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
import dev.keycorrect.core.input.FieldKind
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.input.TextEditor
import dev.keycorrect.core.learning.AutocorrectReverted
import dev.keycorrect.core.learning.CommitMethod as Learned
import dev.keycorrect.core.learning.DeviceClass
import dev.keycorrect.core.learning.LearningSink
import dev.keycorrect.core.learning.RevertKind
import dev.keycorrect.core.learning.SentenceContext
import dev.keycorrect.core.learning.ShownSuggestion
import dev.keycorrect.core.learning.TouchSample
import dev.keycorrect.core.learning.UserAction
import dev.keycorrect.core.learning.WordCommitted
import dev.keycorrect.core.learning.WordReplaced
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.feature.keyboard.FeedbackKind
import dev.keycorrect.feature.keyboard.hapticWanted
import java.nio.CharBuffer

/**
 * The typing side of prediction: owns the composing word, talks to the editor, feeds the decoder
 * and turns its answers into autocorrect, undo and the suggestion strip. Everything runs on the
 * main thread; only the decoding itself happens elsewhere ([DecoderLoop]).
 *
 * Composing state machine (per field that allows suggestions; anywhere else every key is a plain
 * commit and nothing is read from the editor or sent to an engine, see [canPredict]):
 * - *Idle* (no composing word). A letter starts a word only at a word boundary: the cursor is at
 *   the start of the field, after whitespace or after opening punctuation (see
 *   [TextContextCache.atWordBoundary]). After a digit, `@`, `#`, `/`, `:`, `.`, `_` or a letter
 *   that isn't part of a composing word of ours (an address, hashtag, number, abbreviation, or the
 *   middle of an existing word) a letter is committed directly: no decode, no autocorrect.
 *   Backspace deletes in the editor and, when the cursor lands right after a word, turns that word
 *   back into the composing word. Separators commit directly.
 * - *Composing*. Letters, digits and apostrophes extend the word, shown with `setComposingText`.
 *   Backspace shortens it. A separator (space, punctuation, Enter) finishes it: the autocorrect or
 *   auto-expanding shortcut of the freshest result is applied if allowed, otherwise the word stays.
 *   A layer switch or an unexpected cursor move finishes it as it is.
 * - *Undo window*. After a correction or expansion, the very next backspace restores the typed word
 *   (a space separator is dropped, so the next space or punctuation keeps it without a double
 *   space; punctuation the user typed deliberately stays, see [undoCorrection]); while the user is
 *   putting that word back (finishing it again) it is not auto-corrected again, but that is for
 *   this occurrence only: the permanent behaviour comes from the learning thresholds. Any other key
 *   closes the window.
 * - *Held revert*. The learning layer is told about an undone (or deleted and retyped) correction
 *   only once the restored word is finished, since the user often undoes a wrong fix in order to
 *   type the word they meant (see [resolveEdit]).
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
    clock: () -> Long = { 0L },
    wallClock: () -> Long = System::currentTimeMillis,
    /** Told of word events that deserve a vibration, already filtered by the settings. */
    private val onWordFeedback: (FeedbackKind) -> Unit = {},
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

    private val cache = TextContextCache(editor)
    private var selectionIsRange = false

    private var latest: PredictResult? = null

    /** The composing text [latest] was decoded for. */
    private var latestTyped: String? = null
    private var undo: Undo? = null

    /** Words kept as typed by tapping the literal chip; not auto-corrected again this session. */
    private val revertedWords = ArrayDeque<String>()

    /**
     * The (lower-cased) word whose autocorrection was just taken back: while the user puts it back
     * it isn't corrected again. Lives only until they move on, so it is no lasting rule. Kept even
     * where learning is off.
     */
    private var retypeGuard: String? = null

    /**
     * The word an undo just put back (its touches are unknown). Its first log entry already exists,
     * so finishing it must not log it again with all-null touches; see [record].
     */
    private var restoredWord: String? = null

    private val prompts = StripPrompts(env.scheduler, onStrip)

    // Learning (docs/PLAN.md §6.2-6.3). All of it lives behind the single gate in [learn]; none of
    // it is kept while the gate is closed.
    private val learn = LearningRecorder(learning, clock, wallClock)
    private val shown = ShownTracker()
    private val memory = WordMemory()
    private var edit: EditSession? = null
    private val phrases = PhraseLearning(learn, cache)
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
        /**
         * The corrected word for the sentence buffer, recorded only once the undo window closes (an
         * undone correction must not leave the corrected word in the buffer next to the restored
         * one); see [keep]. Dropped by an undo.
         */
        var pendingWord: String? = null,
        var pendingWordEnd: Boolean = false,
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
        /** The autocorrection whose deleted word this one replaces; see [deletedCorrection]. */
        val replacedCorrection: Tracked?,
    )

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
            touchLog.clear()
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
            prompts.cancelAddWord()
            prompts.cancelNever()
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
        // No typed text outlives the field it was typed in.
        touchLog.clear()
        memory.clear()
    }

    // ------------------------------------------------------------------ keys

    /** A text key: a letter, digit, apostrophe, punctuation or anything else a key types. */
    fun onText(text: String, touch: TouchPoint?) {
        if (!canPredict()) {
            editor.commitText(text)
            return
        }
        prompts.dismiss()
        closeUndo()
        flushSentence(text)
        settleHeldRevert()
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
        prompts.dismiss()
        closeUndo()
        flushSentence(text)
        settleHeldRevert()
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
        prompts.dismiss()
        flushSentence(null)
        if (composing.isNotEmpty()) {
            closeUndo()
            composing.setLength(composing.length - 1)
            touches.removeAt(touches.size - 1)
            if (composing.isEmpty()) {
                edit?.deleted = true
                if (pendingRevertTracked == null) retypeGuard = null
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
            cache.invalidate() // the text isn't what we thought: fall back to a plain backspace
        }
        if (edit != null) endEdit() // deleting on past a word that was being rewritten
        val hadRange = selectionIsRange
        editor.deleteBackward(false)
        if (hadRange) {
            cache.invalidate()
        } else if (cache.beforeKnown) {
            cache.dropLastCluster()
        }
        loop.advance()
        latest = null
        if (!recomposePreviousWord()) {
            dropPendingRevert() // the restored word can't be followed any more
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
        prompts.dismiss()
        closeUndo()
        flushSentence("\n")
        if (composing.isNotEmpty()) finishWord(separator = null, allowUndo = false)
        endEdit()
        flushPendingRevert() // Enter finishes a restored word as it is
        if (commitsLineBreak) cache.appendBefore("\n")
        loop.advance()
        latest = null
        publish(StripModel.Empty)
    }

    /** The layer changed: a word in progress ends as typed. */
    fun onLayerSwitch() {
        closeUndo()
        prompts.dismiss()
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
        prompts.dismiss()
        closeUndo()
        flushSentence(null)
        settleHeldRevert()
        retypeGuard = null // a strip tap moves on from the restored word, however it is settled
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
        prompts.askNever(slot.insert)
    }

    /** "Yes" on the never-suggest prompt: hides the word now and tells the learning layer. */
    fun onNeverConfirm() {
        val word = prompts.neverWord ?: return
        prompts.cancelNever()
        if (canPredict() && learn.open) {
            locallyBlocked.addLast(word)
            while (locallyBlocked.size > MAX_BLOCKED) locallyBlocked.removeFirst()
            learn.emit(UserAction(learn.stamp(), learn.profile, UserAction.Kind.NeverSuggest, word))
            latest = latest?.unblocked()
            if (composing.isNotEmpty()) requestWord() else requestNextWord()
        }
        publish(prompts.base)
    }

    /** "Cancel" on the never-suggest prompt. */
    fun onNeverCancel() {
        prompts.declineNever()
    }

    /**
     * The "Add word to dictionary" prompt was tapped. The word is added only if the field still
     * allows suggestions and learning; a prompt that outlived either is dropped.
     */
    fun onAddWordTap() {
        val word = prompts.addWord ?: return
        val allowed = canPredict() && profile?.learningEnabled == true
        prompts.dismiss()
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
        return cache.beforeAvailable && cache.atWordBoundary() && !cache.cursorInsideWord()
    }

    /** Commits [text] as it is, without decoding anything for it. */
    private fun commitPlain(text: String) {
        endEdit()
        editor.commitText(text)
        cache.appendBefore(text)
        loop.advance()
        latest = null
        if (prompts.base != StripModel.Empty) publish(StripModel.Empty)
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
            cache.appendBefore(text)
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
        val keptAsTyped = userKeepsAsTyped(typed)
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
        cache.appendBefore(committed)
        if (cursorInside) {
            cache.prependAfter(tail)
        } else if (separator != null) {
            cache.appendBefore(separator)
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
        wordFeedback(decision, typed, separator, keptAsTyped)
        if (pendingRevertTracked == null) retypeGuard = null // the restored word is settled
    }

    /**
     * The vibration for the word just finished at a separator: a double tick for an autocorrection,
     * a soft buzz for a word that is in no dictionary and wasn't corrected. Only here, once per
     * word, never per key; shortcuts, words the user explicitly keeps, single letters and anything
     * that isn't a plain word (digits, symbols, addresses) stay silent.
     */
    private fun wordFeedback(
        decision: Decision?,
        typed: String,
        separator: String?,
        keptAsTyped: Boolean,
    ) {
        val kind =
            when (decision) {
                is Decision.Correct -> FeedbackKind.Autocorrected
                is Decision.Expand -> return
                null ->
                    if (!keptAsTyped && !startsAddress(separator) && isUnknownPlainWord(typed)) {
                        FeedbackKind.UnknownWord
                    } else return
            }
        if (hapticWanted(kind, settings)) onWordFeedback(kind)
    }

    /** A word followed by one of these is the start of an address or path, not a word. */
    private fun startsAddress(separator: String?): Boolean =
        separator != null && separator.length == 1 && separator[0] in ADDRESS_SEPARATORS

    private fun isUnknownPlainWord(word: String): Boolean {
        if (backend.predictor == null) return false // the dictionary isn't loaded: all is unknown
        val kind = profile?.kind
        if (kind == FieldKind.Email || kind == FieldKind.Url) return false
        if (word.count { it.isLetter() } < 2) return false
        if (!word.first().isLetter() || !word.all { it.isLetter() || isApostrophe(it) }) {
            return false
        }
        return backend.shortcutFor(word) == null && !isKnown(word)
    }

    /** The user has explicitly said to keep [word] as typed: no need to buzz about it. */
    private fun userKeepsAsTyped(word: String): Boolean {
        val editing = edit?.tracked
        return word.lowercase() in revertedWords ||
            retypeGuard?.let { sameWord(it, word) } == true ||
            (editing != null && editing.autocorrected && sameWord(word, editing.typed)) ||
            deletedCorrection(word) != null
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
            ?.takeIf { it.sequence == loop.sequence && latestTyped == typed }
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
                retypeGuard?.let { sameWord(it, typed) } != true &&
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
        if (retypeGuard?.let { sameWord(it, word) } == true) return false
        // The corrected word was deleted and this is the word they typed originally, put back.
        if (deletedCorrection(word) != null) return false
        return settings.autocorrectEnabled &&
            p.autocorrectEnabled &&
            !(word.isNotEmpty() && isApostrophe(word.last())) &&
            word.lowercase() !in revertedWords
    }

    /**
     * Backspace right after a correction: puts the typed word back. Only done if the text before
     * the cursor is still exactly what we wrote (UTF-16 units, so emoji and combining marks count
     * like the editor counts them); returns false, changing nothing, if not (the cursor moved or
     * the text was edited without us hearing of it yet).
     *
     * After a space the word comes back as the composing word with the cursor at its end and
     * WITHOUT the space, so the user can keep it with the next space or punctuation (no double
     * space) or edit it. It is then an edit session on the remembered correction, so keep / edit /
     * switch is classified when it is finished (see [resolveEdit]).
     *
     * After punctuation (or a line break) the user typed that on purpose, so the restored word
     * keeps it: `teh.` stays `teh.`, as committed text with the cursor after it, like the old
     * behaviour. There is no composing word to finish later, so the classification is settled now:
     * the punctuation already being there is the "finished unchanged" case, i.e. the restored word
     * counts as kept (an AutocorrectReverted with the immediate-backspace kind). The user can still
     * backspace the punctuation and edit the word; that is a plain recompose and not tracked.
     */
    private fun undoCorrection(u: Undo): Boolean {
        val expected = u.applied + u.separator
        if (editor.textBeforeCursor(expected.length)?.toString() != expected) return false
        val keepsSeparator = isDeliberateSeparator(u.separator)
        editor.batchEdit {
            editor.deleteSurroundingText(expected.length, 0)
            if (keepsSeparator) editor.commitText(u.typed + u.separator)
            else editor.setComposingText(u.typed)
        }
        if (cache.beforeKnown) cache.dropBefore(expected.length)
        touchLog.markLastReverted(u.applied)
        edit = null
        u.correction?.pendingWord = null // the corrected word is not recorded; see below
        u.correction?.let { reportImmediateRevert(it) }
        if (keepsSeparator) {
            cache.appendBefore(u.typed + u.separator)
            flushPendingRevert() // finished as typed, punctuation already there: a keep
            if (u.typed.any { it.isLetter() }) {
                learn.sentenceWord(u.typed, SentenceContext.endsSentence(u.separator))
            }
            // Still no auto-correction if the user backspaces the punctuation and finishes again.
            retypeGuard = u.typed.lowercase()
            restoredWord = u.typed
            loop.advance()
            latest = null
            publish(StripModel.Empty)
            requestNextWord()
            return true
        }
        composing.append(u.typed)
        repeat(u.typed.length) { touches.add(null) }
        val held = u.correction?.tracked
        if (learn.open && held != null && pendingRevertTracked === held) {
            edit = EditSession(u.typed, held)
        }
        retypeGuard = u.typed.lowercase() // after the hold: holding another revert clears it
        restoredWord = u.typed
        requestWord()
        return true
    }

    /** Punctuation or a line break the user typed on purpose (anything but a plain space). */
    private fun isDeliberateSeparator(separator: String): Boolean = separator.any {
        !it.isWhitespace() || it == '\n'
    }

    /**
     * After a backspace landed the cursor right after a word, makes that word the composing word
     * again so its suggestions come back. The editor's copy is verified, deleted and re-inserted as
     * composing text (the word's touches are unknown). Returns true if it did.
     */
    private fun recomposePreviousWord(): Boolean {
        if (backend.predictor == null) return false
        ensureContext()
        if (cache.cursorInsideWord()) return false
        val start = cache.trailingWordStart()
        if (start < 0 || (start == 0 && cache.beforeTruncated)) return false
        val word = cache.before.substring(start)
        if (editor.textBeforeCursor(word.length)?.toString() != word) {
            cache.invalidate()
            return false
        }
        editor.batchEdit {
            editor.deleteSurroundingText(word.length, 0)
            editor.setComposingText(word)
        }
        cache.truncateBefore(start)
        composing.append(word)
        repeat(word.length) { touches.add(null) }
        beginEdit(word)
        requestWord()
        return true
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
        if (leadingSpace) cache.appendBefore(" ")
        cache.appendBefore(if (cursorInside) expansion.head else expansion.text)
        if (cursorInside) cache.prependAfter(expansion.tail)
        if (trailingSpace) cache.appendBefore(" ")
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
        retypeGuard = null // settling a held revert may have set it again; the word is dealt with
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
        cache.appendBefore(typed)
        if (space) cache.appendBefore(" ")
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
        retypeGuard = null // the literal is kept (and remembered in revertedWords)
        publish(StripModel.Empty)
        if (
            profile?.learningEnabled == true &&
                typed.any { it.isLetter() } &&
                !backend.isKnownWord(typed)
        ) {
            prompts.showAddWord(typed)
        }
        requestNextWord()
    }

    private fun needsLeadingSpace(): Boolean {
        if (!cache.beforeKnown) return false
        val c = cache.before.lastOrNull() ?: return false
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
            context = TypingContext(cache.before.toString(), cache.after, fieldClass()),
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
        latestTyped = typed
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
        cache.ensureBefore()
        cache.ensureAfter()
    }

    /**
     * Re-reads the text before the cursor, after input starts or the cursor moved externally. This
     * and the first word start are the only reads while typing; auto-capitalisation then runs off
     * the cache. Skipped when no engine could use it (the editor is then never read at all).
     */
    private fun refreshContext() {
        cache.invalidate()
        if (canPredict() && backend.predictor != null) cache.ensureBefore()
    }

    /**
     * Whether a capital is wanted at the cursor, computed from the cached text, or null when the
     * cache can't tell (prediction off, text unread or unavailable, or a truncated start the answer
     * depends on): the caller asks the editor then.
     */
    fun capsFromCache(mode: AutoCapitalize): Boolean? {
        if (mode == AutoCapitalize.Characters) return true
        if (!canPredict() || !cache.beforeReadable) return null
        // A composing word starts with a letter, so no capital is wanted inside or after it.
        if (composing.isNotEmpty()) return false
        return cache.capitalizeAtCursor(mode)
    }

    // ------------------------------------------------------------------ misc

    /** Ends the composing word without correcting it. */
    private fun finishAsTyped() {
        if (composing.isEmpty()) return
        val typed = composing.toString()
        val touchList = ArrayList(touches)
        val pre = capturePre()
        editor.finishComposingText()
        cache.appendBefore(typed)
        composing.setLength(0)
        touches.clear()
        record(typed, typed, touchList, CommitMethod.Typed)
        completeWord(typed, typed, Learned.Typed, null, pre, touchList, null, null, false)
        if (pendingRevertTracked == null) retypeGuard = null
        loop.advance()
        latest = null
        publish(StripModel.Empty)
    }

    private fun resetWordState() {
        retypeGuard = null
        restoredWord = null
        composing.setLength(0)
        touches.clear()
        cache.reset()
        latest = null
        closeUndo()
        endEdit()
        shown.clear()
        loop.advance()
        prompts.cancelAddWord()
        prompts.cancelNever()
        prompts.base = StripModel.Empty
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
        val restored = restoredWord
        restoredWord = null
        if (restored != null && restored == typed && touchList.all { it == null }) return
        touchLog.record(WordRecord(typed, committed, touchList, layout.id, method))
    }

    private fun publish(base: StripModel) {
        prompts.base = base
        if (learn.open) shown.onStrip(base, composing.length, learn.now())
        onStrip(prompts.overlaid())
    }

    // ------------------------------------------------------------------ learning

    /** True where long-pressing a suggestion can ask to never suggest it (a learning event). */
    val canBlockSuggestions: Boolean
        get() = learn.open

    /** The kind of device and orientation the keyboard is used on (part of the input profile). */
    fun setDevice(device: DeviceClass, landscape: Boolean) = learn.setDevice(device, landscape)

    /**
     * The touch-statistics key ([InputProfile.touchKey]) of the layout on screen: the same value
     * the learning events carry. Safe to read from any thread.
     */
    val touchKey: String
        get() = learn.touchKey

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
                learn.fieldAllowsLearning &&
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
        phrases.clear()
        pendingSentence = null
        pendingRevertTracked = null
        undo?.correction?.pendingKept = null
        undo?.correction?.pendingWord = null
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
        dropPendingRevert() // the field changed before the restored word was finished
        phrases.judgeTaken()
        phrases.clear()
        flushSentence(null)
    }

    /** The undo window closed without a backspace: the autocorrection stood. */
    private fun closeUndo() {
        val u = undo ?: return
        undo = null
        keep(u.correction)
    }

    private fun keep(c: Correction?) {
        if (c == null) return
        c.pendingWord?.let { learn.sentenceWord(it, c.pendingWordEnd) }
        c.pendingWord = null
        val event = c.pendingKept ?: return
        c.pendingKept = null
        learn.emit(event)
    }

    /**
     * One backspace took the correction back (rows 6, 7, 22). Nothing is reported yet: the user may
     * be undoing a wrong fix to type the word they meant. The revert is held until the restored
     * word is finished (see [resolveEdit]).
     */
    private fun reportImmediateRevert(c: Correction) {
        c.pendingKept = null
        val t = c.tracked ?: return
        if (!learn.open) return
        hold(t, RevertKind.ImmediateBackspace)
    }

    /**
     * Holds the revert of [t], whose restored word [Tracked.typed] is in the text, until it is
     * finished. Settled by [resolveEdit] (edited or swapped), [flushPendingRevert] (the user moved
     * on, so it was kept) or [dropPendingRevert] (abandoned: nothing is reported).
     */
    private fun hold(t: Tracked, kind: RevertKind) {
        if (pendingRevertTracked != null && pendingRevertTracked !== t) flushPendingRevert()
        t.undone = kind
        pendingRevertTracked = t
        retypeGuard = t.typed.lowercase()
    }

    /** The user typed on without touching the restored word: it stands, so the revert is a keep. */
    private fun flushPendingRevert() {
        retypeGuard = null
        val t = pendingRevertTracked ?: return
        pendingRevertTracked = null
        val kind = t.undone
        t.undone = null
        memory.remove(t)
        if (edit?.tracked === t) edit?.tracked = null
        if (kind == null || !learn.open) return
        learn.emit(
            AutocorrectReverted(
                learn.stamp(),
                learn.profile,
                t.context,
                t.typed,
                t.committed,
                kind,
                t.typed,
            )
        )
    }

    /**
     * The restored word was abandoned (deleted entirely, or the field or cursor changed before it
     * was finished): there is no evidence either way, so nothing is reported.
     */
    private fun dropPendingRevert() {
        retypeGuard = null
        val t = pendingRevertTracked ?: return
        pendingRevertTracked = null
        t.undone = null
        memory.remove(t)
        if (edit?.tracked === t) edit?.tracked = null
    }

    /** A key was typed: if no restored word is being edited, it was left alone and stands. */
    private fun settleHeldRevert() {
        if (edit == null && composing.isEmpty()) flushPendingRevert()
    }

    /** The word [word] before the cursor was re-composed for editing. */
    private fun beginEdit(word: String) {
        edit = null
        if (!learn.open) return
        val found = memory.find(word, SentenceContext.lastWords(cache.before), learn.now())
        if (pendingRevertTracked != null && pendingRevertTracked !== found) dropPendingRevert()
        edit = EditSession(word, found)
    }

    /** The edit was abandoned (the user moved on without finishing the word). */
    private fun endEdit() {
        val e = edit ?: return
        edit = null
        val held = pendingRevertTracked
        if (held != null && e.tracked === held) dropPendingRevert()
    }

    private enum class Resolution {
        None,
        Unchanged,
        Reported,

        /** An autocorrection's typed word is back and held; the caller adds no new word for it. */
        Deferred,
    }

    /**
     * A re-composed word ended as [x]. If it was a remembered word, says what happened to it: an
     * autocorrection put back, edited or swapped for a suggestion; or any other word replaced.
     *
     * An undone or deleted-and-retyped autocorrection of T into C is classified by how the restored
     * word ends, not by the undo itself:
     * - finished as T again: nothing yet, it is [Resolution.Deferred] (a keep once the user moves
     *   on, see [flushPendingRevert]; an edit of it later is judged here again);
     * - finished as an X ≠ T related to T or C: `EditedLater` with X (the user meant X; no keep),
     *   or `SwitchedToSuggestion` if X came from a suggestion tap;
     * - finished as C, or as an unrelated word: nothing is reported (a rewording, not a verdict on
     *   the correction);
     * - abandoned or left with the field: [dropPendingRevert], nothing is reported.
     */
    private fun resolveEdit(s: EditSession?, x: String, viaPick: Boolean): Resolution {
        if (s == null) return Resolution.None
        val t = s.tracked
        if (t == null) {
            return if (sameWord(x, s.original)) Resolution.Unchanged else Resolution.None
        }
        val held = t.undone
        if (pendingRevertTracked === t) pendingRevertTracked = null
        t.undone = null
        // A held revert waits for the user, not for the clock.
        if (held == null && !memory.isFresh(t, learn.now())) {
            memory.remove(t)
            return if (sameWord(x, s.original)) Resolution.Unchanged else Resolution.None
        }
        if (held == null && sameWord(x, t.committed)) return Resolution.Unchanged
        val profile = learn.profile
        if (t.autocorrected) {
            if (!sameWord(x, t.committed) && sameWord(x, t.typed)) {
                val kind =
                    when {
                        s.deleted -> RevertKind.DeletedAndRetyped
                        held != null -> held
                        else -> RevertKind.EditedLater
                    }
                hold(t, kind)
                return Resolution.Deferred
            }
            memory.remove(t)
            retypeGuard = null
            val kind =
                when {
                    sameWord(x, t.committed) -> null
                    !relatedEdit(t.typed, t.committed, x) -> null
                    viaPick -> RevertKind.SwitchedToSuggestion
                    else -> RevertKind.EditedLater
                }
            if (kind == null) return Resolution.None
            learn.emit(
                AutocorrectReverted(
                    learn.stamp(),
                    profile,
                    t.context,
                    t.typed,
                    t.committed,
                    kind,
                    x,
                )
            )
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
        val known = cache.beforeReadable
        val context = if (known) SentenceContext.lastWords(cache.before) else emptyList()
        val kept = phrases.keptNow(known)
        return Pre(
            context,
            known && context.isEmpty(),
            shown.snapshot(learn.now()),
            kept,
            offered(),
            if (known && edit?.tracked == null) deletedCorrection(null, context) else null,
        )
    }

    /**
     * The remembered autocorrection T→C whose corrected word C was deleted from the text, so that
     * the word now being written ([typed], or any word when null) stands where C stood, whether or
     * not the deletion went through a re-composed word. Found by what the text says, not by an
     * [EditSession]: a session ends at any unrelated edit or cursor report, while the deletion of a
     * word the user wrote a moment ago can take many keystrokes (and characters typed after the
     * word and deleted again). Needs the same evidence as everything else here, checked against the
     * editor's text: the word is still inside the tracking window, the words before the one being
     * written are the words that were before C ([context]), and C no longer stands after them
     * anywhere in the text around the cursor. Null when that can't be verified.
     */
    private fun deletedCorrection(
        typed: String?,
        context: List<String>? = null,
    ): Tracked? {
        if (!learn.open || typed != null && typed.isEmpty()) return null
        if (!cache.beforeReadable || !cache.afterKnown) return null
        val now = learn.now()
        if (memory.size == 0) return null
        return memory
            .autocorrections({ context ?: SentenceContext.lastWords(cache.before) }, typed, now)
            .firstOrNull { !standsInText(it) }
    }

    /** Whether the corrected word of [t] still stands after its context in the cached text. */
    private fun standsInText(t: Tracked): Boolean {
        val text =
            if (cache.after.isEmpty()) cache.before.toString() else "${cache.before} ${cache.after}"
        val word = t.committed
        var from = 0
        while (true) {
            val i = text.indexOf(word, from, ignoreCase = true)
            if (i < 0) return false
            val end = i + word.length
            val bounded =
                (i == 0 || !isWordChar(text[i - 1])) &&
                    (end == text.length || !isWordChar(text[end]))
            // A view, not a copy: this runs for every match of a common word like "the".
            if (bounded && SentenceContext.lastWords(CharBuffer.wrap(text, 0, i)) == t.context) {
                return true
            }
            from = i + 1
        }
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || isApostrophe(c)

    /**
     * The session that explains how [pre]'s word came to be: the one being edited, or, for a word
     * written where a deleted corrected word stood, one made from the memory of that correction.
     */
    private fun sessionFor(pre: Pre): EditSession? {
        val current = edit
        if (current?.tracked != null) return current
        val t = pre.replacedCorrection ?: return current
        return EditSession(t.committed, t).also { it.deleted = true }
    }

    private fun offered(): List<String> {
        val slot =
            listOf(prompts.base.left, prompts.base.centre, prompts.base.right).firstOrNull {
                it?.kind == SlotKind.Phrase
            }
        return if (slot == null) emptyList() else phraseWords(slot.insert)
    }

    private fun slotRank(slot: StripSlot): Int =
        when (slot) {
            prompts.base.centre -> 0
            prompts.base.left -> 1
            prompts.base.right -> 2
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
        val session = sessionFor(pre)
        edit = null
        val isPick = method == Learned.SuggestionPicked || method == Learned.PredictionPicked
        val resolution = resolveEdit(session, committed, isPick)
        phrases.advance(committed, pre.phraseKept, pre.offered, pre.context, startWatch = true)
        if (resolution == Resolution.Unchanged) return null
        val settled = resolution == Resolution.Reported || resolution == Resolution.Deferred
        // A restored word stays the one remembered word; it must not be remembered twice.
        val tracked =
            if (resolution == Resolution.Deferred) null
            else Tracked(typed, committed, pre.context, method, learn.now(), runnerUp)
        tracked?.let(memory::add)
        val event =
            if (settled && !isPick) {
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
            if (correction != null && sentenceEnd != null) {
                correction.pendingWord = committed
                correction.pendingWordEnd = sentenceEnd
            } else if (sentenceEnd != null) {
                learn.sentenceWord(committed, sentenceEnd)
            } else {
                pendingSentence = committed
            }
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
     * [MAX_TOUCH_OFFSET] key sizes from that key isn't plausibly aimed at it and is dropped. A word
     * with more than [MAX_MISMATCHED_POSITIONS] mismatched positions yields no samples.
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
        fun aligned(i: Int): Int =
            when {
                n == m -> i
                i < prefix -> i
                i >= n - suffix -> m - (n - i)
                else -> -1
            }
        // A word with more than one mismatched position (intended != registered) is too unclear
        // to say which touch was aimed where: no samples at all.
        var mismatches = 0
        for (i in 0 until n) {
            val j = aligned(i)
            if (j < 0 || typed[i].lowercaseChar() != committed[j].lowercaseChar()) mismatches++
        }
        if (mismatches > MAX_MISMATCHED_POSITIONS) return List(n) { null }
        return List(n) { i ->
            val touch = touchList.getOrNull(i) ?: return@List null
            val j = aligned(i)
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
        val session = sessionFor(pre)
        edit = null
        resolveEdit(session, words.first(), viaPick = true)
        phrases.advance(words.first(), pre.phraseKept, pre.offered, pre.context, startWatch = false)
        phrases.take(words, pre.context)
        words.forEachIndexed { i, w ->
            if (i < words.lastIndex || trailingSpace) learn.sentenceWord(w, false)
            else pendingSentence = w
        }
    }

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

    private companion object {
        const val MAX_CANDIDATES = 3
        const val MAX_PHRASE_WORDS = 4
        const val MAX_REVERTED = 16
        const val MAX_BLOCKED = 32
        const val MAX_TOUCH_OFFSET = 0.7f

        /** Most positions of a word where the intended char differs from the one registered. */
        const val MAX_MISMATCHED_POSITIONS = 1

        /** Text ending in one of these needs no space before an inserted next word. */
        /** Separators that make the word before them part of an address or path. */
        const val ADDRESS_SEPARATORS = "@/\\_"

        const val NO_SPACE_AFTER = "([{<\"'“‘/@#-"
    }
}
