package dev.keycorrect.feature.keyboard

import android.text.InputType
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.learning.CommitMethod
import dev.keycorrect.core.learning.WordCommitted
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.feature.keyboard.prediction.SlotKind
import org.junit.Test

/** Re-correction of a word the cursor is placed in (feedback round 4, row 8). */
class RecorrectionTest {
    private val editor = SimEditor()
    private val backend = FakeBackend()
    private val predictor = ScriptedPredictor()
    private val sink = RecordingSink()
    private val controller: KeyboardController

    init {
        backend.predictor = predictor
        backend.known += listOf("the", "hello", "then", "cat")
        controller =
            KeyboardController(
                editor,
                KeyboardComposer(BuiltInLayouts.repository()),
                testEnv(backend),
                clock = { 10_000L },
                learning = sink,
            )
        predictor.word("teh", listOf("the", "ten", "tech"))
    }

    private val textField = FieldProfile.from(InputType.TYPE_CLASS_TEXT, 0)

    private fun start(profile: FieldProfile = textField) =
        controller.onStartInput(profile, KeyboardSettings(), false, null, 0, 0)

    /** The user taps the text to put the cursor at [at]. */
    private fun tapInto(text: String, at: Int) {
        editor.setText(text, at)
        editor.echo(controller)
    }

    private fun text() = editor.text.toString()

    @Test
    fun aMissedTypoAtTheCursorShowsCorrectionsAndTheTapReplacesIt() {
        start()
        tapInto("say teh", 7)
        val strip = controller.strip
        assertThat(strip.centre?.text).isEqualTo("the")
        assertThat(strip.left?.text).isEqualTo("ten")
        assertThat(strip.right?.text).isEqualTo("tech")
        val request = predictor.requests.last()
        assertThat(request.composing.text).isEqualTo("teh")
        assertThat(request.composing.touches).containsExactly(null, null, null)
        assertThat(request.context.before).isEqualTo("say ")
        assertThat(request.autocorrectAllowed).isFalse()

        controller.onSuggestionTap(strip.centre!!)
        assertThat(text()).isEqualTo("say the")
        assertThat(editor.cursor).isEqualTo(7)
        assertThat(editor.composing).isEmpty()
        assertThat(controller.strip.centre?.kind).isNotEqualTo(SlotKind.Suggestion)
        val event = sink.of<WordCommitted>().single()
        assertThat(event.typed).isEqualTo("teh")
        assertThat(event.committed).isEqualTo("the")
        assertThat(event.method).isEqualTo(CommitMethod.SuggestionPicked)
        assertThat(event.pickedRank).isEqualTo(0)
    }

    @Test
    fun theCursorInsideTheWordWorksAndEndsAfterTheReplacement() {
        start()
        tapInto("say teh now", 5)
        assertThat(controller.strip.centre?.text).isEqualTo("the")
        assertThat(predictor.requests.last().context.after).isEqualTo(" now")
        controller.onSuggestionTap(controller.strip.left!!) // "ten"
        assertThat(text()).isEqualTo("say ten now")
        assertThat(editor.cursor).isEqualTo(7)
    }

    @Test
    fun aKnownWordShowsNothing() {
        start()
        tapInto("say the", 7)
        assertThat(controller.strip.isEmpty).isTrue()
        assertThat(predictor.requests.map { it.composing.text }).doesNotContain("the")
    }

    @Test
    fun aCursorOutsideAnyWordShowsNothing() {
        start()
        tapInto("say teh ", 8)
        assertThat(controller.strip.isEmpty).isTrue()
        tapInto("say teh", 4) // at the start of the word
        assertThat(controller.strip.isEmpty).isTrue()
    }

    @Test
    fun aSensitiveFieldIsNeverReadOrDecoded() {
        start(
            FieldProfile.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                0,
            )
        )
        editor.readCalls = 0
        tapInto("say teh", 7)
        assertThat(editor.readCalls).isEqualTo(0)
        assertThat(predictor.requests).isEmpty()
        assertThat(controller.strip.isEmpty).isTrue()
    }

    @Test
    fun aFieldWithoutSuggestionsShowsNothing() {
        start(
            FieldProfile.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                0,
            )
        )
        tapInto("say teh", 7)
        assertThat(predictor.requests).isEmpty()
        assertThat(controller.strip.isEmpty).isTrue()
    }

    @Test
    fun oneMoveReadsTheEditorOnlyTwice() {
        start()
        editor.setText("say teh", 7)
        editor.readCalls = 0
        editor.echo(controller)
        assertThat(editor.readCalls).isEqualTo(2) // before the cursor, after the cursor
    }

    @Test
    fun typingClearsTheCorrections() {
        start()
        tapInto("say teh", 7)
        assertThat(controller.strip.isEmpty).isFalse()
        controller.onKey(KeyAction.Space)
        assertThat(controller.strip.centre?.text).isNotEqualTo("the")
        assertThat(text()).isEqualTo("say teh ")
    }

    @Test
    fun backspaceClearsTheCorrectionsToo() {
        start()
        tapInto("say teh", 7)
        controller.onKey(KeyAction.Backspace)
        assertThat(text()).isEqualTo("say te")
        assertThat(controller.strip.centre?.text).isNotEqualTo("the")
    }

    @Test
    fun movingAwayClearsTheCorrections() {
        start()
        tapInto("say teh", 7)
        editor.moveCursor(0)
        editor.echo(controller)
        assertThat(controller.strip.isEmpty).isTrue()
    }

    @Test
    fun aWordChangedBehindOurBackIsNotReplaced() {
        start()
        tapInto("say teh", 7)
        val slot = controller.strip.centre!!
        editor.text.replace(4, 7, "xyz") // changed without a selection report
        controller.onSuggestionTap(slot)
        assertThat(text()).isEqualTo("say xyz")
    }

    @Test
    fun anUnknownOffsetReportShowsNothingAndDoesNotCrash() {
        start()
        editor.setText("say teh", 7)
        controller.onSelectionChanged() // all offsets unknown
        assertThat(controller.strip.isEmpty).isTrue()
        assertThat(text()).isEqualTo("say teh")
    }

    @Test
    fun aSelectionRangeShowsNothing() {
        start()
        editor.setText("say teh", 7)
        controller.onSelectionChanged(4, 7, -1, -1)
        assertThat(controller.strip.isEmpty).isTrue()
    }
}
