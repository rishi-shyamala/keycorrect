package dev.keycorrect.feature.keyboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OwnEditTrackerTest {
    private var now = 0L
    private val tracker = OwnEditTracker({ now }, windowMs = 100)

    @Test
    fun updateWithoutOurEditIsExternal() {
        assertThat(tracker.consumeIfOwn(selectionCollapsed = true)).isFalse()
    }

    @Test
    fun eachEditExplainsOneUpdate() {
        tracker.onEdit()
        tracker.onEdit()
        assertThat(tracker.consumeIfOwn(true)).isTrue()
        assertThat(tracker.consumeIfOwn(true)).isTrue()
        assertThat(tracker.consumeIfOwn(true)).isFalse()
    }

    @Test
    fun updatesCoalescedByThePlatformDoNotLeakIntoLaterChanges() {
        tracker.onEdit()
        tracker.onEdit()
        tracker.onEdit()
        assertThat(tracker.consumeIfOwn(true)).isTrue() // one callback for three edits
        now = 101 // later than the window after the last edit
        assertThat(tracker.consumeIfOwn(true)).isFalse()
    }

    @Test
    fun editThatProducedNoUpdateExpires() {
        tracker.onEdit() // e.g. backspace at the start of the field
        now = 101
        assertThat(tracker.consumeIfOwn(true)).isFalse()
        // And the stale count is gone for good.
        now = 102
        assertThat(tracker.consumeIfOwn(true)).isFalse()
    }

    @Test
    fun updateAtTheEdgeOfTheWindowIsStillOurs() {
        tracker.onEdit()
        now = 100
        assertThat(tracker.consumeIfOwn(true)).isTrue()
    }

    @Test
    fun rangeSelectionIsExternalAndClearsPending() {
        tracker.onEdit()
        tracker.onEdit()
        assertThat(tracker.consumeIfOwn(selectionCollapsed = false)).isFalse()
        assertThat(tracker.consumeIfOwn(true)).isFalse()
    }

    @Test
    fun pendingCountIsCapped() {
        repeat(100) { tracker.onEdit() }
        var consumed = 0
        while (tracker.consumeIfOwn(true)) consumed++
        assertThat(consumed).isEqualTo(OwnEditTracker.MAX_PENDING)
    }

    @Test
    fun resetForgetsPendingEdits() {
        tracker.onEdit()
        tracker.reset()
        assertThat(tracker.consumeIfOwn(true)).isFalse()
    }
}
