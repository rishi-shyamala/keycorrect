package dev.keycorrect.feature.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.engineapi.Candidate
import dev.keycorrect.core.engineapi.CandidateSource
import dev.keycorrect.core.engineapi.PredictResult
import dev.keycorrect.core.input.EnterAction
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.learning.WordCommitted
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.feature.keyboard.prediction.ShortcutRule
import org.junit.Test

/**
 * Enter as an editor action (Search, Send, Go...) hands the word in progress to the app as typed
 * ([KeyboardSettings.enterKeepsTyped]), so the app's own autocomplete can finish it.
 */
class EnterAsTypedTest {
    private val editor = SimEditor()
    private val backend = FakeBackend()
    private val predictor = ScriptedPredictor()
    private val played = mutableListOf<FeedbackKind>()
    private val sink = RecordingSink()
    private val controller: KeyboardController

    init {
        backend.predictor = predictor
        backend.known += listOf("the", "weather", "spot", "spotify")
        predictor.word("teh", listOf("the"), autocorrect = "the")
        predictor.word("spoti", listOf("spot", "spotify"), autocorrect = "spot")
        predictor.word("kubectl", emptyList())
        controller =
            KeyboardController(
                editor,
                KeyboardComposer(BuiltInLayouts.repository()),
                testEnv(backend),
                clock = { 10_000L },
                wordFeedback = { played += it },
                learning = sink,
            )
    }

    private val search = FieldProfile.from(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH)
    private val singleLine = FieldProfile.from(InputType.TYPE_CLASS_TEXT, 0)
    private val multiLine =
        FieldProfile.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, 0)

    private fun start(profile: FieldProfile, settings: KeyboardSettings = KeyboardSettings()) {
        editor.enterInsertsLineBreak =
            profile.multiLine && profile.enterAction == EnterAction.NewLine
        controller.onStartInput(profile, settings, false, null, editor.cursor, editor.cursor)
    }

    private fun type(text: String) = text.forEach { controller.onKey(KeyAction.Text("$it")) }

    private fun enter() = controller.onKey(KeyAction.Enter)

    private fun committedWords() = sink.of<WordCommitted>().map { it.committed }

    @Test
    fun aSearchKeepsThePartialWordAndIsSilent() {
        start(search)
        type("spoti")
        enter()
        assertThat(editor.text.toString()).isEqualTo("spoti")
        assertThat(editor.enters).isEqualTo(1)
        assertThat(played).isEmpty()
        // A likely partial word is not learned.
        assertThat(committedWords()).doesNotContain("spoti")
    }

    @Test
    fun spaceStillCorrectsEarlierWordsInASearch() {
        start(search)
        type("teh")
        controller.onKey(KeyAction.Space)
        type("weathe")
        enter()
        assertThat(editor.text.toString()).isEqualTo("the weathe")
        assertThat(played).containsExactly(FeedbackKind.Autocorrected)
    }

    @Test
    fun aKnownWordIsStillLearned() {
        start(search)
        type("weather")
        enter()
        assertThat(committedWords()).contains("weather")
    }

    @Test
    fun aCompleteNewWordBeforeSendIsStillLearned() {
        // Nothing completes it, so it is a word of its own (a name, a term), not a prefix.
        start(FieldProfile.from(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEND))
        type("kubectl")
        enter()
        assertThat(editor.text.toString()).isEqualTo("kubectl")
        assertThat(committedWords()).contains("kubectl")
        assertThat(played).isEmpty()
    }

    @Test
    fun anUndoneCorrectionIsStillLearnedOnEnter() {
        start(search)
        type("spoti")
        controller.onKey(KeyAction.Space) // corrected to "spot "
        controller.onKey(KeyAction.Backspace) // undo: "spoti" again
        enter()
        assertThat(editor.text.toString()).isEqualTo("spoti")
        assertThat(sink.of<dev.keycorrect.core.learning.AutocorrectReverted>()).isNotEmpty()
    }

    @Test
    fun aMultiLineFieldWithAnActionKeepsTheWord() {
        val profile =
            FieldProfile.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                EditorInfo.IME_ACTION_SEND,
            )
        start(profile)
        type("teh")
        enter()
        assertThat(editor.text.toString()).isEqualTo("teh")
    }

    @Test
    fun noBuzzOnEnterWithAutocorrectOff() {
        start(search, KeyboardSettings(autocorrectEnabled = false))
        type("zzqx")
        enter()
        assertThat(played).isEmpty()
    }

    @Test
    fun aSingleLineFieldWithoutAnActionAlsoKeepsTheWord() {
        // Enter there is a key event the app treats as submit, not a line break.
        start(singleLine)
        type("teh")
        enter()
        assertThat(editor.text.toString()).isEqualTo("teh")
        assertThat(played).isEmpty()
    }

    @Test
    fun aNewLineStillCorrects() {
        start(multiLine)
        type("teh")
        enter()
        assertThat(editor.text.toString()).startsWith("the")
        assertThat(played).containsExactly(FeedbackKind.Autocorrected)
    }

    @Test
    fun theSettingOffRestoresTheCorrection() {
        start(search, KeyboardSettings(enterKeepsTyped = false))
        type("spoti")
        enter()
        assertThat(editor.text.toString()).isEqualTo("spot")
        assertThat(played).containsExactly(FeedbackKind.Autocorrected)
    }

    @Test
    fun aShortcutStillExpands() {
        backend.shortcuts["omw"] = ShortcutRule("on my way", autoExpand = true)
        predictor.words["omw"] =
            PredictResult(
                listOf(Candidate("on my way", 0f, CandidateSource.Shortcut)),
                Candidate("omw", 0f, CandidateSource.Literal),
                null,
            )
        start(search)
        type("omw")
        enter()
        assertThat(editor.text.toString()).isEqualTo("on my way")
    }
}
