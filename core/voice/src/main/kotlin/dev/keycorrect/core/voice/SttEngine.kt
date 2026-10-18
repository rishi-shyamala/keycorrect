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
 *
 * The last three tune the built-in engine, which decodes speech phrase by phrase; other engines
 * ignore them. The engine clamps them to what its model can do ([phraseLimitMillis] never goes
 * above what the model survives).
 */
data class SttConfig(
    val language: String = "en",
    val hotwords: List<String> = emptyList(),
    val punctuate: Boolean = true,
    /** The longest phrase decoded in one go; a longer one is cut (and the user asked to pause). */
    val phraseLimitMillis: Long = 7_000,
    /** How long a pause must last before the phrase before it is decoded for good. */
    val pauseMillis: Long = 500,
    /** Live words: [LIVE_AUTO], [LIVE_OFF], or a fixed interval in ms (raised if too fast). */
    val liveUpdateMillis: Long = LIVE_AUTO,
) {
    companion object {
        /** As often as decoding allows: twice the time a decode took, from 0.5 s to 3 s. */
        const val LIVE_AUTO = 0L

        /** No words while speaking; a phrase appears after its pause. */
        const val LIVE_OFF = -1L
    }
}

/** A running recognition. Every method may be called from any thread. */
interface SttSession {
    /** The next [length] samples of 16-bit mono PCM from [pcm], at the engine's sample rate. */
    fun acceptAudio(pcm: ShortArray, length: Int = pcm.size)

    /** No more audio: emit the [SttResult.Final] and stop. */
    fun finish()

    /**
     * True while the engine is still decoding audio it already has. After [finish] the keyboard
     * keeps waiting for the last result while this is true (up to a cap), so a slow device doesn't
     * lose the last words.
     */
    val busy: Boolean
        get() = false

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
     * can commit them and keep only the rest as composing text. [endsSegment] is true when [text]
     * ends with a finished segment (a pause in the speech), so a command that is the whole of the
     * last segment ("delete that") can be told from the start of a longer sentence.
     */
    data class Partial(
        val text: String,
        val stablePrefixLength: Int = 0,
        val endsSegment: Boolean = false,
    ) : SttResult

    /** The utterance's text, best first in [alternatives] (n-best, may hold only [text]). */
    data class Final(val text: String, val alternatives: List<String> = listOf(text)) : SttResult

    /** The session ended without text. */
    data class Error(val kind: SttError) : SttResult

    /**
     * How far the current phrase is, a few times a second while listening, for the keyboard's
     * progress ring. [phraseMillis] is the length of the speech in progress (0 between phrases),
     * [phraseLimitMillis] the longest phrase the engine decodes in one go (it cuts the phrase
     * there, so the user should pause before), [speaking] whether speech is being heard right now.
     * An engine without a phrase limit never sends it (the system recognizer), or sends 0 as the
     * limit. Carries no text; the keyboard ignores it for editing.
     */
    data class Progress(
        val phraseMillis: Long,
        val phraseLimitMillis: Long,
        val speaking: Boolean,
    ) : SttResult
}

/** Why a session ended without text. Never carries audio or user text. */
enum class SttError {
    /** RECORD_AUDIO isn't granted. */
    NoPermission,

    /** No engine is available (the phone has no speech recognizer). */
    NoEngine,

    /** The built-in engine is chosen, but the KeyCorrect voice pack isn't installed. */
    NoVoicePack,

    /** The voice pack is signed with a different key than the keyboard, so it isn't used. */
    VoicePackUntrusted,

    /** The voice pack is installed but incomplete or unusable. */
    VoicePackBroken,

    /** The phone recognizer is chosen, but this field is private (incognito or no learning). */
    PrivateField,

    /** Stop was pressed and the last result never arrived. */
    Timeout,

    /** The microphone couldn't be opened or failed. */
    Audio,

    /** The engine failed. */
    Engine,

    /** Nothing was recognised (silence or noise). */
    NoMatch,

    /** Speech was heard but no words could be made out; shorter phrases may help. */
    Unclear,

    /** The recognizer is in use by something else. */
    Busy,
}
