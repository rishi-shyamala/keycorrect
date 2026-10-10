package dev.keycorrect.feature.keyboard.prediction

import dev.keycorrect.core.engineapi.Candidate
import dev.keycorrect.core.engineapi.CandidateSource
import dev.keycorrect.core.engineapi.ComposingInput
import dev.keycorrect.core.engineapi.FieldClass
import dev.keycorrect.core.engineapi.KeyLayoutModel
import dev.keycorrect.core.engineapi.KeySpec
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.PredictResult
import dev.keycorrect.core.engineapi.TouchPoint
import dev.keycorrect.core.engineapi.TypingContext
import dev.keycorrect.core.input.AutoCapitalize
import dev.keycorrect.core.input.AutoCaps
import dev.keycorrect.core.input.FieldKind
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.input.Graphemes
import dev.keycorrect.core.input.SentenceContext
import dev.keycorrect.core.input.TextEditor
import dev.keycorrect.core.learning.AutocorrectReverted
import dev.keycorrect.core.learning.CommitMethod as Learned
import dev.keycorrect.core.learning.DeviceClass
import dev.keycorrect.core.learning.LearningSink
import dev.keycorrect.core.learning.PhraseOutcome
import dev.keycorrect.core.learning.RevertKind
import dev.keycorrect.core.learning.ShownSuggestion
import dev.keycorrect.core.learning.TouchSample
import dev.keycorrect.core.learning.UserAction
import dev.keycorrect.core.learning.WordCommitted
import dev.keycorrect.core.learning.WordReplaced
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.feature.keyboard.TimeoutHandle

/**
 * The typing side of prediction: owns the composing word, talks to the editor, feeds the decoder
 * and turns its answers into autocorrect, undo and the suggestion strip. Everything runs on the
 * main thread; only the decoding itself happens elsewhere ([DecoderLoop]).
 *
 * Composing state machine (per field that allows suggestions; anywhere else every key is a plain
 * commit and nothing is read from the editor or sent to an engine, see [canPredict]):
 * - *Idle* (no composing word). A letter starts a word only at a word boundary: the cursor is at
 *   the start of the field, after whitespace or after opening punctuation (see [atWordBoundary]).
 *   After a digit, `@`, `#`, `/`, `:`, `.`, `_` or a letter that isn't part of a composing word of
 *   ours (an address, hashtag, number, abbreviation, or the middle of an existing word) a letter is
 *   committed directly: no decode, no autocorrect. Backspace deletes in the editor and, when the
 *   cursor lands right after a word, turns that word back into the composing word. Separators
 *   commit directly.
 * - *Composing*. Letters, digits and apostrophes extend the word, shown with `setComposingText`.
 *   Backspace shortens it. A separator (space, punctuation, Enter) finishes it: the autocorrect or
 *   auto-expanding shortcut of the freshest result is applied if allowed, otherwise the word stays.
 *   A layer switch or an unexpected cursor move finishes it as it is.
 * - *Undo window*. After a correction or expansion, the very next backspace restores the typed word
 *   (separator kept) and that word is never auto-corrected again. Any other key closes it.
 *
 * Every input event advances the decoder's sequence, so results for older input are dropped. The
 * text before the cursor (≤ [TypingContext.MAX_BEFORE_CHARS]) is read from the editor when input
 * starts and after an external cursor move ([refreshContext]) and then maintained locally; the text
 * after the cursor (≤ [TypingContext.MAX_AFTER_CHARS]) is read lazily at the next word start, since
 * typing doesn't change it. The cache also answers auto-capitalisation ([capsFromCache]).
 *
 * At a separator the answer is taken from the decoder without ever blocking on the engine lock; see
 * [DecoderLoop]'s separator policy. When none arrives in time the word is committed as typed.
 */
internal class PredictiveInput(
    private val editor: TextEditor,
    private val env: PredictionEnvironment,
    private val onStrip: (StripModel) -> Unit,
    learning: LearningSink = LearningSink.None,
    private val clock: () -> Long = { 0L },
    wallClock: () -> Long = System::currentTimeMillis,
) {
    /** The touches of recently finished words, for Phase 3. In memory only. */
    val touchLog = WordTouchLog()

    /** The keys touches are relative to; changing it re-bases the word being composed. */
    var layout: KeyLayoutModel = KeyLayoutModel(0L, emptyList())
        set(value) {
            if (field.id != value.id) touches.replaceAll { null }
            field = value
            keyByChar = value.keys.associateBy { it.char.lowercaseChar() }
        }

    private var keyByChar: Map<Char, KeySpec> = emptyMap()

    private var profile: FieldProfile? = null
    private var settings = KeyboardSettings()
    private var predictionOn = false

    private val composing = StringBuilder()
    private val touches = ArrayList<TouchPoint?>()

    private val before = StringBuilder()
    private var beforeKnown = false
    private var beforeAvailable = false
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
    private var neverWord: String? = null
    private var neverTimer: TimeoutHandle? = null

    // Learning (docs/PLAN.md §6.2-6.3). All of it lives behind the single gate in [learn]; none of
    // it is kept while the gate is closed.
    private val learn = LearningRecorder(learning, clock, wallClock)
    private val shown = ShownTracker()
    private val memory = WordMemory()
    private var edit: EditSession? = null
    private var phraseWatch: PhraseWatch? = null
    private var pendingSentence: String? = null
    private var pendingRevertTracked: Tracked? = null

    /** Words the user said never to suggest, hidden at once; the model takes over from there. */
    private val locallyBlocked = ArrayDeque<String>()

    private val loop: DecoderLoop
        get() = env.loop

    private val backend: PredictionBackend
        get() = env.backend

    private class Undo(
        val typed: String,
        val applied: String,
        val separator: String,
        /** Set for an autocorrection (not for a shortcut expansion). */
        val correction: Correction?,
    )

    /** What reverting an autocorrection with the next backspace reports. */
    private class Correction(
        val context: List<String>,
        val tracked: Tracked?,
        /** The "kept" event, emitted once the undo window closes without a backspace. */
        var pendingKept: WordCommitted?,
    )

    /**
     * What was true just before a word was finished, captured while the text is still as it was.
     */
    private class Pre(
        val context: List<String>,
        val sentenceInitial: Boolean,
        val shown: List<ShownSuggestion>,
        /** Words of a taken phrase still in the text, or -1 if no taken phrase is pending. */
        val phraseKept: Int,
        /** The phrase on the strip when the word was finished. */
        val offered: List<String>,
    )

    private class LearnedWord(val tracked: Tracked?, val pendingKept: WordCommitted?)

    private sealed interface Decision {
        data class Correct(val text: String, val runnerUp: String?) : Decision

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
        // What the previous input left open is settled against the previous field's gate.
        settleAll()
        this.profile = profile
        this.settings = settings
        predictionOn = computeCanPredict(profile, settings)
        if (newField) {
            revertedWords.clear()
            locallyBlocked.clear()
            memory.clear()
            learn.newSession()
        }
        learn.setField(fieldClass())
        syncGate()
        resetWordState()
        selectionIsRange = false
        refreshContext()
    }

    /** Settings changed. Finishes the word as typed if suggestions were just switched off. */
    fun updateSettings(settings: KeyboardSettings) {
        val old = this.settings
        this.settings = settings
        val profile = profile ?: return
        val was = predictionOn
        predictionOn = computeCanPredict(profile, settings)
        syncGate()
        if (was && !predictionOn) {
            finishAsTyped()
            cancelAddWord()
            cancelNever()
        }
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
    }

    /**
     * The field loses the keyboard (input finished or keyboard hidden). Idempotent: the platform
     * reports both the end of input and of its view, and a second call finds nothing to do.
     */
    fun onFinishInput() {
        settleAll()
        resetWordState()
    }

    // ------------------------------------------------------------------ keys

    /** A text key: a letter, digit, apostrophe, punctuation or anything else a key types. */
    fun onText(text: String, touch: TouchPoint?) {
        if (!canPredict()) {
            editor.commitText(text)
            return
        }
        dismissPrompts()
        closeUndo()
        flushSentence(text)
        if (edit == null) flushPendingRevert()
        val c = text.singleOrNull()
        val composingNow = composing.isNotEmpty()
        if (c != null && composingNow && (c.isLetterOrDigit() || isApostrophe(c))) {
            append(c, touch)
        } else if (c != null && c.isLetter() && !composingNow) {
            if (startsWord()) append(c, touch) else commitPlain(text)
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
        dismissPrompts()
        closeUndo()
        flushSentence(text)
        if (edit == null) flushPendingRevert()
        separator(text)
    }

    /**
     * Backspace: shortens the composing word, undoes a correction (if the text before the cursor is
     * still what we wrote), or deletes and re-composes the word it lands behind.
     */
    fun onBackspace() {
        val sensitive = profile?.sensitive == true
        if (!canPredict()) {
            editor.deleteBackward(sensitive)
            return
        }
        dismissPrompts()
        flushSentence(null)
        if (composing.isNotEmpty()) {
            closeUndo()
            composing.setLength(composing.length - 1)
            touches.removeAt(touches.size - 1)
            if (composing.isEmpty()) {
                edit?.deleted = true
                shown.clear()
                editor.commitText("")
                loop.advance()
                latest = null
                publish(StripModel.Empty)
                requestNextWord()
            } else {
                editor.setComposingText(composing)
                requestWord()
            }
            return
        }
        val pendingUndo = undo
        undo = null
        if (pendingUndo != null) {
            if (!selectionIsRange && undoCorrection(pendingUndo)) return
            keep(pendingUndo.correction) // the correction stands as far as we can tell
            invalidateContext() // the text isn't what we thought: fall back to a plain backspace
        }
        if (edit != null) endEdit() // deleting on past a word that was being rewritten
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
        if (!recomposePreviousWord()) {
            flushPendingRevert()
            publish(StripModel.Empty)
        }
    }

    /**
     * Enter is about to be performed: finishes the composing word (with its correction) first.
     * [commitsLineBreak] is whether Enter will insert a line break, which the cache then follows;
     * an editor action or key event changes nothing we know of, and if the app reacts (clears the
     * field, say) the selection callback reports it as an external change.
     */
    fun beforeEnter(commitsLineBreak: Boolean) {
        if (!canPredict()) return
        dismissPrompts()
        closeUndo()
        flushSentence("\n")
        if (composing.isNotEmpty()) finishWord(separator = null, allowUndo = false)
        endEdit()
        if (commitsLineBreak) appendBefore("\n")
        loop.advance()
        latest = null
        publish(StripModel.Empty)
    }

    /** The layer changed: a word in progress ends as typed. */
    fun onLayerSwitch() {
        closeUndo()
        dismissPrompts()
        finishAsTyped()
        endEdit()
    }

    // ------------------------------------------------------------------ selection changes

    /** Records whether the latest reported selection is a range. */
    fun noteSelection(collapsed: Boolean) {
        selectionIsRange = !collapsed
    }

    /**
     * The editor reports a composing region of [length] chars where we have a word of another
     * length (it normalised the text, say): takes the word from the editor and decodes it again.
     * Returns false, leaving the state alone, if the editor's text can't be read back as that word.
     */
    fun resyncComposing(length: Int): Boolean {
        if (composing.isEmpty() || length <= 0) return false
        val text = editor.textBeforeCursor(length)?.toString()
        if (text == null || text.length != length) return false
        composing.setLength(0)
        composing.append(text)
        while (touches.size > length) touches.removeAt(touches.size - 1)
        touches.replaceAll { null }
        while (touches.size < length) touches.add(null)
        requestWord()
        return true
    }

    /**
     * The cursor or text changed behind our back: the word in progress ends as it is and nothing
     * decoded so far applies any more. The text before the cursor is read afresh when [collapsed].
     */
    fun onExternalChange(collapsed: Boolean) {
        settleAll()
        resetWordState()
        publish(StripModel.Empty)
        if (collapsed) refreshContext()
    }

    // ------------------------------------------------------------------ strip taps

    /** A strip slot was tapped. */
    fun onStripTap(slot: StripSlot) {
        if (!canPredict()) return
        dismissPrompts()
        closeUndo()
        flushSentence(null)
        if (edit == null) flushPendingRevert()
        if (slot.kind == SlotKind.Literal) keepLiteral() else pick(slot)
    }

    /**
     * A suggestion was long-pressed: asks, inline in the strip, whether to never suggest it again.
     * Only plain suggestions and the autocorrect can be blocked, and only where learning is on (the
     * answer is a learning event).
     */
    fun onSuggestionLongPress(slot: StripSlot) {
        if (!canPredict() || !learn.open) return
        if (slot.kind != SlotKind.Suggestion && slot.kind != SlotKind.Autocorrect) return
        if (slot.insert.isBlank()) return
        addWordTimer?.cancel()
        addWordTimer = null
        addWord = null
        neverTimer?.cancel()
        neverWord = slot.insert
        neverTimer =
            env.scheduler.schedule(NEVER_PROMPT_MS) {
                neverTimer = null
                if (neverWord != null) {
                    neverWord = null
                    onStrip(overlaid())
                }
            }
        onStrip(overlaid())
    }

    /** "Yes" on the never-suggest prompt: hides the word now and tells the learning layer. */
    fun onNeverConfirm() {
        val word = neverWord ?: return
        cancelNever()
        if (canPredict() && learn.open) {
            locallyBlocked.addLast(word)
            while (locallyBlocked.size > MAX_BLOCKED) locallyBlocked.removeFirst()
            learn.emit(UserAction(learn.stamp(), learn.profile, UserAction.Kind.NeverSuggest, word))
            latest = latest?.unblocked()
            if (composing.isNotEmpty()) requestWord() else requestNextWord()
        }
        publish(stripBase)
    }

    /** "Cancel" on the never-suggest prompt. */
    fun onNeverCancel() {
        if (neverWord == null) return
        cancelNever()
        onStrip(overlaid())
    }

    /**
     * The "Add word to dictionary" prompt was tapped. The word is added only if the field still
     * allows suggestions and learning; a prompt that outlived either is dropped.
     */
    fun onAddWordTap() {
        val word = addWord ?: return
        val allowed = canPredict() && profile?.learningEnabled == true
        dismissPrompts()
        if (allowed) {
            backend.addWord(word)
            learn.emit(
                UserAction(learn.stamp(), learn.profile, UserAction.Kind.AddToDictionary, word)
            )
        }
    }

    // ------------------------------------------------------------------ composing

    private fun computeCanPredict(profile: FieldProfile, settings: KeyboardSettings): Boolean =
        settings.suggestionsEnabled && profile.suggestionsEnabled && !profile.sensitive

    /**
     * A letter may start a composing word: an engine exists, the text before the cursor is known
     * and ends at a word boundary, and the cursor isn't inside a word.
     */
    private fun startsWord(): Boolean {
        if (backend.predictor == null) return false
        ensureContext()
        return beforeAvailable && atWordBoundary() && !cursorInsideWord()
    }

    /** True at the start of the field, after whitespace, or after opening punctuation. */
    private fun atWordBoundary(): Boolean {
        val c = before.lastOrNull() ?: return !beforeTruncated
        return c.isWhitespace() || c in OPENING_PUNCTUATION
    }

    /** Commits [text] as it is, without decoding anything for it. */
    private fun commitPlain(text: String) {
        endEdit()
        editor.commitText(text)
        appendBefore(text)
        loop.advance()
        latest = null
        if (stripBase != StripModel.Empty) publish(StripModel.Empty)
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
            endEdit()
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
        val pre = capturePre()
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
        val canUndo = allowUndo && decision != null && separator != null && !cursorInside
        val correction =
            if (decision is Decision.Expand) {
                abandonWord() // expansions are not learned from
                null
            } else {
                completeWord(
                    typed,
                    committed,
                    if (decision == null) Learned.Typed else Learned.Autocorrected,
                    null,
                    pre,
                    touchList,
                    (decision as? Decision.Correct)?.runnerUp,
                    SentenceContext.endsSentence(separator),
                    canUndo && decision is Decision.Correct,
                )
            }
        undo = if (canUndo) Undo(typed, committed, separator!!, correction) else null
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
            Decision.Correct(pending.text, result.autocorrectRunnerUp?.text)
        }
    }

    private fun freshResult(typed: String): PredictResult? {
        latest
            ?.takeIf { it.sequence == loop.sequence }
            ?.let {
                return it
            }
        val seq = loop.sequence
        return loop.resultForSeparator(seq) { request(seq, typed, touches.toList()) }?.unblocked()
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
        // The user is putting back what an autocorrection replaced: not this time.
        val editing = edit?.tracked
        if (editing != null && editing.autocorrected && sameWord(word, editing.typed)) return false
        return settings.autocorrectEnabled &&
            p.autocorrectEnabled &&
            !(word.isNotEmpty() && isApostrophe(word.last())) &&
            word.lowercase() !in revertedWords
    }

    /**
     * Backspace right after a correction: puts the typed word back, keeping the separator. Only
     * done if the text before the cursor is still exactly what we wrote (UTF-16 units, so emoji and
     * combining marks count like the editor counts them); returns false, changing nothing, if not
     * (the cursor moved or the text was edited without us hearing of it yet).
     */
    private fun undoCorrection(u: Undo): Boolean {
        val expected = u.applied + u.separator
        if (editor.textBeforeCursor(expected.length)?.toString() != expected) return false
        editor.batchEdit {
            editor.deleteSurroundingText(expected.length, 0)
            editor.commitText(u.typed + u.separator)
        }
        if (beforeKnown) {
            before.setLength(maxOf(0, before.length - expected.length))
            appendBefore(u.typed + u.separator)
        }
        rejectWord(u.typed)
        touchLog.markLastReverted(u.applied)
        u.correction?.let { reportImmediateRevert(u, it) }
        loop.advance()
        latest = null
        publish(StripModel.Empty)
        requestNextWord()
        return true
    }

    /**
     * After a backspace landed the cursor right after a word, makes that word the composing word
     * again so its suggestions come back. The editor's copy is verified, deleted and re-inserted as
     * composing text (the word's touches are unknown). Returns true if it did.
     */
    private fun recomposePreviousWord(): Boolean {
        if (backend.predictor == null) return false
        ensureContext()
        if (cursorInsideWord()) return false
        val start = trailingWordStart()
        if (start < 0 || (start == 0 && beforeTruncated)) return false
        val word = before.substring(start)
        if (editor.textBeforeCursor(word.length)?.toString() != word) {
            invalidateContext()
            return false
        }
        editor.batchEdit {
            editor.deleteSurroundingText(word.length, 0)
            editor.setComposingText(word)
        }
        before.setLength(start)
        composing.append(word)
        repeat(word.length) { touches.add(null) }
        beginEdit(word)
        requestWord()
        return true
    }

    /**
     * Start of the word ending at the cursor in [before], or -1 if there isn't a usable one. Only a
     * word that [startsWord] would have composed qualifies: it begins with a letter at a word
     * boundary (the start of the field, if the cached text isn't truncated; whitespace; opening
     * punctuation), so "foo.bar", "user@exa", "#tag" and "42x" are left alone.
     */
    private fun trailingWordStart(): Int {
        var i = before.length
        if (i == 0 || !before[i - 1].isLetterOrDigit()) return -1
        while (i > 0 && (before[i - 1].isLetterOrDigit() || isApostrophe(before[i - 1]))) i--
        val boundary = i
        if (boundary == 0) {
            if (beforeTruncated) return -1
        } else {
            val c = before[boundary - 1]
            if (!c.isWhitespace() && c !in OPENING_PUNCTUATION) return -1
        }
        while (i < before.length && isApostrophe(before[i])) i++
        val length = before.length - i
        if (length == 0 || length > MAX_RECOMPOSE_CHARS) return -1
        if (!before[i].isLetter()) return -1
        return i
    }

    // ------------------------------------------------------------------ suggestion taps

    private fun pick(slot: StripSlot) {
        val typed = composing.toString()
        val nextWord = typed.isEmpty()
        val expansion =
            if (slot.kind == SlotKind.Shortcut) env.expander.expand(slot.insert)
            else Expansion(slot.insert)
        val touchList = ArrayList(touches)
        if (nextWord) ensureContext()
        val pre = capturePre()
        val rank = slotRank(slot)
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
        when (slot.kind) {
            SlotKind.Phrase -> takePhrase(expansion.text, pre, trailingSpace)
            SlotKind.Shortcut -> abandonWord()
            else ->
                completeWord(
                    typed,
                    expansion.text,
                    if (nextWord) Learned.PredictionPicked else Learned.SuggestionPicked,
                    rank,
                    pre,
                    touchList,
                    null,
                    if (trailingSpace) false else null,
                    false,
                )
        }
        publish(StripModel.Empty)
        requestNextWord()
    }

    private fun keepLiteral() {
        val typed = composing.toString()
        if (typed.isEmpty()) return
        val touchList = ArrayList(touches)
        val pre = capturePre()
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
        completeWord(
            typed,
            typed,
            Learned.LiteralPicked,
            null,
            pre,
            touchList,
            null,
            if (space) false else null,
            false,
        )
        publish(StripModel.Empty)
        if (
            profile?.learningEnabled == true &&
                typed.any { it.isLetter() } &&
                !backend.isKnownWord(typed)
        ) {
            showAddWord(typed)
        }
        requestNextWord()
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
        val visible = result.unblocked()
        latest = visible
        val typed = composing.toString()
        publish(
            StripBuilder.build(
                visible,
                typed,
                pendingCorrection(visible, typed),
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
        ensureBefore()
        if (!afterKnown) {
            after = editor.textAfterCursor(TypingContext.MAX_AFTER_CHARS)?.toString().orEmpty()
            afterKnown = true
        }
    }

    private fun ensureBefore() {
        if (beforeKnown) return
        val text = editor.textBeforeCursor(TypingContext.MAX_BEFORE_CHARS)
        before.setLength(0)
        if (text != null) before.append(text)
        beforeAvailable = text != null
        beforeTruncated = text != null && text.length >= TypingContext.MAX_BEFORE_CHARS
        beforeKnown = true
    }

    /**
     * Re-reads the text before the cursor, after input starts or the cursor moved externally. This
     * and the first word start are the only reads while typing; auto-capitalisation then runs off
     * the cache. Skipped when no engine could use it (the editor is then never read at all).
     */
    private fun refreshContext() {
        invalidateContext()
        if (canPredict() && backend.predictor != null) ensureBefore()
    }

    /**
     * Whether a capital is wanted at the cursor, computed from the cached text, or null when the
     * cache can't tell (prediction off, text unread or unavailable, or a truncated start the answer
     * depends on): the caller asks the editor then.
     */
    fun capsFromCache(mode: AutoCapitalize): Boolean? {
        if (mode == AutoCapitalize.Characters) return true
        if (!canPredict() || !beforeKnown || !beforeAvailable) return null
        // A composing word starts with a letter, so no capital is wanted inside or after it.
        if (composing.isNotEmpty()) return false
        return AutoCaps.shouldCapitalize(before, mode, startKnown = !beforeTruncated)
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

    /** Ends the composing word without correcting it. */
    private fun finishAsTyped() {
        if (composing.isEmpty()) return
        val typed = composing.toString()
        val touchList = ArrayList(touches)
        val pre = capturePre()
        editor.finishComposingText()
        appendBefore(typed)
        composing.setLength(0)
        touches.clear()
        record(typed, typed, touchList, CommitMethod.Typed)
        completeWord(typed, typed, Learned.Typed, null, pre, touchList, null, null, false)
        loop.advance()
        latest = null
        publish(StripModel.Empty)
    }

    private fun resetWordState() {
        composing.setLength(0)
        touches.clear()
        invalidateContext()
        before.setLength(0)
        beforeTruncated = false
        after = ""
        latest = null
        closeUndo()
        endEdit()
        shown.clear()
        loop.advance()
        cancelAddWord()
        cancelNever()
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
        if (!p.learningEnabled || p.sensitive || !learn.open) return
        touchLog.record(WordRecord(typed, committed, touchList, layout.id, method))
    }

    private fun publish(base: StripModel) {
        stripBase = base
        if (learn.open) shown.onStrip(base, composing.length, learn.now())
        onStrip(overlaid())
    }

    /** The strip with the prompts that take it over, if any. */
    private fun overlaid(): StripModel = stripBase.withAddWord(addWord).withNeverSuggest(neverWord)

    private fun showAddWord(word: String) {
        addWordTimer?.cancel()
        addWord = word
        addWordTimer =
            env.scheduler.schedule(ADD_WORD_PROMPT_MS) {
                addWordTimer = null
                if (addWord != null) {
                    addWord = null
                    onStrip(overlaid())
                }
            }
    }

    private fun cancelAddWord() {
        addWordTimer?.cancel()
        addWordTimer = null
        addWord = null
    }

    private fun cancelNever() {
        neverTimer?.cancel()
        neverTimer = null
        neverWord = null
    }

    /** Any key or tap other than the prompts' own dismisses them. */
    private fun dismissPrompts() {
        if (addWord == null && neverWord == null) return
        cancelAddWord()
        cancelNever()
        onStrip(stripBase)
    }

    // ------------------------------------------------------------------ learning

    /** True where long-pressing a suggestion can ask to never suggest it (a learning event). */
    val canBlockSuggestions: Boolean
        get() = learn.open

    /** The kind of device and orientation the keyboard is used on (part of the input profile). */
    fun setDevice(device: DeviceClass, landscape: Boolean) = learn.setDevice(device, landscape)

    /** Layer plus key height, e.g. "letters@54dp" (the input profile's layout key). */
    fun setLayoutKey(key: String) = learn.setLayoutKey(key)

    /**
     * The one privacy gate: learning is on for this field and in the settings and incognito is off.
     * Closing it drops everything remembered for learning.
     */
    private fun syncGate() {
        val p = profile
        val open =
            canPredict() &&
                p != null &&
                p.learningEnabled &&
                settings.learningEnabled &&
                !settings.incognito
        val wasOpen = learn.open
        learn.configure(open, settings.sentenceBufferEnabled)
        if (wasOpen && !open) wipeLearning()
    }

    private fun wipeLearning() {
        memory.clear()
        shown.clear()
        edit = null
        phraseWatch = null
        pendingSentence = null
        pendingRevertTracked = null
        undo?.correction?.pendingKept = null
        touchLog.clear()
    }

    /**
     * Ends everything left open (a word, an undo window, an edit, a phrase) against the current
     * gate.
     */
    private fun settleAll() {
        finishAsTyped()
        closeUndo()
        endEdit()
        flushPendingRevert()
        judgeTakenPhrase()
        phraseWatch = null
        flushSentence(null)
    }

    /** The undo window closed without a backspace: the autocorrection stood. */
    private fun closeUndo() {
        val u = undo ?: return
        undo = null
        keep(u.correction)
    }

    private fun keep(c: Correction?) {
        val event = c?.pendingKept ?: return
        c.pendingKept = null
        learn.emit(event)
    }

    private fun rejectWord(word: String) {
        revertedWords.addLast(word.lowercase())
        while (revertedWords.size > MAX_REVERTED) revertedWords.removeFirst()
    }

    /** One backspace took the correction back (rows 6, 7). A near-tie waits for a possible pick. */
    private fun reportImmediateRevert(u: Undo, c: Correction) {
        c.pendingKept = null
        val t = c.tracked ?: return
        if (!learn.open) return
        val event =
            AutocorrectReverted(
                learn.stamp(),
                learn.profile,
                c.context,
                u.typed,
                u.applied,
                RevertKind.ImmediateBackspace,
                u.typed,
            )
        if (t.runnerUp != null) {
            flushPendingRevert()
            t.pendingRevert = event
            pendingRevertTracked = t
        } else {
            learn.emit(event)
            memory.remove(t)
        }
    }

    private fun flushPendingRevert() {
        val t = pendingRevertTracked ?: return
        pendingRevertTracked = null
        t.pendingRevert?.let(learn::emit)
        t.pendingRevert = null
        memory.remove(t)
    }

    /** The word [word] before the cursor was re-composed for editing. */
    private fun beginEdit(word: String) {
        edit = null
        if (!learn.open) return
        val found = memory.find(word, SentenceContext.lastWords(before), learn.now())
        if (pendingRevertTracked != null && pendingRevertTracked !== found) flushPendingRevert()
        edit = EditSession(word, found)
    }

    /** The edit was abandoned (the user moved on without finishing the word). */
    private fun endEdit() {
        edit = null
        flushPendingRevert()
    }

    private enum class Resolution {
        None,
        Unchanged,
        Reported,
    }

    /**
     * A re-composed word ended as [x]. If it was a remembered word, says what happened to it: an
     * autocorrection put back, edited or swapped for a suggestion; or any other word replaced.
     */
    private fun resolveEdit(s: EditSession?, x: String, viaPick: Boolean): Resolution {
        if (s == null) return Resolution.None
        val t = s.tracked
        val pending = t?.pendingRevert
        if (t == null) {
            return if (sameWord(x, s.original)) Resolution.Unchanged else Resolution.None
        }
        if (pendingRevertTracked === t) pendingRevertTracked = null
        t.pendingRevert = null
        if (!memory.isFresh(t, learn.now())) {
            memory.remove(t)
            return if (sameWord(x, s.original)) Resolution.Unchanged else Resolution.None
        }
        if (sameWord(x, t.committed) && (pending == null || !t.autocorrected)) {
            return Resolution.Unchanged
        }
        val profile = learn.profile
        if (t.autocorrected) {
            memory.remove(t)
            val kind =
                when {
                    sameWord(x, t.committed) -> null
                    sameWord(x, t.typed) ->
                        when {
                            pending != null -> RevertKind.ImmediateBackspace
                            s.deleted -> RevertKind.DeletedAndRetyped
                            else -> RevertKind.EditedLater
                        }
                    !relatedEdit(t.typed, t.committed, x) -> null
                    viaPick -> RevertKind.SwitchedToSuggestion
                    else -> RevertKind.EditedLater
                }
            if (kind == null) return Resolution.None
            if (kind == RevertKind.ImmediateBackspace && pending != null) {
                learn.emit(pending)
            } else {
                val replacement = if (sameWord(x, t.typed)) t.typed else x
                learn.emit(
                    AutocorrectReverted(
                        learn.stamp(),
                        profile,
                        t.context,
                        t.typed,
                        t.committed,
                        kind,
                        replacement,
                    )
                )
            }
            if (sameWord(x, t.typed)) rejectWord(t.typed)
            return Resolution.Reported
        }
        memory.remove(t)
        learn.emit(
            WordReplaced(
                learn.stamp(),
                profile,
                t.context,
                t.committed,
                x,
                t.picked,
                learn.now() - t.at,
            )
        )
        return Resolution.Reported
    }

    /** A word that learning must not see (an expansion, a shortcut). */
    private fun abandonWord() {
        shown.clear()
        endEdit()
    }

    /** What is true just before a word is finished, read while the text is still as it was. */
    private fun capturePre(): Pre? {
        if (!learn.open) return null
        val known = beforeKnown && beforeAvailable
        val context = if (known) SentenceContext.lastWords(before) else emptyList()
        val watch = phraseWatch
        val kept =
            when {
                watch == null || !watch.taken -> -1
                known -> keptWords(watch.words)
                else -> watch.words.size
            }
        return Pre(
            context,
            known && context.isEmpty(),
            shown.snapshot(learn.now()),
            kept,
            offered(),
        )
    }

    private fun offered(): List<String> {
        val slot =
            listOf(stripBase.left, stripBase.centre, stripBase.right).firstOrNull {
                it?.kind == SlotKind.Phrase
            }
        return if (slot == null) emptyList() else phraseWords(slot.insert)
    }

    private fun slotRank(slot: StripSlot): Int =
        when (slot) {
            stripBase.centre -> 0
            stripBase.left -> 1
            stripBase.right -> 2
            else -> 0
        }

    /**
     * A word was finished: reports what happened to it and remembers it. Returns the correction to
     * hang the undo window on when [canUndo] (the "kept" event of an autocorrection waits for the
     * window to close).
     */
    private fun completeWord(
        typed: String,
        committed: String,
        method: Learned,
        rank: Int?,
        pre: Pre?,
        touchList: List<TouchPoint?>,
        runnerUp: String?,
        sentenceEnd: Boolean?,
        canUndo: Boolean,
    ): Correction? {
        shown.clear()
        if (pre == null) {
            endEdit()
            return null
        }
        val session = edit
        edit = null
        val isPick = method == Learned.SuggestionPicked || method == Learned.PredictionPicked
        val resolution = resolveEdit(session, committed, isPick)
        advancePhrase(committed, pre, startWatch = true)
        if (resolution == Resolution.Unchanged) return null
        val tracked = Tracked(typed, committed, pre.context, method, learn.now(), runnerUp)
        memory.add(tracked)
        val event =
            if (resolution == Resolution.Reported && !isPick) {
                null
            } else {
                WordCommitted(
                    timeMillis = learn.stamp(),
                    profile = learn.profile,
                    context = pre.context,
                    typed = typed,
                    committed = committed,
                    method = method,
                    pickedRank = rank,
                    shown = pre.shown,
                    touches = touchSamples(typed, committed, touchList),
                    sentenceInitial = pre.sentenceInitial,
                    committedIsKnown = isKnown(committed),
                    typedIsKnown = typed.isEmpty() || isKnown(typed),
                    tieRunnerUp = runnerUp,
                    sessionId = learn.sessionId,
                )
            }
        var correction: Correction? = null
        if (method == Learned.Autocorrected && canUndo) {
            correction = Correction(pre.context, tracked, event)
        } else if (event != null) {
            learn.emit(event)
        }
        if (committed.any { it.isLetter() }) {
            if (sentenceEnd != null) learn.sentenceWord(committed, sentenceEnd)
            else pendingSentence = committed
        }
        return correction
    }

    private fun isKnown(word: String): Boolean =
        backend.isKnownWord(word) || backend.isKnownWord(word.lowercase())

    /**
     * One sample per typed char that came from a tap, measured from the centre of the key of the
     * char the user *meant*: the same position of [committed] (case-insensitively). Same-length
     * words align position by position (autocorrections, substitutions); otherwise only the common
     * prefix and suffix do (completions, insertions, deletions). A touch farther than
     * [MAX_TOUCH_OFFSET] key sizes from that key isn't plausibly aimed at it and is dropped.
     */
    private fun touchSamples(
        typed: String,
        committed: String,
        touchList: List<TouchPoint?>,
    ): List<TouchSample?> {
        if (typed.isEmpty()) return emptyList()
        val n = typed.length
        val m = committed.length
        var prefix = 0
        var suffix = 0
        if (n != m) {
            val limit = minOf(n, m)
            while (
                prefix < limit && typed[prefix].lowercaseChar() == committed[prefix].lowercaseChar()
            ) {
                prefix++
            }
            while (
                suffix < limit - prefix &&
                    typed[n - 1 - suffix].lowercaseChar() ==
                        committed[m - 1 - suffix].lowercaseChar()
            ) {
                suffix++
            }
        }
        return List(n) { i ->
            val touch = touchList.getOrNull(i) ?: return@List null
            val j =
                when {
                    n == m -> i
                    i < prefix -> i
                    i >= n - suffix -> m - (n - i)
                    else -> -1
                }
            if (j < 0) return@List null
            val intended = committed[j].lowercaseChar()
            val key = keyByChar[intended] ?: return@List null
            if (key.width <= 0f || key.height <= 0f) return@List null
            val dx = (touch.x - key.centerX) / key.width
            val dy = (touch.y - key.centerY) / key.height
            if (kotlin.math.abs(dx) > MAX_TOUCH_OFFSET || kotlin.math.abs(dy) > MAX_TOUCH_OFFSET) {
                null
            } else {
                TouchSample(intended, dx, dy)
            }
        }
    }

    // ---- phrases (docs/PLAN.md §6.3 rows 18-20)

    /** A phrase chip was tapped: the whole text went in; it is judged at the next word. */
    private fun takePhrase(text: String, pre: Pre?, trailingSpace: Boolean) {
        shown.clear()
        if (pre == null) {
            endEdit()
            return
        }
        val words = phraseWords(text)
        if (words.isEmpty()) {
            endEdit()
            return
        }
        val session = edit
        edit = null
        resolveEdit(session, words.first(), viaPick = true)
        advancePhrase(words.first(), pre, startWatch = false)
        phraseWatch = PhraseWatch(words, pre.context, taken = true, index = words.size)
        words.forEachIndexed { i, w ->
            if (i < words.lastIndex || trailingSpace) learn.sentenceWord(w, false)
            else pendingSentence = w
        }
    }

    /**
     * [x], the next word after a phrase chip was offered or tapped, is compared with the phrase. A
     * phrase that ran to its end, or stopped matching, is reported; one still matching goes on.
     */
    private fun advancePhrase(x: String, pre: Pre, startWatch: Boolean) {
        val w = phraseWatch
        if (w != null) {
            phraseWatch = null
            val matched = if (w.taken) pre.phraseKept else w.index
            if (matched < 0 || matched >= w.words.size) {
                emitPhrase(w, w.words.size, null)
            } else if (sameWord(x, w.words[matched])) {
                if (matched + 1 >= w.words.size) {
                    emitPhrase(w, w.words.size, null)
                } else {
                    phraseWatch = PhraseWatch(w.words, w.context, false, matched + 1)
                }
                return
            } else {
                emitPhrase(w, matched, x)
            }
        }
        if (startWatch && pre.offered.size >= 2 && sameWord(x, pre.offered[0])) {
            phraseWatch = PhraseWatch(pre.offered, pre.context, false, 1)
        }
    }

    /**
     * A tapped phrase and the field is left, or the cursor moved, before another word: judge it
     * now.
     */
    private fun judgeTakenPhrase() {
        val w = phraseWatch ?: return
        phraseWatch = null
        if (!w.taken || !learn.open) return
        val kept = if (beforeKnown && beforeAvailable) keptWords(w.words) else w.words.size
        emitPhrase(w, kept, null)
    }

    private fun emitPhrase(w: PhraseWatch, accepted: Int, divergedWith: String?) {
        learn.emit(
            PhraseOutcome(
                learn.stamp(),
                learn.profile,
                w.context,
                w.words,
                accepted,
                divergedWith,
            )
        )
    }

    /** How many leading words of [words] the text before the cursor still ends with. */
    private fun keptWords(words: List<String>): Int {
        val tail = alnum(before.takeLast(words.sumOf { it.length + 1 } + 16))
        for (k in words.size downTo 1) {
            if (tail.endsWith(alnum(words.take(k).joinToString("")))) return k
        }
        return 0
    }

    private fun alnum(s: CharSequence): String =
        buildString(s.length) { s.forEach { if (it.isLetterOrDigit()) append(it.lowercaseChar()) } }

    // ---- sentence buffer

    /**
     * Sends a word held back until it is known whether a sentence ends right after it. [next] is
     * the text typed immediately after, or null when the word was left (or text came after a
     * space).
     */
    private fun flushSentence(next: String?) {
        val word = pendingSentence ?: return
        pendingSentence = null
        learn.sentenceWord(word, next != null && SentenceContext.endsSentence(next))
    }

    /** Hides what the user said never to suggest, until the model takes over. */
    private fun PredictResult.unblocked(): PredictResult {
        if (locallyBlocked.isEmpty()) return this
        fun blocked(c: Candidate?) = c != null && locallyBlocked.any { sameWord(it, c.text) }
        if (
            candidates.none(::blocked) &&
                !blocked(autocorrect) &&
                !blocked(phrase) &&
                !blocked(autocorrectRunnerUp)
        ) {
            return this
        }
        return copy(
            candidates = candidates.filterNot(::blocked),
            autocorrect = autocorrect?.takeUnless(::blocked),
            phrase = phrase?.takeUnless(::blocked),
            autocorrectRunnerUp = autocorrectRunnerUp?.takeUnless(::blocked),
        )
    }

    private fun isApostrophe(c: Char) = c == '\'' || c == '’' || c == 'ʼ'

    private companion object {
        const val MAX_CANDIDATES = 3
        const val MAX_PHRASE_WORDS = 4
        const val MAX_RECOMPOSE_CHARS = 32
        const val MAX_REVERTED = 16
        const val ADD_WORD_PROMPT_MS = 3_000L
        const val NEVER_PROMPT_MS = 10_000L
        const val MAX_BLOCKED = 32
        const val MAX_TOUCH_OFFSET = 1.2f

        /** A truncated cache shorter than this is re-read before it is trusted. */
        const val MIN_TRUSTED_BEFORE = 32

        /** Text ending in one of these needs no space before an inserted next word. */
        const val NO_SPACE_AFTER = "([{<\"'“‘/@#-"

        /** A word may start right after one of these. */
        const val OPENING_PUNCTUATION = "([{\"'“‘¿¡"
    }
}
