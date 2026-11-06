package dev.keycorrect.ime

import android.content.res.Configuration
import dev.keycorrect.core.settings.SizeProfile

/**
 * What kind of screen and input the keyboard is on, from a [Configuration]. The keyboard controller
 * turns the three flags into its input profile (and the decoder's touch key, see
 * `KeyboardController.currentTouchKey`).
 */
data class DeviceProfile(
    val tablet: Boolean,
    val landscape: Boolean,
    val hardwareKeyboard: Boolean,
) {
    /** Which per-profile keyboard sizes apply here (docs/PHASE8.md, P8-2). */
    val sizeProfile: SizeProfile
        get() = SizeProfile(tablet, landscape)

    companion object {
        const val TABLET_MIN_SMALLEST_WIDTH_DP = SizeProfile.TABLET_MIN_SMALLEST_WIDTH_DP

        fun from(
            smallestScreenWidthDp: Int,
            orientation: Int,
            keyboard: Int,
            hardKeyboardHidden: Int,
        ) =
            DeviceProfile(
                tablet = smallestScreenWidthDp >= TABLET_MIN_SMALLEST_WIDTH_DP,
                landscape = orientation == Configuration.ORIENTATION_LANDSCAPE,
                // Any keyboard type but NOKEYS counts: book-cover and some USB/Bluetooth keyboards
                // report UNDEFINED or 12KEY rather than QWERTY. A slid-away one is hidden.
                hardwareKeyboard =
                    keyboard != Configuration.KEYBOARD_NOKEYS &&
                        hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES,
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
