package dev.keycorrect.feature.keyboard.voice

import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.core.voice.SttEngine

/** A running microphone capture; [stop] ends it (idempotent). */
fun interface CaptureHandle {
    fun stop()
}

/**
 * What voice typing needs from the app (docs/PHASE6.md): the microphone permission, the microphone
 * itself, and the recognizer. The keyboard module holds none of it, so the controller is testable
 * with fakes. Every callback may come from any thread.
 */
interface VoiceEnvironment {
    /** Whether RECORD_AUDIO is granted. */
    fun micPermissionGranted(): Boolean

    /**
     * Asks for RECORD_AUDIO. A keyboard cannot show the permission dialog itself, so the app opens
     * a small transparent activity for it; dictation starts the next time.
     */
    fun requestMicPermission()

    /**
     * The recognizer to use under [settings], or null when there is none. [onLevel] (0 to 1) is
     * called by engines that record the microphone themselves; others get a level from
     * [startCapture]. The caller [SttEngine.release]s it when dictation ends.
     */
    fun openEngine(settings: KeyboardSettings, onLevel: (Float) -> Unit): SttEngine?

    /**
     * Records 16-bit mono PCM at [sampleRate] on its own thread, passing about 30 ms chunks to
     * [onAudio] and their loudness (0 to 1) to [onLevel]; [onFailure] if the microphone breaks.
     * Null when it cannot be opened.
     */
    fun startCapture(
        sampleRate: Int,
        onAudio: (ShortArray, Int) -> Unit,
        onLevel: (Float) -> Unit,
        onFailure: () -> Unit,
    ): CaptureHandle?

    /** Nothing is available: dictation reports "no engine". */
    object None : VoiceEnvironment {
        override fun micPermissionGranted() = false

        override fun requestMicPermission() = Unit

        override fun openEngine(settings: KeyboardSettings, onLevel: (Float) -> Unit): SttEngine? =
            null

        override fun startCapture(
            sampleRate: Int,
            onAudio: (ShortArray, Int) -> Unit,
            onLevel: (Float) -> Unit,
            onFailure: () -> Unit,
        ): CaptureHandle? = null
    }
}
