package dev.keycorrect.ime

import android.content.res.Configuration
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LearningWiringTest {
    @Test
    fun deviceProfileFromConfiguration() {
        val tabletLandscape =
            DeviceProfile.from(
                smallestScreenWidthDp = 600,
                orientation = Configuration.ORIENTATION_LANDSCAPE,
                keyboard = Configuration.KEYBOARD_NOKEYS,
                hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_YES,
            )
        assertThat(tabletLandscape).isEqualTo(DeviceProfile(true, true, false))

        val phoneWithKeys =
            DeviceProfile.from(
                smallestScreenWidthDp = 411,
                orientation = Configuration.ORIENTATION_PORTRAIT,
                keyboard = Configuration.KEYBOARD_QWERTY,
                hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_NO,
            )
        assertThat(phoneWithKeys).isEqualTo(DeviceProfile(false, false, true))

        // A physical keyboard that is present but hidden does not count.
        val hidden =
            DeviceProfile.from(
                411,
                Configuration.ORIENTATION_PORTRAIT,
                Configuration.KEYBOARD_QWERTY,
                Configuration.HARDKEYBOARDHIDDEN_YES,
            )
        assertThat(hidden.hardwareKeyboard).isFalse()
    }

    @Test
    fun decodeActivityIsBusyDuringAndJustAfterADecode() {
        var now = 0L
        val activity = DecodeActivity(nanoClock = { now }, quietNanos = 100)
        assertThat(activity.busy()).isFalse()
        activity.track {
            assertThat(activity.busy()).isTrue()
            now = 50
        }
        assertThat(activity.busy()).isTrue() // quiet period
        now = 149
        assertThat(activity.busy()).isTrue()
        now = 151
        assertThat(activity.busy()).isFalse()
    }

    @Test
    fun decodeActivityRecoversFromAFailingDecode() {
        val activity = DecodeActivity(nanoClock = { 0 }, quietNanos = 0)
        runCatching { activity.track { error("boom") } }
        assertThat(activity.busy()).isFalse()
    }

    @Test
    fun decodeBudgetCapsCallsPerWindow() {
        var now = 0L
        val budget = DecodeBudget(maxCalls = 3, windowNanos = 1_000, nanoClock = { now })
        assertThat(List(5) { budget.tryAcquire() })
            .containsExactly(true, true, true, false, false)
            .inOrder()
        now = 1_000
        assertThat(budget.tryAcquire()).isTrue()
    }
}
