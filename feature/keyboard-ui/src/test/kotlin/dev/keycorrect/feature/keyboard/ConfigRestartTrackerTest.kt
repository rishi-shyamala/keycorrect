package dev.keycorrect.feature.keyboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConfigRestartTrackerTest {
    private var now = 10_000L
    private val tracker = ConfigRestartTracker { now }

    @Test
    fun withoutAConfigurationChangeNothingIsAConfigRestart() {
        assertThat(tracker.isConfigRestart()).isFalse()
    }

    @Test
    fun startsRightAfterARotationWhileShownCount() {
        tracker.onConfigurationChanged(inputViewShown = true)
        assertThat(tracker.isConfigRestart()).isTrue()
        now += 1_000
        assertThat(tracker.isConfigRestart()).isTrue()
    }

    @Test
    fun theWindowClosesAfterAWhile() {
        tracker.onConfigurationChanged(inputViewShown = true)
        now += 2_000
        assertThat(tracker.isConfigRestart()).isFalse()
    }

    @Test
    fun aChangeWhileHiddenIsNotARestartOfTheField() {
        tracker.onConfigurationChanged(inputViewShown = false)
        assertThat(tracker.isConfigRestart()).isFalse()
    }

    @Test
    fun hidingTheKeyboardEndsIt() {
        tracker.onConfigurationChanged(inputViewShown = true)
        tracker.reset()
        assertThat(tracker.isConfigRestart()).isFalse()
    }
}
