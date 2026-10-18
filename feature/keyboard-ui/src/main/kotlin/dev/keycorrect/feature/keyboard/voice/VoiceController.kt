package dev.keycorrect.feature.keyboard.voice

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
     * The mic is on. [partial] is the text heard so far; [finishing] after Stop, while the last
     * result is awaited.
     */
    data class Listening(val partial: String = "", val finishing: Boolean = false) : VoiceState

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
@Suppress("TooManyFunctions") // one small state machine
class VoiceController
internal constructor(
    private val editor: TextEditor,
    private val env: VoiceEnvironment,
    private val settings: () -> KeyboardSettings,
    private val sensitive: () -> Boolean,
    /** Whether the dictation should capitalise at the start of a sentence. */
    private val capitalize: () -> Boolean,
    /** Called before dictation edits the field and after it ended: the typing side settles. */
    private val settle: () -> Unit,
    private val post: (() -> Unit) -> Unit = MainThread::post,
    private val scheduler: Scheduler = MainThread.scheduler,
) {
    var state: VoiceState by mutableStateOf(VoiceState.Idle)
        private set

    /** Loudness of the voice, 0 to 1, for the level meter. */
    var level: Float by mutableFloatStateOf(0f)
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
        val opened =
            env.openEngine(s) { v -> post { if (gen == generation) level = v.coerceIn(0f, 1f) } }
        if (opened == null) {
            fail(SttError.NoEngine)
            return
        }
        engine = opened
        settle()
        assembler =
            DictationAssembler(
                textBefore = editor.textBeforeCursor(CONTEXT_CHARS)?.toString(),
                autoCapitalize = capitalize(),
            )
        val listener = SttListener { r -> post { if (gen == generation) onResult(r) } }
        val started = runCatching {
            opened.startSession(SttConfig(punctuate = s.voiceAutoPunctuation), listener)
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
    }

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
        cancelTimeout = scheduler.postDelayed(FINISH_TIMEOUT_MS) { if (dictating) stopNow() }
        session?.finish()
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
        teardown()
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
                apply(current.onPartial(result.text, result.stablePrefixLength))
                state = (state as VoiceState.Listening).copy(partial = result.text)
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
    private fun teardown() {
        generation++
        cancelTimeout?.invoke()
        cancelTimeout = null
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

    private companion object {
        /** The text before the cursor the assembler needs for spacing and capitals. */
        const val CONTEXT_CHARS = 64

        /** How long Stop waits for the last result. */
        const val FINISH_TIMEOUT_MS = 4_000L

        /** How long a failure message stays in the strip. */
        const val FAILURE_MS = 3_000L
    }
}
