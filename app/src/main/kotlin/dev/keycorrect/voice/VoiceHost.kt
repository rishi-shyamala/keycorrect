package dev.keycorrect.voice

import android.content.Context
import android.speech.SpeechRecognizer
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.core.settings.VoiceEngineChoice
import dev.keycorrect.core.voice.SttEngine
import dev.keycorrect.core.voice.SttError
import dev.keycorrect.feature.keyboard.voice.CaptureHandle
import dev.keycorrect.feature.keyboard.voice.EngineOpening
import dev.keycorrect.feature.keyboard.voice.VoiceEnvironment
import dev.keycorrect.feature.voicesherpa.SherpaMoonshineEngine
import dev.keycorrect.feature.voicesherpa.VoicePackLocator
import dev.keycorrect.feature.voicesherpa.VoicePackProblem
import dev.keycorrect.feature.voicesherpa.VoicePackResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** What opening the built-in engine gave. */
sealed interface BuiltInOpening {
    data class Ready(val engine: SttEngine) : BuiltInOpening

    data class Unavailable(val problem: VoicePackProblem) : BuiltInOpening
}

/** The built-in engine: the on-device model from the voice pack (docs/PHASE6.md P6-1). */
interface BuiltInEngineSource {
    /** A new engine, or why there is none. May take a moment. */
    fun open(): BuiltInOpening

    /**
     * Starts loading the pack's native libraries in the background, so the first dictation doesn't
     * (once per process; a no-op afterwards).
     */
    fun warmUp() = Unit

    /**
     * Whether libraries from an earlier pack install are loaded that the installed pack no longer
     * provides (removed, or replaced with different ones). Reads files: not on the main thread.
     */
    fun librariesStale(): Boolean = false
}

/**
 * The voice pack's Moonshine engine ([VoicePackLocator] checks the pack's signature first). The
 * pack's native libraries (~27 MB) are loaded once per process on a background thread by [warmUp];
 * [open] is cheap once the pack has been verified (the locator remembers the verified install).
 */
class VoicePackEngineSource(context: Context) : BuiltInEngineSource {
    private val locator = VoicePackLocator(context)
    private val warmed = AtomicBoolean(false)

    override fun warmUp() {
        if (!warmed.compareAndSet(false, true)) return
        thread(isDaemon = true, name = "keycorrect-voicepack") {
            // Only warms up: open() reports problems.
            runCatching { locator.locate() }
        }
    }

    override fun librariesStale() = locator.loadedLibrariesStale()

    override fun open(): BuiltInOpening =
        when (val found = locator.locate()) {
            is VoicePackResult.Available -> BuiltInOpening.Ready(SherpaMoonshineEngine(found.pack))
            is VoicePackResult.Unavailable -> BuiltInOpening.Unavailable(found.problem)
        }
}

/** The phone's own speech recognizer. */
fun interface PhoneEngineSource {
    /** A new engine, or null when the phone has no recognizer. */
    fun open(onLevel: (Float) -> Unit): SttEngine?
}

/**
 * Which engine a dictation uses, by the setting alone (docs/PHASE6.md P6-5). Nothing falls back to
 * another engine:
 * - [VoiceEngineChoice.BuiltIn]: the voice pack, or the reason it can't be used (not installed, not
 *   signed by our key, damaged).
 * - [VoiceEngineChoice.PhoneRecognizer]: the phone's recognizer, which may send audio to its
 *   provider's servers; never in a [privateField] (incognito, or a field asking for no learning).
 */
internal fun chooseEngine(
    choice: VoiceEngineChoice,
    privateField: Boolean,
    builtIn: BuiltInEngineSource?,
    phone: PhoneEngineSource,
    onLevel: (Float) -> Unit,
): EngineOpening =
    when (choice) {
        VoiceEngineChoice.BuiltIn ->
            when (val opened = builtIn?.open()) {
                is BuiltInOpening.Ready -> EngineOpening.Ready(opened.engine)
                is BuiltInOpening.Unavailable -> EngineOpening.Unavailable(opened.problem.toError())
                null -> EngineOpening.Unavailable(SttError.NoVoicePack)
            }
        VoiceEngineChoice.PhoneRecognizer ->
            if (privateField) {
                EngineOpening.Unavailable(SttError.PrivateField)
            } else {
                phone.open(onLevel)?.let { EngineOpening.Ready(it) }
                    ?: EngineOpening.Unavailable(SttError.NoEngine)
            }
    }

internal fun VoicePackProblem.toError(): SttError =
    when (this) {
        VoicePackProblem.NotInstalled -> SttError.NoVoicePack
        VoicePackProblem.SignatureMismatch -> SttError.VoicePackUntrusted
        VoicePackProblem.LibrariesMissing -> SttError.VoicePackLibrariesMissing
        VoicePackProblem.LibraryLoadFailed -> SttError.VoicePackLoadFailed
        VoicePackProblem.ModelsMissing -> SttError.VoicePackModelsMissing
        VoicePackProblem.ChangedDuringLoad -> SttError.VoicePackChanged
        VoicePackProblem.NeedsRestart -> SttError.VoicePackNeedsRestart
    }

/** Whether the voice pack's libraries are worth loading in the background: voice on, built-in. */
internal fun warmUpWanted(settings: KeyboardSettings) =
    settings.voiceEnabled && settings.voiceEngine == VoiceEngineChoice.BuiltIn

/**
 * Voice typing for the keyboard service: the permission, the microphone and the choice of
 * recognizer ([chooseEngine]: the setting decides, with no fallback). A new engine is opened for
 * every dictation and released by the controller when it ends.
 */
class VoiceHost(
    private val context: Context,
    private val capture: AudioCapture = AudioCapture(context),
    private val builtIn: BuiltInEngineSource? = VoicePackEngineSource(context),
    private val requestPermission: () -> Unit = { MicPermissionActivity.launch(context) },
    /** Runs [block] off the main thread (the voice pack's locate() reads and hashes files). */
    private val background: (() -> Unit) -> Unit = { block ->
        thread(isDaemon = true, name = "keycorrect-voicepack-open", block = block)
    },
) : VoiceEnvironment {
    /** Set by the keyboard service: restarts its process once the window is hidden. */
    @Volatile var restarter: (() -> Unit)? = null

    override fun restartKeyboard() {
        restarter?.invoke()
    }

    /** See [BuiltInEngineSource.librariesStale]; off the main thread. */
    fun voicePackLibrariesStale(): Boolean = builtIn?.librariesStale() == true

    private val phone = PhoneEngineSource { onLevel ->
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SystemRecognizerEngine(context, onLevel = onLevel)
        } else {
            null
        }
    }

    /**
     * Loads the voice pack's libraries in the background, but only when voice typing is on and uses
     * the built-in engine; otherwise nothing is loaded.
     */
    fun applySettings(settings: KeyboardSettings) {
        if (warmUpWanted(settings)) builtIn?.warmUp()
    }

    override fun micPermissionGranted() =
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun requestMicPermission() = requestPermission()

    override fun openEngine(
        settings: KeyboardSettings,
        privateField: Boolean,
        onLevel: (Float) -> Unit,
    ): EngineOpening = chooseEngine(settings.voiceEngine, privateField, builtIn, phone, onLevel)

    /**
     * The built-in engine is opened on a background thread: locating the pack may hash and load its
     * native libraries. The phone recognizer must be created on the main thread, so it isn't.
     */
    override fun openEngineAsync(
        settings: KeyboardSettings,
        privateField: Boolean,
        onLevel: (Float) -> Unit,
        onOpened: (EngineOpening) -> Unit,
    ) {
        if (settings.voiceEngine == VoiceEngineChoice.BuiltIn) {
            background {
                onOpened(
                    runCatching { openEngine(settings, privateField, onLevel) }
                        .getOrDefault(EngineOpening.Unavailable(SttError.Engine))
                )
            }
        } else {
            onOpened(openEngine(settings, privateField, onLevel))
        }
    }

    override fun startCapture(
        sampleRate: Int,
        onAudio: (ShortArray, Int) -> Unit,
        onLevel: (Float) -> Unit,
        onFailure: () -> Unit,
    ): CaptureHandle? = capture.start(sampleRate, onAudio, onLevel, onFailure)
}
