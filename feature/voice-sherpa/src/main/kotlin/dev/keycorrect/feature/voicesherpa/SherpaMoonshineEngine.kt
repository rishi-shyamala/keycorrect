package dev.keycorrect.feature.voicesherpa

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dev.keycorrect.core.voice.SttConfig
import dev.keycorrect.core.voice.SttEngine
import dev.keycorrect.core.voice.SttEngineInfo
import dev.keycorrect.core.voice.SttListener
import dev.keycorrect.core.voice.SttSession

/**
 * The built-in speech engine: Silero VAD cuts the audio into speech segments, and the Moonshine v2
 * offline model recognises each one on a background thread. Not a streaming model: while a phrase
 * is spoken it is decoded again every second or so for the live words; a finished segment is
 * committed for good, and the final text follows [SttSession.finish]. The models are read in place
 * from the voice pack's assets ([pack]) and loaded when a session starts, and freed when it ends.
 *
 * Moonshine takes no hotwords; [SttConfig.hotwords] are ignored here.
 */
class SherpaMoonshineEngine internal constructor(private val factory: ComponentsFactory) :
    SttEngine {
    constructor(pack: VoicePack) : this(SherpaComponentsFactory(pack.assets))

    override val info =
        SttEngineInfo(
            id = "sherpa-moonshine-v2-tiny-en",
            name = "Moonshine tiny (English)",
            streaming = false,
        )

    private var active: SttSession? = null

    @Synchronized
    override fun startSession(config: SttConfig, listener: SttListener): SttSession {
        active?.cancel()
        val tuning = SessionTuning.from(config)
        return SegmentedDictation(
                listener,
                create = { factory.create(tuning) },
                phraseLimitMillis = tuning.phraseLimitMillis,
                liveUpdateMillis = tuning.liveUpdateMillis,
            )
            .also { active = it }
    }

    @Synchronized
    override fun release() {
        active?.cancel()
        active = null
    }
}

/** Creates the VAD and the recognizer for one session (on its worker thread). */
internal fun interface ComponentsFactory {
    fun create(tuning: SessionTuning): SpeechComponents
}

/** The per-dictation settings of [SttConfig], clamped to what Moonshine and the VAD can do. */
internal data class SessionTuning(
    val phraseLimitMillis: Long,
    val pauseSeconds: Float,
    val liveUpdateMillis: Long,
) {
    companion object {
        fun from(config: SttConfig) =
            SessionTuning(
                phraseLimitMillis =
                    config.phraseLimitMillis.coerceIn(
                        SherpaTuning.MIN_PHRASE_MILLIS,
                        SherpaTuning.MAX_PHRASE_MILLIS,
                    ),
                pauseSeconds =
                    (config.pauseMillis / MILLIS).coerceIn(
                        SherpaTuning.MIN_PAUSE_SECONDS,
                        SherpaTuning.MAX_PAUSE_SECONDS,
                    ),
                liveUpdateMillis =
                    when {
                        config.liveUpdateMillis < 0 -> SttConfig.LIVE_OFF
                        config.liveUpdateMillis == SttConfig.LIVE_AUTO -> SttConfig.LIVE_AUTO
                        else ->
                            config.liveUpdateMillis.coerceIn(
                                LiveTuning.MIN_INTERVAL_MS,
                                LIVE_MAX_FIXED,
                            )
                    },
            )

        private const val MILLIS = 1000f
        private const val LIVE_MAX_FIXED = 2000L
    }
}

/**
 * Segments are cut at a pause (0.5 s by default) and at the phrase limit (7 s by default).
 *
 * Moonshine v2 tiny fails outright on audio longer than about 9.25 s (the decoder's cross-attention
 * shapes don't broadcast; sherpa-onnx then returns an empty result): measured in docs/PHASE6.md.
 * The default limit is 7 s, about 75% of that, and the most the user can set is
 * [MAX_PHRASE_MILLIS]. The VAD's own maximum is only a hint (it cuts at a quiet moment and waits
 * for one: segments of 12 s and more were seen with it set to 3 s), so [SegmentedDictation] cuts a
 * phrase at the limit itself.
 */
internal object SherpaTuning {
    const val SAMPLE_RATE = 16_000
    const val NUM_THREADS = 2
    const val VAD_THRESHOLD = 0.5f
    const val MIN_PAUSE_SECONDS = 0.3f
    const val MAX_PAUSE_SECONDS = 1.5f
    const val MIN_SPEECH_SECONDS = 0.25f
    const val MIN_PHRASE_MILLIS = 3_000L

    /** The most the user can ask for: 92% of the 9.25 s where Moonshine fails. */
    const val MAX_PHRASE_MILLIS = 8_500L
    const val DEFAULT_PHRASE_MILLIS = 7_000L
    const val VAD_WINDOW = 512
}

internal class SherpaComponentsFactory(private val assets: AssetManager) : ComponentsFactory {
    override fun create(tuning: SessionTuning): SpeechComponents {
        val recognizer = SherpaRecognizer(createRecognizer())
        return runCatching { SpeechComponents(SherpaDetector(createVad(tuning)), recognizer) }
            .getOrElse {
                recognizer.release()
                throw it
            }
    }

    private fun createRecognizer() =
        OfflineRecognizer(
            assetManager = assets,
            config =
                OfflineRecognizerConfig(
                    modelConfig =
                        OfflineModelConfig(
                            moonshine =
                                OfflineMoonshineModelConfig(
                                    encoder = VoicePackLayout.MOONSHINE_ENCODER,
                                    mergedDecoder = VoicePackLayout.MOONSHINE_DECODER,
                                ),
                            tokens = VoicePackLayout.MOONSHINE_TOKENS,
                            numThreads = SherpaTuning.NUM_THREADS,
                        )
                ),
        )

    private fun createVad(tuning: SessionTuning) =
        Vad(
            assetManager = assets,
            config =
                VadModelConfig(
                    sileroVadModelConfig =
                        SileroVadModelConfig(
                            model = VoicePackLayout.SILERO_VAD,
                            threshold = SherpaTuning.VAD_THRESHOLD,
                            minSilenceDuration = tuning.pauseSeconds,
                            minSpeechDuration = SherpaTuning.MIN_SPEECH_SECONDS,
                            windowSize = SherpaTuning.VAD_WINDOW,
                            maxSpeechDuration = tuning.phraseLimitMillis / MILLIS_PER_SECOND,
                        ),
                    sampleRate = SherpaTuning.SAMPLE_RATE,
                    numThreads = 1,
                ),
        )
}

private class SherpaDetector(private val vad: Vad) : SpeechDetector {
    override fun accept(samples: FloatArray) = vad.acceptWaveform(samples)

    override fun flush() = vad.flush()

    override fun reset() = vad.reset()

    override val speechDetected: Boolean
        get() = vad.isSpeechDetected()

    override fun nextSegment(): FloatArray? {
        if (vad.empty()) return null
        val segment = vad.front().samples
        vad.pop()
        return segment
    }

    override fun release() = vad.release()
}

private class SherpaRecognizer(private val recognizer: OfflineRecognizer) : SegmentRecognizer {
    override fun recognize(segment: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(segment, SherpaTuning.SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    override fun release() = recognizer.release()
}

private const val MILLIS_PER_SECOND = 1000f
