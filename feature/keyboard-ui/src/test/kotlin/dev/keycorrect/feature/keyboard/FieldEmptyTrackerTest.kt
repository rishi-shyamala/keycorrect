package dev.keycorrect.feature.keyboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FieldEmptyTrackerTest {
    private var selection = EditorSelection(0, 0)
    private var sensitive = false
    private var after: CharSequence? = ""
    private var reads = 0
    private val tracker =
        FieldEmptyTracker(
            selection = { selection },
            sensitive = { sensitive },
            textAfterCursor = {
                reads++
                after
            },
        )

    @Test
    fun cursorAtZeroOfFreshFieldIsEmptyWithoutAnEditorRead() {
        tracker.refresh(newField = true)
        assertThat(tracker.empty).isTrue()
        assertThat(reads).isEqualTo(0)
    }

    @Test
    fun cursorPastZeroOrRangeMeansText() {
        selection = EditorSelection(3, 3)
        tracker.refresh(newField = true)
        assertThat(tracker.empty).isFalse()
        selection = EditorSelection(0, 2)
        tracker.refresh(newField = true)
        assertThat(tracker.empty).isFalse()
    }

    @Test
    fun afterTextWasSeenTheEditorIsAskedOnceUntilTheCursorMoves() {
        selection = EditorSelection(2, 2)
        tracker.refresh(newField = true)
        selection = EditorSelection(0, 0)
        after = ""
        tracker.refresh()
        assertThat(tracker.empty).isTrue()
        assertThat(reads).isEqualTo(1)
        tracker.refresh()
        assertThat(reads).isEqualTo(1)
        tracker.refresh(externalChange = true)
        assertThat(reads).isEqualTo(2)
    }

    @Test
    fun textAfterTheCursorAtZeroMeansNotEmpty() {
        selection = EditorSelection(1, 1)
        tracker.refresh(newField = true)
        selection = EditorSelection(0, 0)
        after = "x"
        tracker.refresh()
        assertThat(tracker.empty).isFalse()
    }

    @Test
    fun sensitiveFieldIsNeverRead() {
        sensitive = true
        selection = EditorSelection(2, 2)
        tracker.refresh(newField = true)
        selection = EditorSelection(0, 0)
        tracker.refresh()
        assertThat(reads).isEqualTo(0)
        assertThat(tracker.empty).isFalse()
    }

    @Test
    fun typedTextEndsEmpty() {
        tracker.refresh(newField = true)
        tracker.noteTextTyped()
        assertThat(tracker.empty).isFalse()
    }
}
