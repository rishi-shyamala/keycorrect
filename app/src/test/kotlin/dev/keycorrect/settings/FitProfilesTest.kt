package dev.keycorrect.settings

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.learning.model.ProfileTouchStatistics
import dev.keycorrect.core.settings.SizeProfile
import org.junit.Test

class FitProfilesTest {
    private fun stats(key: String, words: Int) =
        ProfileTouchStatistics(key, words, words * 5, emptyList(), 0f, 0f, 0f, 0f, 0f, 0f, true)

    @Test
    fun touchKeyMapsToSizeProfileAndHeight() {
        val i = parseTouchKey("Tablet/land/letters+nr@62dp/b100/g2")
        assertThat(i.sizeProfile).isEqualTo(SizeProfile(tablet = true, landscape = true))
        assertThat(i.recordedHeightDp).isEqualTo(62)
        assertThat(parseTouchKey("Phone/port/letters@54dp/b90/g2").sizeProfile)
            .isEqualTo(SizeProfile(tablet = false, landscape = false))
    }

    @Test
    fun hardwareKeyboardAndJunkAreUnmapped() {
        assertThat(parseTouchKey("HardwareKeyboard/land/letters@54dp").sizeProfile).isNull()
        assertThat(parseTouchKey("nonsense").sizeProfile).isNull()
        assertThat(parseTouchKey("nonsense").recordedHeightDp).isNull()
    }

    @Test
    fun pickPrefersCurrentHeightThenWords() {
        val phone = SizeProfile(false, false)
        val a = stats("Phone/port/letters@54dp/g2", 100)
        val b = stats("Phone/port/letters@60dp/g2", 500)
        val other = stats("Tablet/port/letters@54dp/g2", 900)
        assertThat(pickTouchProfile(listOf(a, b, other), phone, 54)).isEqualTo(a)
        assertThat(pickTouchProfile(listOf(a, b, other), phone, 70)).isEqualTo(b)
    }

    @Test
    fun pickFallsBackToAnyProfileWhenNoneMaps() {
        val hw = stats("HardwareKeyboard/port/letters@54dp", 10)
        assertThat(pickTouchProfile(listOf(hw), SizeProfile(false, false), 54)).isEqualTo(hw)
        assertThat(pickTouchProfile(emptyList(), SizeProfile(false, false), 54)).isNull()
    }
}
