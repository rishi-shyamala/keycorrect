package dev.keycorrect.feature.keyboard

import android.content.Context
import android.media.AudioManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import dev.keycorrect.core.settings.KeyboardSettings

/** Which system click sound to play for a key. */
enum class FeedbackKind {
    Standard,
    Delete,
    Space,
    Return,
}

/** Key-press haptics and sounds, driven by [KeyboardSettings]. */
class KeyFeedback(context: Context) {
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val audio = context.getSystemService(AudioManager::class.java)
    private val touchAttributes =
        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)

    /** Whether custom strength (amplitude) is supported on this device. */
    val hasAmplitudeControl: Boolean
        get() = vibrator.hasAmplitudeControl()

    fun onKeyPress(view: View?, kind: FeedbackKind, settings: KeyboardSettings) {
        haptic(view, settings)
        sound(kind, settings)
    }

    /**
     * Duration 0 uses the system keyboard-tap haptic (honours the user's system setting); otherwise
     * a one-shot of the configured duration and strength with touch attributes, so the system
     * touch-feedback intensity still applies.
     */
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
        vibrator.vibrate(
            VibrationEffect.createOneShot(settings.hapticDurationMs.toLong(), amplitude),
            touchAttributes,
        )
    }

    fun sound(kind: FeedbackKind, settings: KeyboardSettings) {
        if (!settings.soundEnabled) return
        val effect =
            when (kind) {
                FeedbackKind.Standard -> AudioManager.FX_KEYPRESS_STANDARD
                FeedbackKind.Delete -> AudioManager.FX_KEYPRESS_DELETE
                FeedbackKind.Space -> AudioManager.FX_KEYPRESS_SPACEBAR
                FeedbackKind.Return -> AudioManager.FX_KEYPRESS_RETURN
            }
        audio.playSoundEffect(effect, settings.soundVolume)
    }
}
