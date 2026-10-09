package dev.keycorrect.feature.keyboard.prediction

import dev.keycorrect.core.engineapi.Candidate
import dev.keycorrect.core.engineapi.CandidateSource
import dev.keycorrect.core.engineapi.ComposingInput
import dev.keycorrect.core.engineapi.FieldClass
import dev.keycorrect.core.engineapi.KeyLayoutModel
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.PredictResult
import dev.keycorrect.core.engineapi.TouchPoint
import dev.keycorrect.core.engineapi.TypingContext
import dev.keycorrect.core.input.FieldKind
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.input.Graphemes
import dev.keycorrect.core.input.TextEditor
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.feature.keyboard.TimeoutHandle

/**
 * The typing side of prediction: owns the composing word, talks to the editor, feeds the decoder
 * and turns its answers into autocorrect, undo and the suggestion strip. Everything runs on the
 * main thread; only the decoding itself happens elsewhere ([DecoderLoop]).
 *
 * Composing state machine (per field that allows suggestions; anywhere else every key is a plain
 * commit and nothing is read from the editor or sent to an engine, see [canPredict]):
 * - *Idle* (no composing word). A letter starts a word, unless the cursor sits inside an existing
 *   word. Backspace deletes in the editor and, when the cursor lands right after a word, turns that
 *   word back into the composing word. Separators commit directly.
 * - *Composing*. Letters, digits and apostrophes extend the word, shown with `setComposingText`.
 *   Backspace shortens it. A separator (space, punctuation, Enter) finishes it: the autocorrect or
 *   auto-expanding shortcut of the freshest result is applied if allowed, otherwise the word stays.
 *   A layer switch or an unexpected cursor move finishes it as it is.
 * - *Undo window*. After a correction or expansion, the very next backspace restores the typed word
 *   (separator kept) and that word is never auto-corrected again. Any other key closes it.
 *
 * Every input event advances the decoder's sequence, so results for older input are dropped. The
 * text before the cursor (≤ [TypingContext.MAX_BEFORE_CHARS]) is read from the editor at the start
 * of a word after a cursor jump and then maintained locally; the text after the cursor (≤
 * [TypingContext.MAX_AFTER_CHARS]) is read at the same moments, since typing doesn't change it.
 */
internal class PredictiveInput(
    private val editor: TextEditor,
    private val env: PredictionEnvironment,
    private val onStrip: (StripModel) -> Unit,
) {
    /** The touches of recently finished words, for Phase 3. In memory only. */
    val touchLog = WordTouchLog()

    /** The keys touches are relative to; changing it re-bases the word being composed. */
    var layout: KeyLayoutModel = KeyLayoutModel(0L, emptyList())
        set(value) {
            if (field.id != value.id) touches.replaceAll { null }
            field = value
        }

    private var profile: FieldProfile? = null
    private var settings = KeyboardSettings()
    private var predictionOn = false

    private val composing = StringBuilder()
    private val touches = ArrayList<TouchPoint?>()

    private val before = StringBuilder()
    private var beforeKnown = false
    private var beforeTruncated = false
    private var after = ""
    private var afterKnown = false
    private var selectionIsRange = false

    private var latest: PredictResult? = null
    private var undo: Undo? = null
    private val revertedWords = ArrayDeque<String>()

    private var stripBase = StripModel.Empty
    private var addWord: String? = null
    private var addWordTimer: TimeoutHandle? = null

    private val loop: DecoderLoop
        get() = env.loop

    private val backend: PredictionBackend
        get() = env.backend

    private class Undo(
        val typed: String,
        val applied: String,
        val separator: String,
    )

    private sealed interface Decision {
        data class Correct(val text: String) : Decision

        data class Expand(val expansion: Expansion) : Decision
    }

    /**
     * The single gate for everything that reads the field's text, composes or talks to an engine:
     * suggestions must be on in the settings and for the field, and the field must not be
     * sensitive. Every other path checks this and nothing else.
     */
    fun canPredict(): Boolean = predictionOn

    /** True while a word is being composed. */
    val isComposing: Boolean
        get() = composing.isNotEmpty()

    // ------------------------------------------------------------------ lifecycle

    /**
     * A field was focused, or the keyboard re-shown on it. Forgets all word state (the caches are
     * re-read when needed); [newField] also forgets the words the user reverted.
     */
    fun onStartInput(profile: FieldProfile, settings: KeyboardSettings, newField: Boolean) {
        if (composing.isNotEmpty()) editor.finishComposingText()
        this.profile = profile
        this.settings = settings
        predictionOn = computeCanPredict(profile, settings)
        if (newField) revertedWords.clear()
        resetWordState()
        selectionIsRange = false
    }

    /** Settings changed. Finishes the word as typed if suggestions were just switched off. */
    fun updateSettings(settings: KeyboardSettings): Boolean {
        val old = this.settings
        this.settings = settings
        val profile = profile ?: return false
        val was = predictionOn
        predictionOn = computeCanPredict(profile, settings)
        var edited = false
        if (was && !predictionOn) edited = finishAsTyped()
        val relevant =
            old.suggestionsEnabled != settings.suggestionsEnabled ||
                old.autocorrectEnabled != settings.autocorrectEnabled ||
                old.nextWordPredictions != settings.nextWordPredictions ||
                old.phraseCompletion != settings.phraseCompletion
        if (relevant) {
            loop.advance()
            latest = null
            if (!predictionOn) publish(StripModel.Empty)
            else if (composing.isNotEmpty()) requestWord() else publish(StripModel.Empty)
        }
        return edited
    }

    /**
     * The field loses the keyboard (input finished or keyboard hidden). Returns true if it edited.
     */
    fun onFinishInput(): Boolean {
        val edited = finishAsTyped()
        resetWordState()
        return edited
    }

    // ------------------------------------------------------------------ keys

    /** A text key: a letter, digit, apostrophe, punctuation or anything else a key types. */
    fun onText(text: String, touch: TouchPoint?) {
        if (!canPredict()) {
            editor.commitText(text)
            return
        }
        dismissAddWord()
        undo = null
        val c = text.singleOrNull()
        val composingNow = composing.isNotEmpty()
        if (c != null && composingNow && (c.isLetterOrDigit() || isApostrophe(c))) {
            append(c, touch)
        } else if (c != null && c.isLetter() && !composingNow && startsWord()) {
            append(c, touch)
        } else {
            separator(text)
        }
    }

    /** Space, or any other separator committed as text. */
    fun onSeparator(text: String) {
        if (!canPredict()) {
            editor.commitText(text)
            return
        }
        dismissAddWord()
        undo = null
        separator(text)
    }

    /**
     * Backspace. Returns true if the only edit made was a bare backward delete (which the caller
     * counts like a plain delete when matching selection callbacks), false if it composed or
     * rewrote text.
     */
    fun onBackspace(): Boolean {
        val sensitive = profile?.sensitive == true
        if (!canPredict()) {
            editor.deleteBackward(sensitive)
            return true
        }
        dismissAddWord()
        if (composing.isNotEmpty()) {
            undo = null
            composing.setLength(composing.length - 1)
            touches.removeAt(touches.size - 1)
            if (composing.isEmpty()) {
                editor.commitText("")
                loop.advance()
                latest = null
                publish(StripModel.Empty)
                requestNextWord()
            } else {
                editor.setComposingText(composing)
                requestWord()
            }
            return false
        }
        undo?.let {
            undoCorrection(it)
            return false
        }
        val hadRange = selectionIsRange
        editor.deleteBackward(false)
        if (hadRange) {
            invalidateContext()
        } else if (beforeKnown) {
            val n = Graphemes.lastClusterLength(before)
            before.setLength(maxOf(0, before.length - n))
            if (beforeTruncated && before.length < MIN_TRUSTED_BEFORE) beforeKnown = false
        }
        loop.advance()
        latest = null
        return !recomposePreviousWord().also { if (!it) publish(StripModel.Empty) }
    }

    /**
     * Enter is about to be performed: finishes the composing word (with its correction) first.
     * Returns true if that edited the text.
     */
    fun beforeEnter(): Boolean {
        if (!canPredict()) return false
        dismissAddWord()
        undo = null
        var edited = false
        if (composing.isNotEmpty()) {
            finishWord(separator = null, allowUndo = false)
            edited = true
        }
        loop.advance()
        latest = null
        publish(StripModel.Empty)
        invalidateContext()
        return edited
    }

    /** The layer changed: a word in progress ends as typed. Returns true if it edited. */
    fun onLayerSwitch(): Boolean {
        undo = null
        dismissAddWord()
        return finishAsTyped()
    }

    // ------------------------------------------------------------------ selection changes

    /** Records whether the latest reported selection is a range. */
    fun noteSelection(collapsed: Boolean) {
        selectionIsRange = !collapsed
    }

    /**
     * True if a selection update is just the editor echoing the composing word as it stands now
     * (cursor at its end, region as long as the word), however late it arrives.
     */
    fun isEchoOfComposing(cursor: Int, composingStart: Int, composingEnd: Int): Boolean =
        composing.isNotEmpty() &&
            cursor >= 0 &&
            composingStart >= 0 &&
            cursor == composingEnd &&
            composingEnd - composingStart == composing.length

    /**
     * The cursor or text changed behind our back: the word in progress ends as it is and nothing
     * decoded so far applies any more. Returns true if it edited.
     */
    fun onExternalChange(): Boolean {
        val edited = finishAsTyped()
        resetWordState()
        publish(StripModel.Empty)
        return edited
    }

    // ------------------------------------------------------------------ strip taps

    /** A strip slot was tapped. Returns true if the editor's text changed. */
    fun onStripTap(slot: StripSlot): Boolean {
        if (!canPredict()) return false
        dismissAddWord()
        undo = null
        return if (slot.kind == SlotKind.Literal) keepLiteral() else pick(slot)
    }

    /** The "Add word to dictionary" prompt was tapped. */
    fun onAddWordTap() {
        val word = addWord ?: return
        backend.addWord(word)
        dismissAddWord()
    }

    // ------------------------------------------------------------------ composing

    private fun computeCanPredict(profile: FieldProfile, settings: KeyboardSettings): Boolean =
        settings.suggestionsEnabled && profile.suggestionsEnabled && !profile.sensitive

    /** A letter may start a composing word: an engine exists and the cursor isn't mid-word. */
    private fun startsWord(): Boolean {
        if (backend.predictor == null) return false
        ensureContext()
        return !cursorInsideWord()
    }

    private fun append(c: Char, touch: TouchPoint?) {
        composing.append(c)
        touches.add(touch)
        editor.setComposingText(composing)
        requestWord()
    }

    /** A separator [text]: finishes the composing word (if any) and commits [text]. */
    private fun separator(text: String) {
        if (composing.isNotEmpty()) {
            finishWord(separator = text, allowUndo = true)
        } else {
            editor.commitText(text)
            appendBefore(text)
            loop.advance()
            latest = null
        }
        publish(StripModel.Empty)
        requestNextWord()
    }

    /**
     * Ends the composing word. [separator] (null for Enter, which the caller performs) is committed
     * after it. Applies the autocorrect/expansion of the freshest result when allowed.
     */
    private fun finishWord(separator: String?, allowUndo: Boolean) {
        val typed = composing.toString()
        val touchList = ArrayList(touches)
        val decision = decide(typed)
        composing.setLength(0)
        touches.clear()
        loop.advance()
        latest = null

        var committed = typed
        var method = CommitMethod.Typed
        var cursorInside = false
        var tail = ""
        editor.batchEdit {
            when (decision) {
                null -> editor.finishComposingText()
                is Decision.Correct -> {
                    editor.commitText(decision.text)
                    committed = decision.text
                    method = CommitMethod.Autocorrect
                }
                is Decision.Expand -> {
                    val e = decision.expansion
                    method = CommitMethod.Expansion
                    if (e.cursor in 0 until e.text.length) {
                        editor.commitText(e.head)
                        editor.commitText(e.tail, 0)
                        committed = e.head
                        tail = e.tail
                        cursorInside = true
                    } else {
                        editor.commitText(e.text)
                        committed = e.text
                    }
                }
            }
            if (separator != null && !cursorInside) editor.commitText(separator)
        }
        appendBefore(committed)
        if (cursorInside) {
            after = (tail + after).take(TypingContext.MAX_AFTER_CHARS)
        } else if (separator != null) {
            appendBefore(separator)
        }
        undo =
            if (allowUndo && decision != null && separator != null && !cursorInside) {
                Undo(typed, committed, separator)
            } else {
                null
            }
        record(typed, committed, touchList, method)
    }

    /**
     * What to do with [typed] at a separator, from the freshest result (decoding now if needed).
     */
    private fun decide(typed: String): Decision? {
        if (backend.predictor == null) return null
        val result = freshResult(typed) ?: return null
        val pending = pendingCorrection(result, typed) ?: return null
        return if (pending.source == CandidateSource.Shortcut) {
            Decision.Expand(env.expander.expand(pending.text))
        } else {
            Decision.Correct(pending.text)
        }
    }

    private fun freshResult(typed: String): PredictResult? {
        latest
            ?.takeIf { it.sequence == loop.sequence }
            ?.let {
                return it
            }
        return loop.predictNow(request(loop.sequence, typed, touches.toList()))
    }

    /**
     * The candidate that [result] would apply at a separator: an auto-expanding shortcut, or the
     * autocorrect when both the setting and the field allow it and the user hasn't rejected it.
     */
    private fun pendingCorrection(result: PredictResult, typed: String): Candidate? {
        if (typed.isEmpty()) return null
        val first = result.candidates.firstOrNull()
        if (
            first != null &&
                first.source == CandidateSource.Shortcut &&
                first.text != typed &&
                backend.shortcutFor(typed)?.autoExpand == true
        ) {
            return first
        }
        if (!autocorrectAllowed(typed)) return null
        return result.autocorrect?.takeIf { it.text != typed }
    }

    private fun autocorrectAllowed(word: String): Boolean {
        val p = profile ?: return false
        return settings.autocorrectEnabled &&
            p.autocorrectEnabled &&
            !(word.isNotEmpty() && isApostrophe(word.last())) &&
            word.lowercase() !in revertedWords
    }

    /** Backspace right after a correction: put the typed word back, keeping the separator. */
    private fun undoCorrection(u: Undo) {
        undo = null
        val appliedLength = u.applied.length + u.separator.length
        editor.batchEdit {
            editor.deleteSurroundingText(appliedLength, 0)
            editor.commitText(u.typed + u.separator)
        }
        if (beforeKnown) {
            before.setLength(maxOf(0, before.length - appliedLength))
            appendBefore(u.typed + u.separator)
        }
        revertedWords.addLast(u.typed.lowercase())
        while (revertedWords.size > MAX_REVERTED) revertedWords.removeFirst()
        touchLog.markLastReverted(u.applied)
        loop.advance()
        latest = null
        publish(StripModel.Empty)
        requestNextWord()
    }

    /**
     * After a backspace landed the cursor right after a word, makes that word the composing word
     * again so its suggestions come back. The editor's copy is deleted and re-inserted as composing
     * text (the word's touches are unknown). Returns true if it did.
     */
    private fun recomposePreviousWord(): Boolean {
        if (backend.predictor == null) return false
        ensureContext()
        if (cursorInsideWord()) return false
        val start = trailingWordStart()
        if (start < 0 || (start == 0 && beforeTruncated)) return false
        val word = before.substring(start)
        editor.batchEdit {
            editor.deleteSurroundingText(word.length, 0)
            editor.setComposingText(word)
        }
        before.setLength(start)
        composing.append(word)
        repeat(word.length) { touches.add(null) }
        requestWord()
        return true
    }

    /** Start of the word ending at the cursor in [before], or -1 if there isn't a usable one. */
    private fun trailingWordStart(): Int {
        var i = before.length
        if (i == 0 || !before[i - 1].isLetterOrDigit()) return -1
        while (i > 0 && (before[i - 1].isLetterOrDigit() || isApostrophe(before[i - 1]))) i--
        while (i < before.length && isApostrophe(before[i])) i++
        val length = before.length - i
        if (length == 0 || length > MAX_RECOMPOSE_CHARS) return -1
        if ((i until before.length).none { before[it].isLetter() }) return -1
        return i
    }

    // ------------------------------------------------------------------ suggestion taps

    private fun pick(slot: StripSlot): Boolean {
        val typed = composing.toString()
        val nextWord = typed.isEmpty()
        val expansion =
            if (slot.kind == SlotKind.Shortcut) env.expander.expand(slot.insert)
            else Expansion(slot.insert)
        val touchList = ArrayList(touches)
        if (nextWord) ensureContext()
        val leadingSpace = nextWord && needsLeadingSpace()
        composing.setLength(0)
        touches.clear()
        loop.advance()
        latest = null

        val cursorInside = expansion.cursor in 0 until expansion.text.length
        val trailingSpace = settings.autoSpaceAfterPick && !cursorInside
        editor.batchEdit {
            if (leadingSpace) editor.commitText(" ")
            if (cursorInside) {
                editor.commitText(expansion.head)
                editor.commitText(expansion.tail, 0)
            } else {
                editor.commitText(expansion.text)
            }
            if (trailingSpace) editor.commitText(" ")
        }
        if (leadingSpace) appendBefore(" ")
        appendBefore(if (cursorInside) expansion.head else expansion.text)
        if (cursorInside) after = (expansion.tail + after).take(TypingContext.MAX_AFTER_CHARS)
        if (trailingSpace) appendBefore(" ")
        if (!nextWord) {
            val method =
                when (slot.kind) {
                    SlotKind.Phrase -> CommitMethod.PhraseTap
                    SlotKind.Shortcut -> CommitMethod.Expansion
                    else -> CommitMethod.SuggestionTap
                }
            record(typed, expansion.text, touchList, method)
        }
        publish(StripModel.Empty)
        requestNextWord()
        return true
    }

    private fun keepLiteral(): Boolean {
        val typed = composing.toString()
        if (typed.isEmpty()) return false
        val touchList = ArrayList(touches)
        composing.setLength(0)
        touches.clear()
        loop.advance()
        latest = null
        val space = settings.autoSpaceAfterPick
        editor.batchEdit {
            editor.finishComposingText()
            if (space) editor.commitText(" ")
        }
        appendBefore(typed)
        if (space) appendBefore(" ")
        revertedWords.addLast(typed.lowercase())
        while (revertedWords.size > MAX_REVERTED) revertedWords.removeFirst()
        record(typed, typed, touchList, CommitMethod.LiteralTap)
        publish(StripModel.Empty)
        if (typed.any { it.isLetter() } && !backend.isKnownWord(typed)) showAddWord(typed)
        requestNextWord()
        return true
    }

    private fun needsLeadingSpace(): Boolean {
        if (!beforeKnown) return false
        val c = before.lastOrNull() ?: return false
        return !c.isWhitespace() && c !in NO_SPACE_AFTER
    }

    // ------------------------------------------------------------------ decoding

    private fun requestWord() {
        val seq = loop.advance()
        loop.submit(request(seq, composing.toString(), touches.toList()), ::onResult)
    }

    private fun requestNextWord() {
        val seq = loop.advance()
        latest = null
        if (!settings.nextWordPredictions || backend.predictor == null) return
        ensureContext()
        loop.submit(request(seq, "", emptyList()), ::onResult)
    }

    private fun request(seq: Long, typed: String, touchList: List<TouchPoint?>): PredictRequest =
        PredictRequest(
            context = TypingContext(before.toString(), after, fieldClass()),
            composing = ComposingInput(typed, touchList),
            layout = layout,
            maxCandidates = MAX_CANDIDATES,
            maxPhraseWords = if (settings.phraseCompletion) MAX_PHRASE_WORDS else 0,
            autocorrectAllowed = autocorrectAllowed(typed),
            sequence = seq,
        )

    private fun fieldClass(): FieldClass =
        when (profile?.kind) {
            FieldKind.Email -> FieldClass.Email
            FieldKind.Url -> FieldClass.Url
            FieldKind.PersonName -> FieldClass.PersonName
            FieldKind.PostalAddress -> FieldClass.PostalAddress
            else -> FieldClass.Text
        }

    private fun onResult(result: PredictResult) {
        if (result.sequence != loop.sequence || !canPredict()) return
        latest = result
        val typed = composing.toString()
        publish(
            StripBuilder.build(
                result,
                typed,
                pendingCorrection(result, typed),
                env.expander::preview,
            )
        )
    }

    // ------------------------------------------------------------------ context cache

    /**
     * Reads the text around the cursor if it isn't known. Never called when [canPredict] is false.
     */
    private fun ensureContext() {
        if (!canPredict()) return
        if (!beforeKnown) {
            val text = editor.textBeforeCursor(TypingContext.MAX_BEFORE_CHARS)
            before.setLength(0)
            if (text != null) before.append(text)
            beforeTruncated = text != null && text.length >= TypingContext.MAX_BEFORE_CHARS
            beforeKnown = true
        }
        if (!afterKnown) {
            after = editor.textAfterCursor(TypingContext.MAX_AFTER_CHARS)?.toString().orEmpty()
            afterKnown = true
        }
    }

    private fun appendBefore(text: String) {
        if (!beforeKnown) return
        before.append(text)
        val excess = before.length - TypingContext.MAX_BEFORE_CHARS
        if (excess > 0) {
            before.delete(0, excess)
            beforeTruncated = true
        }
    }

    private fun cursorInsideWord(): Boolean =
        afterKnown && after.isNotEmpty() && after[0].isLetterOrDigit()

    private fun invalidateContext() {
        beforeKnown = false
        afterKnown = false
    }

    // ------------------------------------------------------------------ misc

    /** Ends the composing word without correcting it. Returns true if there was one. */
    private fun finishAsTyped(): Boolean {
        if (composing.isEmpty()) return false
        val typed = composing.toString()
        val touchList = ArrayList(touches)
        editor.finishComposingText()
        appendBefore(typed)
        composing.setLength(0)
        touches.clear()
        record(typed, typed, touchList, CommitMethod.Typed)
        loop.advance()
        latest = null
        publish(StripModel.Empty)
        return true
    }

    private fun resetWordState() {
        composing.setLength(0)
        touches.clear()
        invalidateContext()
        before.setLength(0)
        beforeTruncated = false
        after = ""
        latest = null
        undo = null
        loop.advance()
        addWordTimer?.cancel()
        addWordTimer = null
        addWord = null
        stripBase = StripModel.Empty
        onStrip(StripModel.Empty)
    }

    /** Records a finished word for Phase 3, unless the field forbids learning. */
    private fun record(
        typed: String,
        committed: String,
        touchList: List<TouchPoint?>,
        method: CommitMethod,
    ) {
        val p = profile ?: return
        if (!p.learningEnabled || p.sensitive) return
        touchLog.record(WordRecord(typed, committed, touchList, layout.id, method))
    }

    private fun publish(base: StripModel) {
        stripBase = base
        onStrip(base.withAddWord(addWord))
    }

    private fun showAddWord(word: String) {
        addWordTimer?.cancel()
        addWord = word
        addWordTimer =
            env.scheduler.schedule(ADD_WORD_PROMPT_MS) {
                addWordTimer = null
                if (addWord != null) {
                    addWord = null
                    onStrip(stripBase)
                }
            }
    }

    private fun dismissAddWord() {
        if (addWord == null) return
        addWordTimer?.cancel()
        addWordTimer = null
        addWord = null
        onStrip(stripBase)
    }

    private fun isApostrophe(c: Char) = c == '\'' || c == '’' || c == 'ʼ'

    private companion object {
        const val MAX_CANDIDATES = 3
        const val MAX_PHRASE_WORDS = 4
        const val MAX_RECOMPOSE_CHARS = 32
        const val MAX_REVERTED = 16
        const val ADD_WORD_PROMPT_MS = 3_000L

        /** A truncated cache shorter than this is re-read before it is trusted. */
        const val MIN_TRUSTED_BEFORE = 32

        /** Text ending in one of these needs no space before an inserted next word. */
        const val NO_SPACE_AFTER = "([{<\"'“‘/@#-"
    }
}
