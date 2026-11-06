package dev.keycorrect.ime

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.settings.SizeProfile
import org.junit.Test

class DisplaySizeProfileTest {
    @Test
    fun phonePortraitAndLandscape() {
        // 1440x3088 at 560 dpi: 411 dp smallest width.
        assertThat(DisplaySizeProfile.of(1440, 3088, 560))
            .isEqualTo(SizeProfile(tablet = false, landscape = false))
        assertThat(DisplaySizeProfile.of(3088, 1440, 560))
            .isEqualTo(SizeProfile(tablet = false, landscape = true))
    }

    @Test
    fun tabletUsesTheSmallestSide() {
        // 1600x2560 at 320 dpi: 800 dp smallest width.
        assertThat(DisplaySizeProfile.of(1600, 2560, 320))
            .isEqualTo(SizeProfile(tablet = true, landscape = false))
        assertThat(DisplaySizeProfile.of(2560, 1600, 320))
            .isEqualTo(SizeProfile(tablet = true, landscape = true))
    }
}
