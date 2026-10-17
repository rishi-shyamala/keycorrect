package dev.keycorrect.feature.keyboard

import android.view.KeyEvent
import dev.keycorrect.core.engineapi.Candidate
import dev.keycorrect.core.engineapi.CandidateSource
import dev.keycorrect.core.engineapi.GestureRequest
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.PredictResult
import dev.keycorrect.core.input.EditAction
import dev.keycorrect.core.input.Graphemes
import dev.keycorrect.core.input.Surrounding
import dev.keycorrect.core.input.TextEditor
import dev.keycorrect.feature.keyboard.prediction.DecoderLoop
import dev.keycorrect.feature.keyboard.prediction.PredictionBackend
import dev.keycorrect.feature.keyboard.prediction.PredictionEnvironment
import dev.keycorrect.feature.keyboard.prediction.Predictor
import dev.keycorrect.feature.keyboard.prediction.ShortcutExpander
import dev.keycorrect.feature.keyboard.prediction.ShortcutRule
import java.util.concurrent.Executor

/**
 * An editor that keeps real text, cursor and composing region, like a text field would, so tests
 * can assert on what the user would see. All edits happen at the cursor.
 */
internal class SimEditor : TextEditor {
    val text = StringBuilder()
    var cursor = 0
    var compStart = -1
    var compEnd = -1
    var enters = 0

    /** Whether Enter inserts a line break (a multi-line field) rather than doing nothing. */
    var enterInsertsLineBreak = false
    var batches = 0

    /** What [cursorCapsMode] reports; tests set it to mimic the editor. */
    var capsMode = 0
    var capsQueries = 0

    /** The `sensitive` argument of each delete. */
    val deletes = mutableListOf<Boolean>()

    /** Counts of calls, for "never composes / never reads" assertions. */
    var composeCalls = 0
    var readCalls = 0

    /** How many of [readCalls] were [textAfterCursor]. */
    var afterCalls = 0

    /**
     * When set, called after every mutating operation, even inside a batch: a non-coalescing editor
     * reporting each intermediate state (tests wire it to [echo]).
     */
    var onOperation: (() -> Unit)? = null

    private fun changed() = onOperation?.invoke()

    val composing: String
        get() = if (compStart < 0) "" else text.substring(compStart, compEnd)

    /**
     * The other end of a selection, or -1 when the selection is just the cursor. The selection is
     * [selStart] until [selEnd]; [cursor] is the end that moves.
     */
    var anchor = -1

    val selStart: Int
        get() = if (anchor < 0) cursor else minOf(anchor, cursor)

    val selEnd: Int
        get() = if (anchor < 0) cursor else maxOf(anchor, cursor)

    val selected: String
        get() = text.substring(selStart, selEnd)

    /** What [contextMenuAction] cut or copied. */
    var clipboard = ""

    /** Every [sendKey] as (keyCode, metaState), and every [setSelection] as (start, end). */
    val keys = mutableListOf<Pair<Int, Int>>()
    val selections = mutableListOf<Pair<Int, Int>>()
    val menuActions = mutableListOf<EditAction>()

    /** Moves the cursor as the user would (tap in the text), without any composing change. */
    fun moveCursor(to: Int) {
        cursor = to
        anchor = -1
    }

    /** Puts [s] in the field with the cursor at [at] (default: the end). */
    fun setText(s: String, at: Int = s.length) {
        text.setLength(0)
        text.append(s)
        cursor = at
        anchor = -1
        compStart = -1
        compEnd = -1
    }

    private fun replaceRegion(s: CharSequence): Int {
        val start = if (compStart >= 0) compStart else selStart
        val end = if (compStart >= 0) compEnd else selEnd
        text.replace(start, end, s.toString())
        anchor = -1
        return start
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int) {
        val start = replaceRegion(text)
        compStart = -1
        compEnd = -1
        cursor =
            if (newCursorPosition > 0) start + text.length + newCursorPosition - 1
            else start + newCursorPosition
        changed()
    }

    override fun setComposingText(text: CharSequence) {
        composeCalls++
        val start = replaceRegion(text)
        compStart = start
        compEnd = start + text.length
        cursor = compEnd
        changed()
    }

    override fun finishComposingText() {
        val was = compStart >= 0
        compStart = -1
        compEnd = -1
        if (was) changed()
    }

    override fun deleteSurroundingText(before: Int, after: Int) {
        val from = maxOf(0, cursor - before)
        val to = minOf(text.length, cursor + after)
        text.delete(from, to)
        cursor = from
        anchor = -1
        compStart = -1
        compEnd = -1
        changed()
    }

    override fun textBeforeCursor(maxChars: Int): CharSequence? {
        readCalls++
        return text.substring(maxOf(0, selStart - maxChars), selStart)
    }

    override fun textAfterCursor(maxChars: Int): CharSequence? {
        readCalls++
        afterCalls++
        return text.substring(selEnd, minOf(text.length, selEnd + maxChars))
    }

    override fun <T> batchEdit(block: () -> T): T {
        batches++
        return block()
    }

    override fun deleteBackward(sensitive: Boolean) {
        deletes += sensitive
        if (anchor >= 0) {
            // Like the real editor: a selection is deleted as a whole.
            text.delete(selStart, selEnd)
            cursor = selStart
            anchor = -1
            compStart = -1
            compEnd = -1
            changed()
            return
        }
        val deleted = cursor > 0
        if (deleted) {
            val n = Graphemes.lastClusterLength(text.substring(maxOf(0, cursor - 32), cursor))
            text.delete(cursor - n, cursor)
            cursor -= n
        }
        compStart = -1
        compEnd = -1
        if (deleted) changed()
    }

    override fun performEnter() {
        enters++
        if (enterInsertsLineBreak) commitText("\n", 1)
    }

    override fun cursorCapsMode(): Int {
        capsQueries++
        return capsMode
    }

    /**
     * What the platform reports after an edit: feed this to the controller's selection callback.
     */
    fun echo(controller: KeyboardController) =
        controller.onSelectionChanged(selStart, selEnd, compStart, compEnd)

    override fun setSelection(start: Int, end: Int) {
        selections += start to end
        anchor = if (start == end) -1 else start
        cursor = end
        changed()
    }

    /**
     * Arrows (shift extends the selection), Home/End of the line, up/down keeping the column, and
     * Delete; anything else is only recorded. Chars, not graphemes: enough for the tests.
     */
    override fun sendKey(keyCode: Int, metaState: Int) {
        keys += keyCode to metaState
        val extend = metaState and KeyEvent.META_SHIFT_ON != 0
        val from = cursor
        val to =
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT ->
                    if (!extend && anchor >= 0) selStart else maxOf(0, cursor - 1)
                KeyEvent.KEYCODE_DPAD_RIGHT ->
                    if (!extend && anchor >= 0) selEnd else minOf(text.length, cursor + 1)
                KeyEvent.KEYCODE_MOVE_HOME -> lineStart(cursor)
                KeyEvent.KEYCODE_MOVE_END -> lineEnd(cursor)
                KeyEvent.KEYCODE_DPAD_UP -> {
                    val start = lineStart(cursor)
                    if (start == 0) 0 else minOf(lineStart(start - 1) + cursor - start, start - 1)
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val end = lineEnd(cursor)
                    if (end == text.length) text.length
                    else minOf(end + 1 + cursor - lineStart(cursor), lineEnd(end + 1))
                }
                KeyEvent.KEYCODE_DEL -> {
                    deleteBackward(false)
                    return
                }
                else -> return
            }
        if (extend) {
            if (anchor < 0) anchor = from
            cursor = to
            if (anchor == cursor) anchor = -1
        } else {
            cursor = to
            anchor = -1
        }
        compStart = -1
        compEnd = -1
        changed()
    }

    private fun lineStart(at: Int): Int =
        text.lastIndexOf("\n", at - 1).let { if (at == 0) 0 else it + 1 }

    private fun lineEnd(at: Int): Int =
        text.indexOf("\n", at).let { if (it < 0) text.length else it }

    override fun contextMenuAction(action: EditAction): Boolean {
        menuActions += action
        when (action) {
            EditAction.Copy -> clipboard = selected
            EditAction.Cut -> {
                clipboard = selected
                commitText("", 1)
            }
            EditAction.Paste -> commitText(clipboard, 1)
            EditAction.SelectAll -> setSelection(0, text.length)
        }
        return true
    }

    override fun surroundingText(beforeLength: Int, afterLength: Int): Surrounding? {
        readCalls++
        val from = maxOf(0, selStart - beforeLength)
        val to = minOf(text.length, selEnd + afterLength)
        return Surrounding(text.substring(from, to), selStart - from, selEnd - from, from)
    }

    override fun selectedText(maxChars: Int): CharSequence? {
        readCalls++
        return if (anchor < 0) null else selected.take(maxChars)
    }
}

/** A backend whose predictor, shortcuts and known words are set by the test. */
internal class FakeBackend : PredictionBackend {
    override var predictor: Predictor? = null
    val shortcuts = mutableMapOf<String, ShortcutRule>()
    val known = mutableSetOf<String>()
    val added = mutableListOf<String>()

    override fun shortcutFor(trigger: String): ShortcutRule? = shortcuts[trigger.lowercase()]

    override fun isKnownWord(word: String): Boolean = word.lowercase() in known

    override fun addWord(word: String) {
        added += word
    }
}

/** Runs queued work only when told to, so tests control when the decoder thread "finishes". */
internal class ManualExecutor : Executor {
    val queue = ArrayDeque<Runnable>()

    override fun execute(command: Runnable) {
        queue.addLast(command)
    }

    fun runAll() {
        while (queue.isNotEmpty()) queue.removeFirst().run()
    }
}

/** Timers fired by the test. */
internal class ManualScheduler : TimeoutScheduler {
    class Task(val delayMs: Long, val action: Runnable, var cancelled: Boolean = false)

    val tasks = mutableListOf<Task>()

    override fun schedule(delayMs: Long, action: Runnable): TimeoutHandle {
        val task = Task(delayMs, action)
        tasks += task
        return TimeoutHandle { task.cancelled = true }
    }

    fun fireAll() {
        tasks.filterNot { it.cancelled }.forEach { it.action.run() }
        tasks.clear()
    }

    val pending: List<Task>
        get() = tasks.filterNot { it.cancelled }
}

internal fun testEnv(
    backend: PredictionBackend,
    executor: Executor = Executor { it.run() },
    scheduler: TimeoutScheduler = ManualScheduler(),
    expander: ShortcutExpander = ShortcutExpander(),
    postToMain: (Runnable) -> Unit = { it.run() },
    separatorWaitMs: Long = DecoderLoop.SEPARATOR_WAIT_MS,
): PredictionEnvironment {
    val loop = DecoderLoop(executor, postToMain, separatorWaitMs) { backend.predictor }
    return PredictionEnvironment(backend, loop, scheduler, expander)
}

private fun dict(text: String, score: Float = -1f) =
    Candidate(text, score, CandidateSource.Dictionary)

internal fun lit(text: String) = Candidate(text, 0f, CandidateSource.Literal)

/**
 * A scripted predictor: [responses] maps the composing text to its result (an unlisted word gets
 * just its literal). Records every request so tests can inspect what the decoder was asked.
 */
internal class ScriptedPredictor : Predictor {
    val requests = mutableListOf<PredictRequest>()
    val words = mutableMapOf<String, PredictResult>()

    /** What to return for an empty composing word (next-word prediction). */
    var nextWords: List<String> = emptyList()

    fun word(
        typed: String,
        suggestions: List<String>,
        autocorrect: String? = null,
        phrase: String? = null,
        runnerUp: String? = null,
    ) {
        words[typed] =
            PredictResult(
                candidates = suggestions.map { dict(it) },
                literal = lit(typed),
                autocorrect = autocorrect?.let { dict(it) },
                phrase = phrase?.let { Candidate(it, -1f, CandidateSource.Dictionary, true) },
                autocorrectRunnerUp = runnerUp?.let { dict(it) },
            )
    }

    /** Every swipe decode asked for (previews and finals), in order. */
    val gestureRequests = mutableListOf<GestureRequest>()

    /** The words a swipe decodes to, best first; empty means nothing fits. */
    var swipeWords: List<String> = emptyList()

    override fun predictGesture(request: GestureRequest, stale: () -> Boolean): PredictResult {
        gestureRequests += request
        return PredictResult(
            candidates = swipeWords.map { dict(it) },
            literal = null,
            autocorrect = swipeWords.firstOrNull()?.let { dict(it) },
            sequence = request.sequence,
        )
    }

    override fun predict(request: PredictRequest, stale: () -> Boolean): PredictResult {
        requests += request
        val typed = request.composing.text
        val base =
            if (typed.isEmpty()) {
                PredictResult(nextWords.map { dict(it) }, null, null)
            } else {
                words[typed] ?: PredictResult(emptyList(), lit(typed), null)
            }
        return base.copy(sequence = request.sequence)
    }
}
