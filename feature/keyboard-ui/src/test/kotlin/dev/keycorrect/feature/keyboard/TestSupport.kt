package dev.keycorrect.feature.keyboard

import dev.keycorrect.core.engineapi.Candidate
import dev.keycorrect.core.engineapi.CandidateSource
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.PredictResult
import dev.keycorrect.core.input.Graphemes
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

    /**
     * When set, called after every mutating operation, even inside a batch: a non-coalescing editor
     * reporting each intermediate state (tests wire it to [echo]).
     */
    var onOperation: (() -> Unit)? = null

    private fun changed() = onOperation?.invoke()

    val composing: String
        get() = if (compStart < 0) "" else text.substring(compStart, compEnd)

    /** Moves the cursor as the user would (tap in the text), without any composing change. */
    fun moveCursor(to: Int) {
        cursor = to
    }

    /** Puts [s] in the field with the cursor at [at] (default: the end). */
    fun setText(s: String, at: Int = s.length) {
        text.setLength(0)
        text.append(s)
        cursor = at
        compStart = -1
        compEnd = -1
    }

    private fun replaceRegion(s: CharSequence): Int {
        val start = if (compStart >= 0) compStart else cursor
        val end = if (compStart >= 0) compEnd else cursor
        text.replace(start, end, s.toString())
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
        compStart = -1
        compEnd = -1
        changed()
    }

    override fun textBeforeCursor(maxChars: Int): CharSequence? {
        readCalls++
        return text.substring(maxOf(0, cursor - maxChars), cursor)
    }

    override fun textAfterCursor(maxChars: Int): CharSequence? {
        readCalls++
        return text.substring(cursor, minOf(text.length, cursor + maxChars))
    }

    override fun <T> batchEdit(block: () -> T): T {
        batches++
        return block()
    }

    override fun deleteBackward(sensitive: Boolean) {
        deletes += sensitive
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
        controller.onSelectionChanged(cursor, cursor, compStart, compEnd)
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
