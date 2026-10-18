package dev.keycorrect.feature.keyboard

import android.text.InputType
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.settings.KeyboardSettings
import org.junit.Test

/** [UnitEdges.move] and the unit boundaries behind it. */
class SelectionEdgeUnitsTest {
    private fun starts(unit: SelectionUnit, text: String) =
        UnitEdges.unitSpans(unit, text).map { it.start }

    private fun ends(unit: SelectionUnit, text: String) =
        UnitEdges.unitSpans(unit, text).map { it.end }

    private fun move(
        unit: SelectionUnit,
        text: String,
        start: Int,
        end: Int,
        edge: SelectionEdge,
        forward: Boolean,
    ) = UnitEdges.move(unit, text, TextSpan(start, end), edge, forward)?.let { it.start to it.end }

    @Test
    fun wordBoundariesSkipSpacesAndPunctuation() {
        val text = "Hi,  there  you're ok"
        assertThat(starts(SelectionUnit.Word, text)).containsExactly(0, 5, 12, 19).inOrder()
        assertThat(ends(SelectionUnit.Word, text)).containsExactly(2, 10, 18, 21).inOrder()
    }

    @Test
    fun anEmojiIsAWordOfItsOwn() {
        val text = "hi 😀 yo"
        assertThat(starts(SelectionUnit.Word, text)).containsExactly(0, 3, 6).inOrder()
        assertThat(ends(SelectionUnit.Word, text)).containsExactly(2, 5, 8).inOrder()
    }

    @Test
    fun sentenceBoundariesFollowTheExpandRules() {
        val text = "Hello there. How are you? Fine!\nSecond line here"
        assertThat(starts(SelectionUnit.Sentence, text)).containsExactly(0, 13, 26, 32).inOrder()
        assertThat(ends(SelectionUnit.Sentence, text)).containsExactly(12, 25, 31, 48).inOrder()
    }

    @Test
    fun sentencesSkipRunsOfSpacesAndEmptyLines() {
        val text = "One.   Two.\n\n  Three"
        assertThat(starts(SelectionUnit.Sentence, text)).containsExactly(0, 7, 15).inOrder()
        assertThat(ends(SelectionUnit.Sentence, text)).containsExactly(4, 11, 20).inOrder()
    }

    @Test
    fun lineBoundariesAreTheLineBreaks() {
        val text = "ab\r\n\ncd\nef"
        assertThat(starts(SelectionUnit.Line, text)).containsExactly(0, 5, 8).inOrder()
        assertThat(ends(SelectionUnit.Line, text)).containsExactly(2, 7, 10).inOrder()
    }

    @Test
    fun noUnitsInBlankOrEmptyText() {
        for (unit in SelectionUnit.entries) {
            assertThat(UnitEdges.unitSpans(unit, "")).isEmpty()
        }
        assertThat(UnitEdges.unitSpans(SelectionUnit.Word, "  ")).isEmpty()
        assertThat(UnitEdges.unitSpans(SelectionUnit.Sentence, "  ")).isEmpty()
    }

    @Test
    fun startMovesToUnitStartsAndSnaps() {
        val text = "one two three"
        val w = SelectionUnit.Word
        assertThat(move(w, text, 8, 13, SelectionEdge.Start, false)).isEqualTo(4 to 13)
        assertThat(move(w, text, 6, 13, SelectionEdge.Start, false)).isEqualTo(4 to 13)
        assertThat(move(w, text, 4, 13, SelectionEdge.Start, true)).isEqualTo(8 to 13)
        assertThat(move(w, text, 5, 13, SelectionEdge.Start, true)).isEqualTo(8 to 13)
        assertThat(move(w, text, 0, 13, SelectionEdge.Start, false)).isNull()
    }

    @Test
    fun endMovesToUnitEndsAndSnaps() {
        val text = "one two three"
        val w = SelectionUnit.Word
        assertThat(move(w, text, 0, 13, SelectionEdge.End, false)).isEqualTo(0 to 7)
        assertThat(move(w, text, 0, 10, SelectionEdge.End, false)).isEqualTo(0 to 7)
        assertThat(move(w, text, 0, 3, SelectionEdge.End, true)).isEqualTo(0 to 7)
        assertThat(move(w, text, 0, 13, SelectionEdge.End, true)).isNull()
    }

    @Test
    fun anEdgePastTheOtherCollapsesToACaretAtTheFixedEdge() {
        val text = "one two three"
        val w = SelectionUnit.Word
        // Start forward from the last word: no unit start left before the end.
        assertThat(move(w, text, 8, 13, SelectionEdge.Start, true)).isEqualTo(13 to 13)
        assertThat(move(w, text, 4, 7, SelectionEdge.Start, true)).isEqualTo(7 to 7)
        // End back past the start.
        assertThat(move(w, text, 4, 7, SelectionEdge.End, false)).isEqualTo(4 to 4)
        assertThat(move(w, text, 0, 3, SelectionEdge.End, false)).isEqualTo(0 to 0)
    }

    @Test
    fun sentenceEdges() {
        val text = "Hi there. Good one. Bye now."
        val s = SelectionUnit.Sentence
        assertThat(move(s, text, 0, 27, SelectionEdge.End, false)).isEqualTo(0 to 19)
        assertThat(move(s, text, 0, 19, SelectionEdge.End, false)).isEqualTo(0 to 9)
        assertThat(move(s, text, 0, 9, SelectionEdge.End, false)).isEqualTo(0 to 0)
        assertThat(move(s, text, 10, 27, SelectionEdge.Start, false)).isEqualTo(0 to 27)
        assertThat(move(s, text, 10, 27, SelectionEdge.Start, true)).isEqualTo(20 to 27)
        assertThat(move(s, text, 20, 27, SelectionEdge.Start, true)).isEqualTo(27 to 27)
        assertThat(move(s, text, 0, 9, SelectionEdge.End, true)).isEqualTo(0 to 19)
    }

    @Test
    fun lineEdges() {
        val text = "ab\ncd\nef"
        val l = SelectionUnit.Line
        assertThat(move(l, text, 3, 5, SelectionEdge.End, true)).isEqualTo(3 to 8)
        assertThat(move(l, text, 3, 8, SelectionEdge.Start, false)).isEqualTo(0 to 8)
        assertThat(move(l, text, 0, 8, SelectionEdge.End, false)).isEqualTo(0 to 5)
        assertThat(move(l, text, 0, 5, SelectionEdge.Start, true)).isEqualTo(3 to 5)
    }

    @Test
    fun edgesNeverMoveOutOfTheTextOrSplitAnEmoji() {
        val text = "a 😀 b"
        val w = SelectionUnit.Word
        assertThat(move(w, text, 0, 1, SelectionEdge.End, true)).isEqualTo(0 to 4)
        assertThat(move(w, text, 0, 4, SelectionEdge.End, false)).isEqualTo(0 to 1)
        assertThat(move(w, text, 0, 6, SelectionEdge.End, true)).isNull()
    }
}

/** The selection strip's edge moves through [EditingController] against [SimEditor]. */
class SelectionEdgeControllerTest {
    private val editor = SimEditor()
    private val backend = FakeBackend()
    private val controller: KeyboardController
    private val prose = "Hi there. Good morning to you. Bye now."

    init {
        backend.predictor = ScriptedPredictor()
        controller =
            KeyboardController(
                editor,
                KeyboardComposer(BuiltInLayouts.repository()),
                testEnv(backend),
            )
        editor.onOperation = { editor.echo(controller) }
    }

    private fun start(text: String, inputType: Int = InputType.TYPE_CLASS_TEXT) {
        editor.setText(text, text.length)
        controller.onStartInput(
            FieldProfile.from(inputType, 0),
            KeyboardSettings(),
            false,
            EditorIdentity(1, "pkg", inputType, 0, null),
            editor.cursor,
            editor.cursor,
        )
    }

    private val editing
        get() = controller.editing

    private fun move(unit: SelectionUnit, edge: SelectionEdge, forward: Boolean) =
        editing.moveSelectionEdge(unit, edge, forward)

    @Test
    fun endBackUnselectsTheLastSentenceBackToTheEndOfThePreviousOne() {
        start(prose)
        editor.setSelection(14, 34) // reaches into the next sentence
        assertThat(editing.expandSelection(SelectionUnit.Sentence)).isTrue()
        assertThat(editor.selected).isEqualTo("Good morning to you. Bye now.")
        assertThat(move(SelectionUnit.Sentence, SelectionEdge.End, false)).isTrue()
        assertThat(editor.selected).isEqualTo("Good morning to you.")
        assertThat(editor.selEnd).isEqualTo(30)
    }

    @Test
    fun startBackAddsThePreviousSentence() {
        start(prose)
        editor.setSelection(10, 30)
        assertThat(move(SelectionUnit.Sentence, SelectionEdge.Start, false)).isTrue()
        assertThat(editor.selected).isEqualTo("Hi there. Good morning to you.")
    }

    @Test
    fun endForwardAddsTheNextSentence() {
        start(prose)
        editor.setSelection(10, 30)
        assertThat(move(SelectionUnit.Sentence, SelectionEdge.End, true)).isTrue()
        assertThat(editor.selected).isEqualTo("Good morning to you. Bye now.")
    }

    @Test
    fun startForwardRemovesTheFirstSentence() {
        start(prose)
        editor.setSelection(0, 39)
        assertThat(move(SelectionUnit.Sentence, SelectionEdge.Start, true)).isTrue()
        assertThat(editor.selected).isEqualTo("Good morning to you. Bye now.")
    }

    @Test
    fun theSelectionNeverInvertsItCollapsesToACaret() {
        start(prose)
        editor.setSelection(10, 30)
        assertThat(move(SelectionUnit.Sentence, SelectionEdge.End, false)).isTrue()
        assertThat(editor.selStart).isEqualTo(10)
        assertThat(editor.selEnd).isEqualTo(10)
        assertThat(controller.selectionStripVisible).isFalse()

        editor.setSelection(10, 30)
        assertThat(move(SelectionUnit.Sentence, SelectionEdge.Start, true)).isTrue()
        assertThat(editor.selStart).isEqualTo(30)
        assertThat(editor.selEnd).isEqualTo(30)
    }

    @Test
    fun anEdgeThatCannotMoveChangesNothing() {
        start(prose)
        editor.setSelection(0, 39)
        assertThat(move(SelectionUnit.Sentence, SelectionEdge.Start, false)).isFalse()
        assertThat(move(SelectionUnit.Sentence, SelectionEdge.End, true)).isFalse()
        assertThat(editor.selStart).isEqualTo(0)
        assertThat(editor.selEnd).isEqualTo(39)
    }

    @Test
    fun wordEdgesStepOneWordAtATime() {
        start("one two three four")
        editor.setSelection(4, 13)
        assertThat(editing.expandSelection(SelectionUnit.Word)).isFalse() // already whole
        assertThat(move(SelectionUnit.Word, SelectionEdge.End, false)).isTrue()
        assertThat(editor.selected).isEqualTo("two")
        assertThat(move(SelectionUnit.Word, SelectionEdge.Start, false)).isTrue()
        assertThat(editor.selected).isEqualTo("one two")
        assertThat(move(SelectionUnit.Word, SelectionEdge.End, true)).isTrue()
        assertThat(editor.selected).isEqualTo("one two three")
        assertThat(move(SelectionUnit.Word, SelectionEdge.Start, true)).isTrue()
        assertThat(editor.selected).isEqualTo("two three")
        assertThat(move(SelectionUnit.Word, SelectionEdge.Start, true)).isTrue()
        assertThat(editor.selected).isEqualTo("three")
        assertThat(move(SelectionUnit.Word, SelectionEdge.Start, true)).isTrue()
        assertThat(editor.selStart).isEqualTo(editor.selEnd)
    }

    @Test
    fun lineEdgesStepOneLineAtATime() {
        start("first\nsecond\nthird")
        editor.setSelection(6, 12)
        assertThat(move(SelectionUnit.Line, SelectionEdge.End, true)).isTrue()
        assertThat(editor.selected).isEqualTo("second\nthird")
        assertThat(move(SelectionUnit.Line, SelectionEdge.Start, false)).isTrue()
        assertThat(editor.selected).isEqualTo("first\nsecond\nthird")
    }

    @Test
    fun nothingIsReadInASensitiveField() {
        start(
            "secret words here",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        editor.setSelection(0, 6)
        val reads = editor.readCalls
        assertThat(move(SelectionUnit.Word, SelectionEdge.End, true)).isFalse()
        assertThat(editing.expandSelection(SelectionUnit.Word)).isFalse()
        assertThat(editor.readCalls).isEqualTo(reads)
        assertThat(editor.selEnd).isEqualTo(6)
    }
}
