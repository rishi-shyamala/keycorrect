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

    @Test
    fun deleteIsCountedWhenThePositionIsUnknown() {
        tracker.onDelete()
        assertThat(tracker.consumeIfOwn(true)).isTrue()
    }

    @Test
    fun deleteAtTheKnownStartOfTheFieldIsNotCounted() {
        tracker.consumeIfOwn(selectionCollapsed = true, atStart = true) // external: cursor at 0
        tracker.onDelete() // nothing to delete, no callback will follow
        assertThat(tracker.consumeIfOwn(true)).isFalse()
    }

    @Test
    fun rangeSelectionIsNotTheStart() {
        tracker.consumeIfOwn(selectionCollapsed = false, atStart = true)
        tracker.onDelete()
        assertThat(tracker.consumeIfOwn(true)).isTrue()
    }

    @Test
    fun anInsertMovesTheCursorOffTheStart() {
        tracker.consumeIfOwn(selectionCollapsed = true, atStart = true)
        tracker.onEdit()
        tracker.onDelete()
        assertThat(tracker.consumeIfOwn(true)).isTrue()
        assertThat(tracker.consumeIfOwn(true)).isTrue()
        assertThat(tracker.consumeIfOwn(true)).isFalse()
    }

    @Test
    fun aDeleteThatReachesTheStartMakesTheNextOneFree() {
        tracker.consumeIfOwn(true, atStart = false)
        tracker.onDelete()
        assertThat(tracker.consumeIfOwn(true, atStart = true)).isTrue() // our own, now at 0
        tracker.onDelete()
        assertThat(tracker.consumeIfOwn(true)).isFalse()
    }

    @Test
    fun resetForgetsTheStartPosition() {
        tracker.consumeIfOwn(true, atStart = true)
        tracker.reset()
        tracker.onDelete()
        assertThat(tracker.consumeIfOwn(true)).isTrue()
    }

    @Test
    fun defaultWindowIsShort() {
        assertThat(OwnEditTracker.DEFAULT_WINDOW_MS).isAtMost(150L)
    }
}
