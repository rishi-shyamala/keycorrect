package dev.keycorrect.ime

import android.content.res.Configuration
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.core.settings.SizeProfile
import dev.keycorrect.core.settings.SizeSettings
import dev.keycorrect.core.transfer.SettingsSection
import org.junit.Test

class SizeProfileSelectionTest {
    private fun profile(smallestWidthDp: Int, orientation: Int) =
        DeviceProfile.from(
                smallestWidthDp,
                orientation,
                Configuration.KEYBOARD_NOKEYS,
                Configuration.HARDKEYBOARDHIDDEN_YES,
            )
            .sizeProfile

    private val settings =
        KeyboardSettings(keyHeightDp = 60)
            .withSizes(
                SizeProfile(tablet = false, landscape = true),
                SizeSettings(44, 0.5f, 1.0f, 1, 2, false),
            )

    @Test
    fun rotationChangesTheSizesTheKeyboardUses() {
        val portrait = profile(411, Configuration.ORIENTATION_PORTRAIT)
        val landscape = profile(411, Configuration.ORIENTATION_LANDSCAPE)
        assertThat(portrait).isNotEqualTo(landscape)
        assertThat(settings.forProfile(portrait).keyHeightDp).isEqualTo(60)
        assertThat(settings.forProfile(landscape).keyHeightDp).isEqualTo(44)
        assertThat(settings.forProfile(landscape).numberRow).isFalse()
    }

    @Test
    fun aTabletScreenSelectsTheTabletProfile() {
        val tablet = profile(800, Configuration.ORIENTATION_LANDSCAPE)
        assertThat(tablet.tablet).isTrue()
        assertThat(settings.forProfile(tablet).keyHeightDp).isEqualTo(60)
    }

    @Test
    fun everyProfileSizeKeyIsScreenTiedInTransfer() {
        val tied = SettingsSection.SCREEN_TIED_KEYS
        val bases =
            listOf(
                "key_height_dp",
                "edge_padding_dp",
                "key_gap_dp",
                "bottom_key_width",
                "mode_enter_key_width",
            )
        for (p in SizeProfile.ALL) {
            for (base in bases) assertThat(tied).contains("$base@${p.id}")
            // The number row is a preference, not a measurement: it is never skipped.
            assertThat(tied).doesNotContain("number_row@${p.id}")
        }
    }
}
