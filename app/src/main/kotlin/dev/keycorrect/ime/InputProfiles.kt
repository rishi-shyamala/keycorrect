package dev.keycorrect.ime

import android.content.res.Configuration
import dev.keycorrect.core.engineapi.FieldClass
import dev.keycorrect.core.learning.DeviceClass
import dev.keycorrect.core.learning.InputProfile

/**
 * What kind of screen and input the keyboard is on, from a [Configuration]. The keyboard controller
 * turns the same three flags into a [DeviceClass] ([DeviceProfile.deviceClass] must stay in step
 * with `KeyboardController.updateDevice`).
 */
data class DeviceProfile(
    val tablet: Boolean,
    val landscape: Boolean,
    val hardwareKeyboard: Boolean,
) {
    /** A physical keyboard wins, then tablet, else phone. */
    val deviceClass: DeviceClass
        get() =
            when {
                hardwareKeyboard -> DeviceClass.HardwareKeyboard
                tablet -> DeviceClass.Tablet
                else -> DeviceClass.Phone
            }

    companion object {
        const val TABLET_MIN_SMALLEST_WIDTH_DP = 600

        fun from(
            smallestScreenWidthDp: Int,
            orientation: Int,
            keyboard: Int,
            hardKeyboardHidden: Int,
        ) =
            DeviceProfile(
                tablet = smallestScreenWidthDp >= TABLET_MIN_SMALLEST_WIDTH_DP,
                landscape = orientation == Configuration.ORIENTATION_LANDSCAPE,
                hardwareKeyboard =
                    keyboard != Configuration.KEYBOARD_NOKEYS &&
                        hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO,
            )

        fun from(config: Configuration) =
            from(
                config.smallestScreenWidthDp,
                config.orientation,
                config.keyboard,
                config.hardKeyboardHidden,
            )
    }
}

/**
 * The key under which touch statistics are kept, as the decoder needs it. The layout key part
 * mirrors `KeyboardController.updateLayoutKey` (layer id, number-row marker, key height), and the
 * rest is [InputProfile.touchKey] itself, so the format has one definition. The learning events
 * carry the controller's own value; if the controller's layout key ever changes, change [layoutKey]
 * with it (LearningWiringTest pins the current format).
 */
internal object InputProfiles {
    fun layoutKey(layerId: String, numberRow: Boolean, keyHeightDp: Int): String =
        "$layerId${if (numberRow) "+nr" else ""}@${keyHeightDp}dp"

    fun touchKey(
        device: DeviceProfile,
        layerId: String,
        numberRow: Boolean,
        keyHeightDp: Int,
    ): String =
        InputProfile(
                device.deviceClass,
                device.landscape,
                layoutKey(layerId, numberRow, keyHeightDp),
                FieldClass.Text,
            )
            .touchKey
}
