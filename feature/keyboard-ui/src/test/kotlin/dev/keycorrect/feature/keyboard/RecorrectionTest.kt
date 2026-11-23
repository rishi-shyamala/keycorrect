package dev.keycorrect.feature.keyboard

import android.text.InputType
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.engineapi.Candidate
import dev.keycorrect.core.engineapi.CandidateSource
import dev.keycorrect.core.engineapi.PredictResult
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

    private fun start(
        profile: FieldProfile = textField,
        settings: KeyboardSettings = KeyboardSettings(),
    ) = controller.onStartInput(profile, settings, false, null, 0, 0)

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
    fun aKnownWordShowsItsAlternativesAndNeverItself() {
        start()
        predictor.word("hello", listOf("hello", "help", "hell", "hallo"))
        tapInto("say hello", 9)
        val strip = controller.strip
        assertThat(strip.centre?.text).isEqualTo("help")
        assertThat(strip.left?.text).isEqualTo("hell")
        assertThat(strip.right?.text).isEqualTo("hallo")
        val shown = listOfNotNull(strip.left, strip.centre, strip.right).map { it.text }
        assertThat(shown).doesNotContain("hello")
        // One more than the strip holds, so three remain once the word itself is dropped.
        assertThat(predictor.requests.last().maxCandidates).isEqualTo(4)
        assertThat(predictor.requests.last().autocorrectAllowed).isFalse()
    }

    @Test
    fun aKnownWordWithFewAlternativesShowsOnlyThose() {
        start()
        predictor.word("hello", listOf("hello", "help"))
        tapInto("say hello", 9)
        assertThat(controller.strip.centre?.text).isEqualTo("help")
        assertThat(controller.strip.left).isNull()
    }

    @Test
    fun tappingAnAlternativeOfAKnownWordIsAContextReplacementNotASpellingFix() {
        start()
        predictor.word("hello", listOf("hello", "help", "hell"))
        tapInto("say hello", 9)
        controller.onSuggestionTap(controller.strip.centre!!)
        assertThat(text()).isEqualTo("say help")
        val event = sink.of<WordCommitted>().single()
        assertThat(event.typed).isEqualTo("hello")
        assertThat(event.committed).isEqualTo("help")
        assertThat(event.method).isEqualTo(CommitMethod.ContextReplaced)
        assertThat(event.context).containsExactly("say")
    }

    @Test
    fun theWordIsOutrankedWhenACandidateScoresAboveIt() {
        start()
        predictor.words["cat"] =
            PredictResult(
                candidates = listOf(Candidate("car", 1f, CandidateSource.Dictionary)),
                literal = Candidate("cat", -2f, CandidateSource.Literal),
                autocorrect = null,
            )
        tapInto("say cat", 7)
        assertThat(controller.recorrectWordOutranked).isTrue()
        predictor.words["hello"] =
            PredictResult(
                candidates = listOf(Candidate("help", -3f, CandidateSource.Dictionary)),
                literal = Candidate("hello", -1f, CandidateSource.Literal),
                autocorrect = null,
            )
        tapInto("say hello", 9)
        assertThat(controller.recorrectWordOutranked).isFalse()
    }

    @Test
    fun aKnownWordInAnEmailFieldShowsNothing() {
        start(
            FieldProfile.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                0,
            )
        )
        predictor.word("hello", listOf("hello", "help"))
        tapInto("say hello", 9)
        assertThat(controller.strip.isEmpty).isTrue()
        assertThat(predictor.requests.map { it.composing.text }).doesNotContain("hello")
    }

    @Test
    fun aCursorOutsideAnyWordShowsNothing() {
        start()
        tapInto("say teh ", 8)
        assertThat(controller.strip.isEmpty).isTrue()
        tapInto("say  teh", 4) // between two spaces
        assertThat(controller.strip.isEmpty).isTrue()
    }

    @Test
    fun aCursorAtTheStartOfATypoShowsCorrectionsAndTheTapReplacesIt() {
        start()
        tapInto("say teh now", 4)
        assertThat(controller.strip.centre?.text).isEqualTo("the")
        val request = predictor.requests.last()
        assertThat(request.composing.text).isEqualTo("teh")
        assertThat(request.context.before).isEqualTo("say ")
        assertThat(request.context.after).isEqualTo(" now")
        controller.onSuggestionTap(controller.strip.centre!!)
        assertThat(text()).isEqualTo("say the now")
        assertThat(editor.cursor).isEqualTo(7)
    }

    @Test
    fun aTypoAtTheStartOfTheFieldOrAfterAnOpeningBracketIsFoundToo() {
        start()
        tapInto("teh end", 7)
        editor.moveCursor(0) // the tap moves the cursor to the start of the field
        editor.echo(controller)
        assertThat(controller.strip.centre?.text).isEqualTo("the")
        controller.onSuggestionTap(controller.strip.centre!!)
        assertThat(text()).isEqualTo("the end")
        tapInto("say (teh)", 5)
        assertThat(controller.strip.centre?.text).isEqualTo("the")
    }

    @Test
    fun aCursorAfterADigitOrSymbolReadsNothingAfterIt() {
        start()
        editor.setText("say 4teh", 5)
        editor.readCalls = 0
        editor.echo(controller)
        assertThat(editor.readCalls).isEqualTo(1) // before the cursor only
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
            ),
            KeyboardSettings(ignoreNoSuggestions = false),
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
