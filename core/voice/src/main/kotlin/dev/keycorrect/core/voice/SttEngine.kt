package dev.keycorrect.core.voice

/**
 * A speech recognizer (docs/PLAN.md §5.1, docs/PHASE6.md): the built-in sherpa-onnx engine with a
 * model from the voice pack, or an installed system recognizer. Engines are created when dictation
 * starts and released when it ends, so a model is only in memory while the mic is on.
 */
interface SttEngine {
    val info: SttEngineInfo

    /**
     * Starts recognising. Results arrive on [listener], on any thread (the caller posts them to the
     * main thread). With [SttEngineInfo.capturesOwnAudio] the engine records the microphone itself
     * and [SttSession.acceptAudio] is never called.
     */
    fun startSession(config: SttConfig, listener: SttListener): SttSession

    /** Frees the model and native resources; the engine is unusable afterwards. */
    fun release() = Unit
}

/**
 * What an engine can do. [sampleRate] is the PCM rate it expects (16 kHz for every built-in model);
 * [streaming] engines emit [SttResult.Partial]s while audio arrives, others only a
 * [SttResult.Final] after [SttSession.finish]. [local] is false only for network engines, which the
 * keyboard itself never contains (D3).
 */
data class SttEngineInfo(
    val id: String,
    val name: String,
    val streaming: Boolean,
    val sampleRate: Int = 16_000,
    val capturesOwnAudio: Boolean = false,
    val local: Boolean = true,
)

/**
 * One dictation's settings. [hotwords] are the user's own words, used for contextual biasing where
 * the engine supports it (sherpa-onnx transducer hotwords, a Whisper prompt).
 */
data class SttConfig(
    val language: String = "en",
    val hotwords: List<String> = emptyList(),
    val punctuate: Boolean = true,
)

/** A running recognition. Every method may be called from any thread. */
interface SttSession {
    /** The next [length] samples of 16-bit mono PCM from [pcm], at the engine's sample rate. */
    fun acceptAudio(pcm: ShortArray, length: Int = pcm.size)

    /** No more audio: emit the [SttResult.Final] and stop. */
    fun finish()

    /** Stop without a result. */
    fun cancel()
}

/** Receives a session's results; see [SttEngine.startSession] for threading. */
fun interface SttListener {
    fun onResult(result: SttResult)
}

/** What a session reports. */
sealed interface SttResult {
    /**
     * The text so far; its first [stablePrefixLength] chars won't change any more, so the keyboard
     * can commit them and keep only the rest as composing text.
     */
    data class Partial(val text: String, val stablePrefixLength: Int = 0) : SttResult

    /** The utterance's text, best first in [alternatives] (n-best, may hold only [text]). */
    data class Final(val text: String, val alternatives: List<String> = listOf(text)) : SttResult

    /** The session ended without text. */
    data class Error(val kind: SttError) : SttResult
}

/** Why a session ended without text. Never carries audio or user text. */
enum class SttError {
    /** RECORD_AUDIO isn't granted. */
    NoPermission,

    /** No engine is available (no voice pack, no system recognizer). */
    NoEngine,

    /** The microphone couldn't be opened or failed. */
    Audio,

    /** The engine failed. */
    Engine,

    /** Nothing was recognised (silence or noise). */
    NoMatch,

    /** The recognizer is in use by something else. */
    Busy,
}
