package dev.keycorrect.feature.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import dev.keycorrect.core.settings.KeyboardSettings

/**
 * What is being given feedback for. The first four are key presses (which system click sound to
 * play); [Autocorrected] and [UnknownWord] are word events with a vibration of their own and no
 * sound.
 */
enum class FeedbackKind {
    Standard,
    Delete,
    Space,
    Return,

    /** A word was auto-corrected when a separator was typed. */
    Autocorrected,

    /** A finished word is in no dictionary and was not corrected. */
    UnknownWord,
}

/** Receives the word events the keyboard gives feedback for (see [KeyboardController]). */
fun interface WordFeedback {
    fun onWordFeedback(kind: FeedbackKind)

    companion object {
        val None = WordFeedback {}
    }
}

/**
 * Whether the user wants a vibration for the word event [kind] under [settings]: haptics must be
 * on, and for [FeedbackKind.Autocorrected] / [FeedbackKind.UnknownWord] so must their own switch.
 * Key presses are always allowed (only [KeyboardSettings.hapticsEnabled] applies).
 */
fun hapticWanted(kind: FeedbackKind, settings: KeyboardSettings): Boolean =
    settings.hapticsEnabled &&
        when (kind) {
            FeedbackKind.Autocorrected -> settings.hapticOnAutocorrect
            FeedbackKind.UnknownWord -> settings.hapticOnUnknownWord
            else -> true
        }

/** Number of short pulses in the autocorrect pattern. */
internal const val AUTOCORRECT_PULSES = 2

/** Silence between the pulses of the autocorrect pattern, ms. */
internal const val AUTOCORRECT_GAP_MS = 43L

/** Number of short pulses in the unknown-word buzz (the autocorrect pattern has two). */
internal const val UNKNOWN_PULSES = 3

/** Silence between the pulses of the unknown-word buzz, ms. */
internal const val UNKNOWN_GAP_MS = 48L

/**
 * Waveform timings (off, on, off, on, ...) of [pulses] pulses of [pulseMs] each, [gapMs] of silence
 * apart. The pulses are plain on/off waveform steps, not primitive ticks, because only a waveform
 * has a settable length.
 */
internal fun pulseTimings(pulses: Int, pulseMs: Int, gapMs: Long): LongArray =
    LongArray(2 * pulses) { i ->
        when {
            i == 0 -> 0L
            i % 2 == 1 -> pulseMs.toLong()
            else -> gapMs
        }
    }

/**
 * The autocorrect double pulse: two pulses of [pulseMs] ([KeyboardSettings.autocorrectPulseMs]).
 */
internal fun autocorrectTimings(pulseMs: Int): LongArray =
    pulseTimings(AUTOCORRECT_PULSES, pulseMs, AUTOCORRECT_GAP_MS)

/**
 * The unknown-word triple buzz: three pulses of [pulseMs] ([KeyboardSettings.unknownWordPulseMs]).
 */
internal fun unknownWordTimings(pulseMs: Int): LongArray =
    pulseTimings(UNKNOWN_PULSES, pulseMs, UNKNOWN_GAP_MS)

/** Key-press haptics and sounds, driven by [KeyboardSettings]. */
class KeyFeedback(context: Context) {
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val audio = context.getSystemService(AudioManager::class.java)
    private val touchAttributes =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
        } else {
            null
        }

    private val sonificationAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    /** Whether custom strength (amplitude) is supported on this device. */
    val hasAmplitudeControl: Boolean
        get() = vibrator.hasAmplitudeControl()

    fun onKeyPress(view: View?, kind: FeedbackKind, settings: KeyboardSettings) {
        haptic(view, settings)
        sound(kind, settings)
    }

    /**
     * The vibration for a word event ([FeedbackKind.Autocorrected] or [FeedbackKind.UnknownWord]),
     * if the settings want it (see [hapticWanted]). Other kinds do nothing here.
     */
    fun onWordEvent(kind: FeedbackKind, settings: KeyboardSettings) {
        if (!hapticWanted(kind, settings)) return
        vibrateWordEvent(kind, settings)
    }

    /**
     * Plays [kind] as a test from the settings screen. Word events ignore their own switch (so they
     * can be tried before being turned on) but not [KeyboardSettings.hapticsEnabled]; key kinds
     * play the key haptic and click.
     */
    fun preview(kind: FeedbackKind, settings: KeyboardSettings, view: View?) {
        when (kind) {
            FeedbackKind.Autocorrected,
            FeedbackKind.UnknownWord ->
                if (settings.hapticsEnabled) vibrateWordEvent(kind, settings)
            else -> onKeyPress(view, kind, settings)
        }
    }

    private fun vibrateWordEvent(kind: FeedbackKind, settings: KeyboardSettings) {
        val effect =
            when (kind) {
                FeedbackKind.Autocorrected -> autocorrectEffect(settings)
                FeedbackKind.UnknownWord -> unknownWordEffect(settings)
                else -> return
            }
        vibrate(effect)
    }

    /** A double pulse: two pulses of [KeyboardSettings.autocorrectPulseMs]. */
    private fun autocorrectEffect(settings: KeyboardSettings): VibrationEffect =
        pulseEffect(autocorrectTimings(settings.autocorrectPulseMs), settings)

    /** A triple buzz: three pulses of [KeyboardSettings.unknownWordPulseMs]. */
    private fun unknownWordEffect(settings: KeyboardSettings): VibrationEffect =
        pulseEffect(unknownWordTimings(settings.unknownWordPulseMs), settings)

    /** A waveform of [timings] (off first, then alternating on and off) at the key strength. */
    private fun pulseEffect(timings: LongArray, settings: KeyboardSettings): VibrationEffect {
        val on = if (hasAmplitudeControl) amplitude(settings, full = 255) else FALLBACK_ON
        return VibrationEffect.createWaveform(
            timings,
            IntArray(timings.size) { if (it % 2 == 1) on else 0 },
            NO_REPEAT,
        )
    }

    /** Amplitude 1..255: [full] by default, [full] times the strength setting when one is set. */
    private fun amplitude(settings: KeyboardSettings, full: Int): Int =
        if (settings.hapticStrength == 0) full
        else (full * settings.hapticStrength / MAX_AMPLITUDE).coerceIn(MIN_AMPLITUDE, MAX_AMPLITUDE)

    /**
     * Duration 0 uses the system keyboard-tap haptic (honours the user's system setting); otherwise
     * a one-shot of the configured duration and strength with touch attributes (sonification audio
     * attributes on API 31-32), so the system touch-feedback intensity still applies.
     */
    // VIBRATE is declared in the app manifest.
    @SuppressLint("MissingPermission")
    fun haptic(view: View?, settings: KeyboardSettings) {
        if (!settings.hapticsEnabled) return
        if (settings.hapticDurationMs == 0) {
            view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            return
        }
        val amplitude =
            if (settings.hapticStrength == 0 || !hasAmplitudeControl) {
                VibrationEffect.DEFAULT_AMPLITUDE
            } else {
                settings.hapticStrength
            }
        vibrate(VibrationEffect.createOneShot(settings.hapticDurationMs.toLong(), amplitude))
    }

    // VIBRATE is declared in the app manifest.
    @SuppressLint("MissingPermission")
    private fun vibrate(effect: VibrationEffect) {
        if (touchAttributes != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, touchAttributes)
        } else {
            vibrateWithSonificationAttributes(effect)
        }
    }

    // API 31-32 have no VibrationAttributes. A plain vibrate(effect) would carry no touch
    // semantics, so the system touch-feedback setting would not apply to it. The deprecated
    // AudioAttributes overload maps USAGE_ASSISTANCE_SONIFICATION to the touch vibration usage
    // there; it is the only way to express that before API 33.
    // VIBRATE is declared in the app manifest.
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun vibrateWithSonificationAttributes(effect: VibrationEffect) {
        vibrator.vibrate(effect, sonificationAttributes)
    }

    fun sound(kind: FeedbackKind, settings: KeyboardSettings) {
        if (!settings.soundEnabled) return
        val effect =
            when (kind) {
                FeedbackKind.Standard -> AudioManager.FX_KEYPRESS_STANDARD
                FeedbackKind.Delete -> AudioManager.FX_KEYPRESS_DELETE
                FeedbackKind.Space -> AudioManager.FX_KEYPRESS_SPACEBAR
                FeedbackKind.Return -> AudioManager.FX_KEYPRESS_RETURN
                FeedbackKind.Autocorrected,
                FeedbackKind.UnknownWord -> return // vibration only
            }
        audio.playSoundEffect(effect, settings.soundVolume)
    }

    private companion object {
        const val MIN_AMPLITUDE = 20
        const val MAX_AMPLITUDE = 255
        const val NO_REPEAT = -1

        /** The "on" level when the motor has no amplitude control: any nonzero value is full. */
        const val FALLBACK_ON = VibrationEffect.DEFAULT_AMPLITUDE
    }
}
