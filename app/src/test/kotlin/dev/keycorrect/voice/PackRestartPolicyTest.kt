package dev.keycorrect.voice

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PackRestartPolicyTest {
    private var kills = 0
    private var dictating = false
    private val policy = PackRestartPolicy({ kills++ }, { dictating })

    private fun broadcast(action: String, replacing: Boolean = false, stale: Boolean = true) =
        policy.onPackBroadcast(action, replacing) { stale }

    @Test
    fun aChangedPackMarksARestartThatHappensOnlyWhenHidden() {
        broadcast(Intent.ACTION_PACKAGE_REPLACED)
        assertThat(policy.pending).isTrue()
        assertThat(kills).isEqualTo(0) // nothing happens until the window hides
        assertThat(policy.onKeyboardHidden()).isTrue()
        assertThat(kills).isEqualTo(1)
        assertThat(policy.pending).isFalse()
        assertThat(policy.onKeyboardHidden()).isFalse()
        assertThat(kills).isEqualTo(1)
    }

    @Test
    fun noRestartWhileDictatingThenItHappensAtTheNextHide() {
        broadcast(Intent.ACTION_PACKAGE_ADDED)
        dictating = true
        assertThat(policy.onKeyboardHidden()).isFalse()
        assertThat(kills).isEqualTo(0)
        assertThat(policy.pending).isTrue()
        dictating = false
        assertThat(policy.onKeyboardHidden()).isTrue()
        assertThat(kills).isEqualTo(1)
    }

    @Test
    fun anUnchangedOrNothingLoadedPackNeverRestarts() {
        broadcast(Intent.ACTION_PACKAGE_REPLACED, stale = false)
        assertThat(policy.pending).isFalse()
        assertThat(policy.onKeyboardHidden()).isFalse()
        assertThat(kills).isEqualTo(0)
    }

    @Test
    fun theRemovalInsideAnUpdateIsIgnoredButARealRemovalCounts() {
        var asked = false
        policy.onPackBroadcast(Intent.ACTION_PACKAGE_REMOVED, replacing = true) {
            asked = true
            true
        }
        assertThat(asked).isFalse()
        assertThat(policy.pending).isFalse()
        broadcast(Intent.ACTION_PACKAGE_REMOVED)
        assertThat(policy.pending).isTrue()
    }

    @Test
    fun otherActionsAreIgnored() {
        policy.onPackBroadcast(Intent.ACTION_PACKAGE_CHANGED, replacing = false) { true }
        policy.onPackBroadcast(null, replacing = false) { true }
        assertThat(policy.pending).isFalse()
    }

    @Test
    fun theTapRequestsARestartAtTheNextHide() {
        policy.requestRestart()
        assertThat(policy.onKeyboardHidden()).isTrue()
        assertThat(kills).isEqualTo(1)
    }
}
