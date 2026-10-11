package dev.keycorrect.ime

import android.content.res.Configuration

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
