package dev.keycorrect.feature.keyboard.voice

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.keycorrect.core.input.TextEditor
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.core.voice.DictationAssembler
import dev.keycorrect.core.voice.DictationEdit
import dev.keycorrect.core.voice.SttConfig
import dev.keycorrect.core.voice.SttEngine
import dev.keycorrect.core.voice.SttError
import dev.keycorrect.core.voice.SttListener
import dev.keycorrect.core.voice.SttResult
import dev.keycorrect.core.voice.SttSession

/** What the strip shows for voice typing. */
sealed interface VoiceState {
    /** Not dictating, nothing to say. */
    data object Idle : VoiceState

    /**
     * The mic is on. [partial] is the text heard so far, of which the first [stableLength] chars
     * are final and the rest may still change; [finishing] after Stop, while the last result is
     * awaited.
     */
    data class Listening(
        val partial: String = "",
        val finishing: Boolean = false,
        val stableLength: Int = 0,
    ) : VoiceState

    /** Dictation could not start or ended without text; shown briefly, for [error]. */
    data class Failed(val error: SttError) : VoiceState
}

/** Runs [block] later on the main thread; returns a function that cancels it. */
fun interface Scheduler {
    fun postDelayed(delayMs: Long, block: () -> Unit): () -> Unit
}

/** The main-thread plumbing; tests replace it with a synchronous one. */
internal object MainThread {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    /** Runs [block] now when already on the main thread, else posts it. */
    fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    val scheduler = Scheduler { delay, block ->
        val r = Runnable { block() }
        handler.postDelayed(r, delay)
        val cancel: () -> Unit = { handler.removeCallbacks(r) }
        cancel
    }
}

/**
 * Voice typing for one keyboard (docs/PHASE6.md): the long-press on the comma starts it, the
 * recognizer's results become editor edits through a [DictationAssembler], and the strip shows the
 * state ([state], [level]).
 *
 * Everything runs on the main thread: engine and microphone callbacks are posted there through
 * [post], and a late callback of a dictation that has ended is dropped (a generation count). The
 * audio is only recorded between [start] and the end of the dictation; nothing is started in a
 * sensitive field, and nothing here logs text.
 *
 * Ending: Stop ([finish]) lets the engine deliver its last result (with a timeout); the cross
 * ([cancel]) clears the composing text; a typed key, a changed field, the keyboard hiding, the user
 * moving the cursor, or turning the setting off ([stopNow]) cancels the engine and keeps the text
 * as it stands. A single-shot recognizer (one that records itself, like the system's) ends with its
 * first final result; a streaming engine keeps listening after a final until Stop.
 */
@Suppress("TooManyFunctions", "LongParameterList") // one small state machine, its seams injected
class VoiceController
internal constructor(
    private val editor: TextEditor,
    private val env: VoiceEnvironment,
    private val settings: () -> KeyboardSettings,
    private val sensitive: () -> Boolean,
    /** Incognito, or a field that asks for no personalised learning: no phone recognizer. */
    private val privateField: () -> Boolean,
    /** Whether the dictation should capitalise at the start of a sentence. */
    private val capitalize: () -> Boolean,
    /** Called before dictation edits the field and after it ended: the typing side settles. */
    private val settle: () -> Unit,
    private val post: (() -> Unit) -> Unit = MainThread::post,
    private val scheduler: Scheduler = MainThread.scheduler,
    /** Drives the timer and the auto-stop; separate so that a test can step it on its own. */
    private val ticker: Scheduler = scheduler,
    /** Milliseconds from a monotonic clock. */
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) {
    var state: VoiceState by mutableStateOf(VoiceState.Idle)
        private set

    /** Loudness of the voice, 0 to 1, for the level meter. */
    var level: Float by mutableFloatStateOf(0f)
        private set

    /** Whole seconds since dictation started, for the m:ss timer. */
    var elapsedSeconds: Int by mutableIntStateOf(0)
        private set

    /**
     * How far the current phrase is towards the engine's phrase limit, 0 to 1 (the ring around the
     * mic). Read in a draw scope only: it changes about five times a second.
     */
    var phraseFraction: Float by mutableFloatStateOf(0f)
        private set

    /** Speech is being heard right now (the mic pulses). */
    var speaking: Boolean by mutableStateOf(false)
        private set

    /** The phrase is long: the bar asks to pause briefly. Changes only when crossing the line. */
    val phraseHint: Boolean by derivedStateOf { phraseFraction >= HINT_FROM }

    /** Seconds until dictation stops by itself for lack of speech, in the last few; else 0. */
    var silenceCountdown: Int by mutableIntStateOf(0)
        private set

    /** The strip shows the voice bar (listening, or a failure message). */
    val barVisible: Boolean
        get() = state != VoiceState.Idle

    /** The mic is on or the last result is being awaited. */
    val dictating: Boolean
        get() = state is VoiceState.Listening

    private var generation = 0
    private var engine: SttEngine? = null
    private var session: SttSession? = null
    private var capture: CaptureHandle? = null
    private var assembler: DictationAssembler? = null
    private var cancelTimeout: (() -> Unit)? = null
    private var cancelFailure: (() -> Unit)? = null
    private var cancelTick: (() -> Unit)? = null
    private var startedAt = 0L
    private var lastSpeechAt = 0L
    private var progressSeen = false

    /**
     * What the last dictation committed and kept, so a "delete that" said at the start of the next
     * one (the system recognizer has one result per dictation) can remove it if it is still right
     * before the cursor.
     */
    private var lastSessionText = ""

    /** Long-press on the voice key: starts dictation, or stops it when it is already running. */
    fun toggle() {
        if (dictating) finish() else start()
    }

    /** Starts dictating, or shows why it cannot. */
    @Suppress("ReturnCount") // guard clauses
    fun start() {
        if (dictating) return
        clearFailure()
        val s = settings()
        if (!s.voiceEnabled || sensitive()) return
        if (!env.micPermissionGranted()) {
            env.requestMicPermission()
            fail(SttError.NoPermission)
            return
        }
        val gen = ++generation
        val opening =
            env.openEngine(s, privateField()) { v ->
                post { if (gen == generation) level = v.coerceIn(0f, 1f) }
            }
        val opened =
            when (opening) {
                is EngineOpening.Ready -> opening.engine
                is EngineOpening.Unavailable -> {
                    fail(opening.error)
                    return
                }
            }
        engine = opened
        settle()
        assembler =
            DictationAssembler(
                textBefore = editor.textBeforeCursor(CONTEXT_CHARS)?.toString(),
                autoCapitalize = capitalize(),
                previousSession = lastSessionText,
            )
        val listener = SttListener { r -> post { if (gen == generation) onResult(r) } }
        val started = runCatching {
            opened.startSession(configFor(s), listener)
        }
        val newSession = started.getOrNull()
        if (newSession == null) {
            teardown()
            fail(SttError.Engine)
            return
        }
        session = newSession
        if (!opened.info.capturesOwnAudio && !startCapture(gen, opened, newSession)) {
            newSession.cancel()
            teardown()
            fail(SttError.Audio)
            return
        }
        state = VoiceState.Listening()
        startedAt = clock()
        lastSpeechAt = startedAt
        progressSeen = false
        tick(gen)
    }

    /**
     * Twice a second: the timer, and the auto-stop. Dictation stops (as with Stop, keeping the
     * text) after [KeyboardSettings.voiceAutoStopSeconds] without speech, counting down in the last
     * [COUNTDOWN_SECONDS]. Only for an engine that reports progress: the phone recognizer ends by
     * itself.
     */
    private fun tick(gen: Int) {
        cancelTick =
            ticker.postDelayed(TICK_MS) {
                if (gen != generation) return@postDelayed
                val now = clock()
                elapsedSeconds = ((now - startedAt) / MILLIS_PER_SECOND).toInt()
                val limit = settings().voiceAutoStopSeconds
                val finishing = (state as? VoiceState.Listening)?.finishing != false
                if (limit > 0 && progressSeen && !finishing) {
                    val left = limit - (now - lastSpeechAt) / MILLIS_PER_SECOND
                    silenceCountdown = if (left in 1..COUNTDOWN_SECONDS) left.toInt() else 0
                    if (now - lastSpeechAt >= limit * MILLIS_PER_SECOND) {
                        silenceCountdown = 0
                        finish()
                        return@postDelayed
                    }
                } else {
                    silenceCountdown = 0
                }
                tick(gen)
            }
    }

    private fun configFor(s: KeyboardSettings) =
        SttConfig(
            punctuate = s.voiceAutoPunctuation,
            phraseLimitMillis = s.voicePhraseMs.toLong(),
            pauseMillis = s.voicePauseMs.toLong(),
            liveUpdateMillis = s.voiceLiveMs.toLong(),
        )

    private fun startCapture(gen: Int, opened: SttEngine, target: SttSession): Boolean {
        capture =
            env.startCapture(
                opened.info.sampleRate,
                { pcm, n -> target.acceptAudio(pcm, n) },
                { v -> post { if (gen == generation) level = v.coerceIn(0f, 1f) } },
                { post { if (gen == generation) onResult(SttResult.Error(SttError.Audio)) } },
            )
        return capture != null
    }

    /** Stop: the recognizer delivers what it has, and dictation ends with it. */
    fun finish() {
        val current = state as? VoiceState.Listening ?: return
        if (current.finishing) return
        state = current.copy(finishing = true)
        capture?.stop()
        capture = null
        awaitFinal(FINISH_TIMEOUT_MS, FINISH_TIMEOUT_MS)
        session?.finish()
    }

    /**
     * Waits [delayMs] for the last result. When it hasn't come, an engine that is still decoding (a
     * slow device) gets more time, up to [FINISH_MAX_MS] in all; then the text stands as it is and
     * a message says the end may be missing.
     */
    private fun awaitFinal(delayMs: Long, waited: Long) {
        cancelTimeout =
            scheduler.postDelayed(delayMs) {
                if (!dictating) return@postDelayed
                if (session?.busy == true && waited < FINISH_MAX_MS) {
                    awaitFinal(FINISH_POLL_MS, waited + FINISH_POLL_MS)
                } else {
                    stopNow()
                    fail(SttError.Timeout)
                }
            }
    }

    /** The cross: the composing text is cleared, what was committed stays. */
    fun cancel() {
        if (!dictating) return
        session?.cancel()
        assembler?.cancel()?.let(::apply)
        teardown()
    }

    /**
     * Ends dictation at once, keeping the text as it stands (a partial result stays in the field as
     * ordinary text). For a typed key, a new field, the keyboard hiding, a cursor move.
     */
    fun stopNow() {
        clearFailure()
        if (!dictating) return
        session?.cancel()
        editor.finishComposingText()
        teardown(remember = false)
    }

    /** The selection moved by something other than dictation. */
    fun onExternalChange() = stopNow()

    /** The strip's message was dismissed or timed out. */
    fun clearFailure() {
        cancelFailure?.invoke()
        cancelFailure = null
        if (state is VoiceState.Failed) state = VoiceState.Idle
    }

    private fun onResult(result: SttResult) {
        if (!dictating) return
        val current = assembler ?: return
        when (result) {
            is SttResult.Partial -> {
                apply(current.onPartial(result.text, result.stablePrefixLength, result.endsSegment))
                lastSpeechAt = clock()
                state =
                    (state as VoiceState.Listening).copy(
                        partial = result.text,
                        stableLength = result.stablePrefixLength.coerceIn(0, result.text.length),
                    )
            }
            is SttResult.Progress -> {
                progressSeen = true
                speaking = result.speaking
                phraseFraction = fractionOf(result.phraseMillis, result.phraseLimitMillis)
                if (result.speaking) lastSpeechAt = clock()
            }
            is SttResult.Final -> {
                apply(current.onFinal(result.text))
                val finishing = (state as VoiceState.Listening).finishing
                if (finishing || engine?.info?.capturesOwnAudio == true) {
                    teardown()
                } else {
                    state = VoiceState.Listening(finishing = false)
                }
            }
            is SttResult.Error -> {
                apply(current.cancel())
                teardown()
                fail(result.kind)
            }
        }
    }

    /** Releases the engine and the microphone and returns to idle. */
    private fun teardown(remember: Boolean = true) {
        assembler?.let { a ->
            lastSessionText =
                when {
                    !remember -> ""
                    a.committedText.isNotEmpty() -> a.committedText
                    else -> a.carryOverText
                }
        }
        generation++
        cancelTimeout?.invoke()
        cancelTimeout = null
        cancelTick?.invoke()
        cancelTick = null
        elapsedSeconds = 0
        phraseFraction = 0f
        speaking = false
        silenceCountdown = 0
        capture?.stop()
        capture = null
        session = null
        assembler = null
        engine?.release()
        engine = null
        level = 0f
        state = VoiceState.Idle
        settle()
    }

    private fun fail(error: SttError) {
        state = VoiceState.Failed(error)
        cancelFailure = scheduler.postDelayed(FAILURE_MS) { clearFailure() }
    }

    private fun apply(edits: List<DictationEdit>) {
        if (edits.isEmpty()) return
        editor.batchEdit {
            for (edit in edits) {
                when (edit) {
                    is DictationEdit.Commit -> editor.commitText(edit.text, 1)
                    is DictationEdit.Composing -> editor.setComposingText(edit.text)
                    DictationEdit.ClearComposing -> {
                        editor.setComposingText("")
                        editor.finishComposingText()
                    }
                    DictationEdit.NewLine -> editor.commitText("\n", 1)
                    DictationEdit.DeleteLastDictation -> deleteLast()
                }
            }
        }
    }

    /** "Delete that": removes the last utterance, if it is still right before the cursor. */
    private fun deleteLast() {
        val text = assembler?.lastDeletedText.orEmpty()
        if (text.isEmpty()) return
        if (editor.textBeforeCursor(text.length)?.toString() == text) {
            editor.deleteSurroundingText(text.length, 0)
        }
    }

    internal companion object {
        /** The ring turns amber from here, and the hint asks for a pause from [HINT_FROM]. */
        const val AMBER_FROM = 0.75f
        const val HINT_FROM = 0.6f

        /** The silence countdown shows in the last this many seconds. */
        const val COUNTDOWN_SECONDS = 3L
        const val TICK_MS = 500L
        const val MILLIS_PER_SECOND = 1000L
        const val NANOS_PER_MILLI = 1_000_000L

        /** [millis] of the phrase out of its [limit]; 0 when the engine has no limit. */
        fun fractionOf(millis: Long, limit: Long): Float =
            if (limit <= 0) 0f else (millis.toFloat() / limit).coerceIn(0f, 1f)

        /** Seconds as the timer shows them: 0:07, 1:05, 12:00. */
        fun formatElapsed(seconds: Int): String =
            "${seconds / SECONDS_PER_MINUTE}:${(seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"

        private const val SECONDS_PER_MINUTE = 60

        /** The text before the cursor the assembler needs for spacing and capitals. */
        const val CONTEXT_CHARS = 64

        /** How long Stop waits for the last result before checking whether the engine is busy. */
        const val FINISH_TIMEOUT_MS = 4_000L

        /** While the engine is still decoding, how often to look again, and the longest wait. */
        const val FINISH_POLL_MS = 1_000L
        const val FINISH_MAX_MS = 15_000L

        /** How long a failure message stays in the strip. */
        const val FAILURE_MS = 3_000L
    }
}
