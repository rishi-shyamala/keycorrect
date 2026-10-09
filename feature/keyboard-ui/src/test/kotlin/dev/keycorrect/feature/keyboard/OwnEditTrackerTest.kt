package dev.keycorrect.feature.keyboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OwnEditTrackerTest {
    private var now = 0L
    private val tracker = OwnEditTracker({ now }, ttlMs = 1_000)

    private fun at(cursor: Int, compStart: Int = -1, compEnd: Int = -1) =
        EditorSelection(cursor, cursor, compStart, compEnd)

    private fun start(cursor: Int = 0) = tracker.reset(at(cursor))

    private fun isOwn(update: EditorSelection) = tracker.onUpdate(update) is UpdateVerdict.Own

    @Test
    fun anUpdateWithoutAnEditOfOursIsExternal() {
        start(0)
        assertThat(tracker.onUpdate(at(5))).isEqualTo(UpdateVerdict.External)
    }

    @Test
    fun theEchoOfACommitIsOurs() {
        start(0)
        tracker.onCommit(3)
        assertThat(isOwn(at(3))).isTrue()
        assertThat(tracker.pendingCount).isEqualTo(0)
    }

    @Test
    fun aBatchIsOneExpectation() {
        start(0)
        tracker.beginBatch()
        tracker.onCommit(1)
        tracker.onCommit(1)
        tracker.endBatch()
        assertThat(tracker.pendingCount).isEqualTo(1)
        assertThat(isOwn(at(2))).isTrue()
    }

    @Test
    fun aDroppedEchoIsHealedByALaterOne() {
        start(0)
        tracker.onSetComposing(1)
        tracker.onSetComposing(2)
        tracker.onSetComposing(3)
        // The updates for 1 and 2 never arrive.
        assertThat(isOwn(at(3, 0, 3))).isTrue()
        assertThat(tracker.pendingCount).isEqualTo(0)
    }

    @Test
    fun coalescedEchoesConsumeEverythingTheyExplain() {
        start(0)
        tracker.onSetComposing(1)
        tracker.onSetComposing(2)
        assertThat(isOwn(at(1, 0, 1))).isTrue() // the first only
        assertThat(tracker.pendingCount).isEqualTo(1)
        assertThat(isOwn(at(2, 0, 2))).isTrue()
    }

    @Test
    fun aDuplicatedEchoIsNotExternal() {
        start(0)
        tracker.onSetComposing(2)
        assertThat(isOwn(at(2, 0, 2))).isTrue()
        assertThat(isOwn(at(2, 0, 2))).isTrue()
        tracker.onFinishComposing()
        assertThat(isOwn(at(2))).isTrue()
        assertThat(isOwn(at(2))).isTrue()
    }

    @Test
    fun aLateEchoAfterExpiryIsStillOursWhenItMatchesTheState() {
        start(0)
        tracker.onCommit(1)
        now = 5_000
        assertThat(isOwn(at(1))).isTrue()
    }

    @Test
    fun aMissingEchoDoesNotHideALaterCursorMove() {
        start(0)
        tracker.onCommit(1) // its echo is lost
        now = 1_001
        assertThat(tracker.onUpdate(at(0))).isEqualTo(UpdateVerdict.External)
    }

    @Test
    fun aNormalisingEditorIsAcceptedAndReportsItsLength() {
        start(0)
        tracker.onSetComposing(2)
        val verdict = tracker.onUpdate(at(3, 0, 3))
        assertThat(verdict).isEqualTo(UpdateVerdict.Own(3))
    }

    @Test
    fun aNormalisedLengthWithoutAPendingEditIsAcceptedToo() {
        start(0)
        tracker.onSetComposing(2)
        assertThat(isOwn(at(2, 0, 2))).isTrue()
        assertThat(tracker.onUpdate(at(3, 0, 3))).isEqualTo(UpdateVerdict.Own(3))
    }

    @Test
    fun aStaleEchoDoesNotRequestAResyncWhileNewerEditsAreInFlight() {
        start(0)
        tracker.onSetComposing(1)
        tracker.onSetComposing(2)
        assertThat(tracker.onUpdate(at(1, 0, 1))).isEqualTo(UpdateVerdict.Own())
    }

    @Test
    fun aCursorMoveInsideTheComposingWordIsExternal() {
        start(0)
        tracker.onSetComposing(5)
        assertThat(isOwn(at(5, 0, 5))).isTrue()
        assertThat(tracker.onUpdate(at(2, 0, 5))).isEqualTo(UpdateVerdict.External)
    }

    @Test
    fun aLostCompositionWhileComposingIsExternal() {
        start(0)
        tracker.onSetComposing(3)
        assertThat(tracker.onUpdate(at(3))).isEqualTo(UpdateVerdict.External)
    }

    @Test
    fun aRangeSelectionIsExternalAndClearsPending() {
        start(0)
        tracker.onCommit(1)
        assertThat(tracker.onUpdate(EditorSelection(0, 1, -1, -1)))
            .isEqualTo(UpdateVerdict.External)
        assertThat(tracker.pendingCount).isEqualTo(0)
    }

    @Test
    fun anUnknownUpdateIsExternal() {
        start(0)
        tracker.onCommit(1)
        assertThat(tracker.onUpdate(EditorSelection())).isEqualTo(UpdateVerdict.External)
    }

    @Test
    fun aBackwardDeleteIsExpectedWithinOneGrapheme() {
        start(10)
        tracker.onDeleteBackward()
        assertThat(isOwn(at(8))).isTrue() // an accent cluster of two units
    }

    @Test
    fun aBackwardDeleteAtTheStartExpectsNothing() {
        start(0)
        tracker.onDeleteBackward()
        assertThat(tracker.pendingCount).isEqualTo(0)
        assertThat(tracker.onUpdate(at(0))).isEqualTo(UpdateVerdict.Own()) // repeats the state
    }

    @Test
    fun aDeletedRangeLeavesTheCursorAtItsStart() {
        tracker.reset(EditorSelection(3, 7))
        tracker.onDeleteBackward()
        assertThat(isOwn(at(3))).isTrue()
    }

    @Test
    fun surroundingDeleteThenComposingInABatchIsOneStep() {
        start(10)
        tracker.beginBatch()
        tracker.onDeleteSurrounding(5)
        tracker.onSetComposing(5)
        tracker.endBatch()
        assertThat(tracker.pendingCount).isEqualTo(1)
        assertThat(isOwn(at(10, 5, 10))).isTrue()
    }

    @Test
    fun resetForgetsPendingEdits() {
        start(0)
        tracker.onCommit(1)
        tracker.reset(at(0))
        assertThat(tracker.pendingCount).isEqualTo(0)
    }

    @Test
    fun pendingExpectationsAreCapped() {
        start(0)
        repeat(100) { tracker.onCommit(1) }
        assertThat(tracker.pendingCount).isEqualTo(OwnEditTracker.MAX_PENDING)
    }

    @Test
    fun withAnUnknownStartTheNextEchoLearnsTheCursor() {
        tracker.reset()
        tracker.onCommit(1)
        assertThat(isOwn(at(7))).isTrue()
        tracker.onCommit(1)
        assertThat(isOwn(at(8))).isTrue()
        assertThat(tracker.onUpdate(at(2))).isEqualTo(UpdateVerdict.External)
    }

    @Test
    fun anIntermediateStateOfABatchIsOursAndKeepsTheFinalExpectation() {
        start(10)
        tracker.beginBatch()
        tracker.onDeleteSurrounding(5)
        tracker.onSetComposing(5)
        tracker.endBatch()
        // A non-coalescing editor: "cursor at the word start, nothing composing", then the end.
        assertThat(isOwn(at(5))).isTrue()
        assertThat(tracker.pendingCount).isEqualTo(1)
        assertThat(isOwn(at(10, 5, 10))).isTrue()
        assertThat(tracker.pendingCount).isEqualTo(0)
    }

    @Test
    fun anIntermediateStateReportedInsideTheBatchIsOurs() {
        start(10)
        tracker.beginBatch()
        tracker.onDeleteSurrounding(5)
        assertThat(isOwn(at(5))).isTrue() // reported before the batch ended
        tracker.onSetComposing(5)
        assertThat(isOwn(at(10, 5, 10))).isTrue()
        tracker.endBatch()
        assertThat(isOwn(at(10, 5, 10))).isTrue() // the batch's own echo still matches
        assertThat(tracker.pendingCount).isEqualTo(0)
    }

    @Test
    fun anUpdateMatchingNoStepOfTheBatchIsStillExternal() {
        start(10)
        tracker.beginBatch()
        tracker.onDeleteSurrounding(5)
        tracker.onSetComposing(5)
        tracker.endBatch()
        assertThat(tracker.onUpdate(at(7))).isEqualTo(UpdateVerdict.External)
    }

    @Test
    fun aReversedSelectionIsNormalised() {
        tracker.reset(EditorSelection(7, 3))
        tracker.onDeleteBackward()
        assertThat(isOwn(at(3))).isTrue()
        tracker.reset(at(4, 6, 2)) // a reversed composing region
        tracker.onSetComposing(2)
        assertThat(isOwn(at(4, 2, 4))).isTrue()
    }
}
