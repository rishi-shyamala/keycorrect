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
 * offline model recognises each one on a background thread. Not a streaming model, so the partial
 * text grows one finished segment at a time; the final text follows [SttSession.finish]. The models
 * are read in place from the voice pack's assets ([pack]) and loaded when a session starts, and
 * freed when it ends.
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
        return SegmentedDictation(listener, create = factory::create).also { active = it }
    }

    @Synchronized
    override fun release() {
        active?.cancel()
        active = null
    }
}

/** Creates the VAD and the recognizer for one session (on its worker thread). */
internal fun interface ComponentsFactory {
    fun create(): SpeechComponents
}

/** Segments are cut at 0.5 s of silence, and at 15 s of speech so long dictation is split. */
internal object SherpaTuning {
    const val SAMPLE_RATE = 16_000
    const val NUM_THREADS = 2
    const val VAD_THRESHOLD = 0.5f
    const val MIN_SILENCE_SECONDS = 0.5f
    const val MIN_SPEECH_SECONDS = 0.25f
    const val MAX_SEGMENT_SECONDS = 15f
    const val VAD_WINDOW = 512
}

internal class SherpaComponentsFactory(private val assets: AssetManager) : ComponentsFactory {
    override fun create(): SpeechComponents {
        val recognizer = SherpaRecognizer(createRecognizer())
        return runCatching { SpeechComponents(SherpaDetector(createVad()), recognizer) }
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

    private fun createVad() =
        Vad(
            assetManager = assets,
            config =
                VadModelConfig(
                    sileroVadModelConfig =
                        SileroVadModelConfig(
                            model = VoicePackLayout.SILERO_VAD,
                            threshold = SherpaTuning.VAD_THRESHOLD,
                            minSilenceDuration = SherpaTuning.MIN_SILENCE_SECONDS,
                            minSpeechDuration = SherpaTuning.MIN_SPEECH_SECONDS,
                            windowSize = SherpaTuning.VAD_WINDOW,
                            maxSpeechDuration = SherpaTuning.MAX_SEGMENT_SECONDS,
                        ),
                    sampleRate = SherpaTuning.SAMPLE_RATE,
                    numThreads = 1,
                ),
        )
}

private class SherpaDetector(private val vad: Vad) : SpeechDetector {
    override fun accept(samples: FloatArray) = vad.acceptWaveform(samples)

    override fun flush() = vad.flush()

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
