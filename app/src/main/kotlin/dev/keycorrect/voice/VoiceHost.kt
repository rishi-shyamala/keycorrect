package dev.keycorrect.voice

import android.content.Context
import android.speech.SpeechRecognizer
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.core.settings.VoiceEngineChoice
import dev.keycorrect.core.voice.SttEngine
import dev.keycorrect.feature.keyboard.voice.CaptureHandle
import dev.keycorrect.feature.keyboard.voice.VoiceEnvironment
import dev.keycorrect.feature.voicesherpa.SherpaMoonshineEngine
import dev.keycorrect.feature.voicesherpa.VoicePackLocator
import dev.keycorrect.feature.voicesherpa.VoicePackResult
import kotlin.concurrent.thread

/**
 * The built-in engine, if there is one: the on-device model from the voice pack (docs/PHASE6.md
 * P6-1).
 */
fun interface BuiltInEngineSource {
    /** A new engine, or null when the pack isn't installed or fails to load. May take a moment. */
    fun open(): SttEngine?
}

/**
 * The voice pack's Moonshine engine ([VoicePackLocator] checks the pack's signature first). The
 * pack's native libraries (~27 MB) are loaded once per process on a background thread when this is
 * created, so the first dictation doesn't load them on the main thread; without the pack that check
 * returns at once.
 */
class VoicePackEngineSource(context: Context) : BuiltInEngineSource {
    private val locator = VoicePackLocator(context)

    init {
        thread(isDaemon = true, name = "keycorrect-voicepack") {
            // Only warms up: open() locates again (cheap once loaded) and reports problems.
            runCatching { locator.locate() }
        }
    }

    override fun open(): SttEngine? =
        when (val found = locator.locate()) {
            is VoicePackResult.Available -> SherpaMoonshineEngine(found.pack)
            is VoicePackResult.Unavailable -> null
        }
}

/**
 * Voice typing for the keyboard service: the permission, the microphone and the choice of
 * recognizer. [builtIn] (the voice pack) is preferred under [VoiceEngineChoice.Automatic];
 * otherwise, and when it is unavailable, the installed system recognizer is used; with neither the
 * dictation reports "no engine". A new engine is opened for every dictation and released by the
 * controller when it ends.
 */
class VoiceHost(
    private val context: Context,
    private val capture: AudioCapture = AudioCapture(context),
    private val builtIn: BuiltInEngineSource? = VoicePackEngineSource(context),
    private val requestPermission: () -> Unit = { MicPermissionActivity.launch(context) },
) : VoiceEnvironment {
    override fun micPermissionGranted() =
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun requestMicPermission() = requestPermission()

    override fun openEngine(settings: KeyboardSettings, onLevel: (Float) -> Unit): SttEngine? {
        if (settings.voiceEngine == VoiceEngineChoice.Automatic) {
            builtIn?.open()?.let {
                return it
            }
        }
        return if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SystemRecognizerEngine(context, onLevel = onLevel)
        } else {
            null
        }
    }

    override fun startCapture(
        sampleRate: Int,
        onAudio: (ShortArray, Int) -> Unit,
        onLevel: (Float) -> Unit,
        onFailure: () -> Unit,
    ): CaptureHandle? = capture.start(sampleRate, onAudio, onLevel, onFailure)
}
