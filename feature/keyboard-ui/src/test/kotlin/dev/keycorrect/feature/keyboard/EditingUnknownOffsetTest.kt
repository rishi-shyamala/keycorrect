package dev.keycorrect.feature.keyboard

import android.text.InputType
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.input.Surrounding
import dev.keycorrect.core.input.TextEditor
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.settings.KeyboardSettings
import org.junit.Test

/** An editor whose `getSurroundingText` says nothing of where its text begins (offset -1). */
private class UnknownOffsetEditor(private val sim: SimEditor) : TextEditor by sim {
    /** How many times the text around the cursor was read. */
    var reads = 0

    override fun surroundingText(beforeLength: Int, afterLength: Int): Surrounding? {
        reads++
        return sim.surroundingText(beforeLength, afterLength)?.copy(offset = -1)
    }
}

/**
 * The editing panel's Word / Line / Sentence and the backspace swipe in editors that report the
 * surrounding-text offset as -1 (docs/PHASE4.md, feedback round 1, row 9): the keyboard's tracked
 * cursor stands in, but only while none of its own edits is in flight.
 */
class EditingUnknownOffsetTest {
    private val sim = SimEditor()
    private val editor = UnknownOffsetEditor(sim)
    private val backend = FakeBackend()
    private val controller: KeyboardController

    init {
        backend.predictor = ScriptedPredictor()
        controller =
            KeyboardController(
                editor,
                KeyboardComposer(BuiltInLayouts.repository()),
                testEnv(backend),
            )
        sim.onOperation = { sim.echo(controller) }
    }

    private fun start(text: String, at: Int = text.length) {
        sim.setText(text, at)
        controller.onStartInput(
            FieldProfile.from(InputType.TYPE_CLASS_TEXT, 0),
            KeyboardSettings(),
            false,
            EditorIdentity(1, "pkg", InputType.TYPE_CLASS_TEXT, 0, null),
            sim.cursor,
            sim.cursor,
        )
    }

    private fun perform(action: EditingAction) = controller.editing.perform(action)

    /** Types [chars] without the platform reporting them, so the edits stay in flight. */
    private fun typeUnreported(chars: String) {
        sim.onOperation = null
        chars.forEach { controller.onKey(KeyAction.Text("$it")) }
    }

    private fun reportPending() {
        sim.echo(controller)
    }

    private val prose = "Hello there. How are you? Fine!\nSecond line here"

    @Test
    fun wordLineAndSentenceUseTheTrackedCursor() {
        start(prose, at = 9)
        perform(EditingAction.SelectWord)
        assertThat(sim.selected).isEqualTo("there")
        start(prose, at = 16)
        perform(EditingAction.SelectSentence)
        assertThat(sim.selected).isEqualTo("How are you?")
        start(prose, at = 40)
        perform(EditingAction.SelectLine)
        assertThat(sim.selected).isEqualTo("Second line here")
        assertThat(sim.selections.last()).isEqualTo(32 to 48)
    }

    @Test
    fun wordAtAWordEndSelectsThePreviousWord() {
        start("one two", at = 7)
        perform(EditingAction.SelectWord)
        assertThat(sim.selected).isEqualTo("two")
        start("one two ", at = 8)
        perform(EditingAction.SelectWord)
        assertThat(sim.selected).isEqualTo("two")
    }

    @Test
    fun withAnEditInFlightNothingWrongIsSelectedAndItIsAppliedOnceItSettles() {
        start("one two", at = 7)
        typeUnreported(" x")
        sim.selections.clear()
        perform(EditingAction.SelectWord)
        assertThat(sim.selections).isEmpty()
        // The platform reports the typed chars: the tracker settles and the action is carried out.
        reportPending()
        assertThat(sim.selections).hasSize(1)
        assertThat(sim.selected).isEqualTo("x")
    }

    @Test
    fun aDeferredActionIsDroppedWhenAnotherIsChosen() {
        start("one two", at = 7)
        typeUnreported(" x")
        sim.selections.clear()
        perform(EditingAction.SelectWord)
        perform(EditingAction.Left)
        reportPending()
        assertThat(sim.selections).isEmpty()
    }

    @Test
    fun closingThePanelDropsADeferredAction() {
        start("one two", at = 7)
        typeUnreported(" x")
        sim.selections.clear()
        perform(EditingAction.SelectWord)
        perform(EditingAction.Close)
        reportPending()
        assertThat(sim.selections).isEmpty()
    }

    @Test
    fun aCursorMovedByTheUserDropsADeferredAction() {
        start("one two", at = 7)
        typeUnreported(" x")
        sim.selections.clear()
        perform(EditingAction.SelectWord)
        controller.onSelectionChanged(0, 0) // the user tapped at the start of the field
        reportPending()
        assertThat(sim.selections).isEmpty()
    }

    @Test
    fun anUnsettledSwipeReadsTheEditorOncePerWait() {
        start("foo bar", at = 7)
        typeUnreported(" ")
        val before = editor.reads
        repeat(5) { controller.gestures.onBackspaceSwipe(1) }
        assertThat(editor.reads - before).isEqualTo(1)
    }

    @Test
    fun theBackspaceSwipeRetriesWhileAnEditIsInFlight() {
        start("foo bar", at = 7)
        typeUnreported(" ")
        sim.selections.clear()
        controller.gestures.onBackspaceSwipe(1)
        assertThat(sim.selections).isEmpty()
        assertThat(controller.editing.swipeActive).isFalse()
        reportPending()
        controller.gestures.onBackspaceSwipe(1)
        assertThat(sim.selected).isEqualTo("bar ")
        assertThat(controller.editing.swipeActive).isTrue()
        controller.gestures.onBackspaceSwipeEnd(commit = true)
        assertThat(sim.text.toString()).isEqualTo("foo ")
    }

    @Test
    fun theBackspaceSwipeWorksWithAnUnknownOffsetWhenSettled() {
        start("foo bar baz ")
        controller.gestures.onBackspaceSwipe(2)
        assertThat(sim.selections.last()).isEqualTo(4 to 12)
    }
}
