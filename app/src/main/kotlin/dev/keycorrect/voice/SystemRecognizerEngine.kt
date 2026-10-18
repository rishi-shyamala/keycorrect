package dev.keycorrect.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.keycorrect.core.voice.SttConfig
import dev.keycorrect.core.voice.SttEngine
import dev.keycorrect.core.voice.SttEngineInfo
import dev.keycorrect.core.voice.SttError
import dev.keycorrect.core.voice.SttListener
import dev.keycorrect.core.voice.SttResult
import dev.keycorrect.core.voice.SttSession
import java.util.Locale

/**
 * Pure mapping from `SpeechRecognizer`'s callbacks to [SttResult]s (unit-tested without Android).
 */
internal object RecognizerMapping {
    /**
     * A partial result; the recognizer's partials are not stable, so nothing is committed early.
     */
    fun partial(texts: List<String>?): SttResult.Partial? =
        texts?.firstOrNull { it.isNotBlank() }?.let { SttResult.Partial(it.trim(), 0) }

    /** The final result with its alternatives (best first, blanks and repeats dropped). */
    fun final(texts: List<String>?): SttResult {
        val all = texts.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return if (all.isEmpty()) SttResult.Error(SttError.NoMatch)
        else SttResult.Final(all.first(), all)
    }

    /** A `SpeechRecognizer.ERROR_*` code. */
    fun error(code: Int): SttError =
        when (code) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SttError.NoMatch
            SpeechRecognizer.ERROR_AUDIO -> SttError.Audio
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SttError.NoPermission
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SttError.Busy
            else -> SttError.Engine
        }

    /** `onRmsChanged` is in dB, about -2 (silence) to 10 (loud). */
    fun level(rmsDb: Float): Float =
        ((rmsDb + RMS_FLOOR) / (RMS_CEILING + RMS_FLOOR)).coerceIn(0f, 1f)

    private const val RMS_FLOOR = 2f
    private const val RMS_CEILING = 10f
}

/**
 * The installed system recognizer (Google, FUTO Voice Input, ...) as an [SttEngine]: it records the
 * microphone itself ([SttEngineInfo.capturesOwnAudio]) and ends its session with its first final
 * result. On-device only when the system offers it (API 31+), else the default service, which may
 * be an online one the user chose. Create and use it on the main thread.
 */
class SystemRecognizerEngine(
    private val context: Context,
    private val preferOnDevice: Boolean = true,
    private val onLevel: (Float) -> Unit = {},
) : SttEngine {
    override val info =
        SttEngineInfo(
            id = "system-recognizer",
            name = "System recognizer",
            streaming = true,
            capturesOwnAudio = true,
        )

    private var recognizer: SpeechRecognizer? = null

    override fun startSession(config: SttConfig, listener: SttListener): SttSession {
        val created = checkNotNull(create()) { "no recognizer" }
        recognizer = created
        created.setRecognitionListener(Bridge(listener, onLevel))
        created.startListening(intent(config))
        return Session(created)
    }

    override fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun create(): SpeechRecognizer? =
        if (
            preferOnDevice &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            null
        }

    private fun intent(config: SttConfig) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                Locale.forLanguageTag(config.language).toLanguageTag(),
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (config.punctuate && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(
                    RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                    RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY,
                )
            }
        }

    private class Session(private val recognizer: SpeechRecognizer) : SttSession {
        override fun acceptAudio(pcm: ShortArray, length: Int) = Unit

        override fun finish() = recognizer.stopListening()

        override fun cancel() = recognizer.cancel()
    }

    private class Bridge(private val listener: SttListener, private val onLevel: (Float) -> Unit) :
        RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) {
            RecognizerMapping.partial(texts(partialResults))?.let(listener::onResult)
        }

        override fun onResults(results: Bundle?) =
            listener.onResult(RecognizerMapping.final(texts(results)))

        override fun onError(error: Int) =
            listener.onResult(SttResult.Error(RecognizerMapping.error(error)))

        override fun onRmsChanged(rmsdB: Float) = onLevel(RecognizerMapping.level(rmsdB))

        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        private fun texts(bundle: Bundle?): List<String>? =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
    }

    private companion object {
        const val MAX_RESULTS = 5
    }
}
