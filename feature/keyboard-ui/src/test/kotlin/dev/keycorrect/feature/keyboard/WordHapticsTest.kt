package dev.keycorrect.feature.keyboard

import android.text.InputType
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.feature.keyboard.prediction.ShortcutRule
import org.junit.Test

/** When the autocorrect and unknown-word vibrations fire, with a fake feedback sink. */
class WordHapticsTest {
    private val editor = SimEditor()
    private val backend = FakeBackend()
    private val predictor = ScriptedPredictor()
    private val played = mutableListOf<FeedbackKind>()
    private val controller: KeyboardController

    init {
        backend.predictor = predictor
        backend.known += listOf("the", "hello", "world")
        predictor.word("teh", listOf("the"), autocorrect = "the")
        controller =
            KeyboardController(
                editor,
                KeyboardComposer(BuiltInLayouts.repository()),
                testEnv(backend),
                clock = { 10_000L },
                wordFeedback = { played += it },
            )
    }

    private val textField = FieldProfile.from(InputType.TYPE_CLASS_TEXT, 0)

    private fun start(
        profile: FieldProfile = textField,
        settings: KeyboardSettings = KeyboardSettings(),
    ) = controller.onStartInput(profile, settings, false, null, editor.cursor, editor.cursor)

    private fun type(text: String) = text.forEach { controller.onKey(KeyAction.Text("$it")) }

    private fun space() = controller.onKey(KeyAction.Space)

    private fun backspace() = controller.onKey(KeyAction.Backspace)

    @Test
    fun anAutocorrectFiresOnceOnTheSeparatorNotPerKey() {
        start()
        type("teh")
        assertThat(played).isEmpty()
        space()
        assertThat(played).containsExactly(FeedbackKind.Autocorrected)
    }

    @Test
    fun anAutocorrectOnPunctuationOrEnterFires() {
        // A new line corrects; Enter as an action keeps the word (EnterAsTypedTest).
        editor.enterInsertsLineBreak = true
        start(
            FieldProfile.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, 0)
        )
        type("teh")
        controller.onKey(KeyAction.Text(","))
        type(" teh")
        controller.onKey(KeyAction.Enter)
        assertThat(played).containsExactly(FeedbackKind.Autocorrected, FeedbackKind.Autocorrected)
    }

    @Test
    fun anUnknownUncorrectedWordFires() {
        start()
        type("zzqx")
        assertThat(played).isEmpty()
        space()
        assertThat(played).containsExactly(FeedbackKind.UnknownWord)
    }

    @Test
    fun anUnknownWordStillFiresWhenAutocorrectIsOff() {
        start(settings = KeyboardSettings(autocorrectEnabled = false))
        type("teh")
        space()
        assertThat(played).containsExactly(FeedbackKind.UnknownWord)
    }

    @Test
    fun aKnownWordIsSilent() {
        start()
        type("hello")
        space()
        type("World") // case-insensitive
        space()
        assertThat(played).isEmpty()
    }

    @Test
    fun aUserDictionaryWordIsKnown() {
        backend.known += "zzqx"
        start()
        type("zzqx")
        space()
        assertThat(played).isEmpty()
    }

    @Test
    fun singleLettersAndWordsWithDigitsAreSilent() {
        start()
        type("q")
        space()
        type("zz9")
        space()
        type("ab1c")
        space()
        assertThat(played).isEmpty()
    }

    @Test
    fun wordsAfterSymbolsAndAddressesAreSilent() {
        start()
        type("zzqx")
        controller.onKey(KeyAction.Text("@")) // the start of an address
        type("zzqx")
        controller.onKey(KeyAction.Text("."))
        type("com")
        space()
        type("#zzqx")
        space()
        assertThat(played).isEmpty()
    }

    @Test
    fun aShortcutTriggerIsSilent() {
        backend.shortcuts["omw"] = ShortcutRule("on my way", autoExpand = true)
        start()
        type("omw")
        space()
        assertThat(played).isEmpty()
        backend.shortcuts["brb"] = ShortcutRule("be right back", autoExpand = false)
        type("brb")
        space()
        assertThat(played).isEmpty()
    }

    @Test
    fun aWordTheUserPutBackAfterAnUndoIsSilent() {
        start()
        type("teh")
        space()
        played.clear()
        backspace() // undo: composing again
        space() // kept as typed: no buzz about it
        assertThat(played).isEmpty()
    }

    @Test
    fun aLayerSwitchDoesNotFireBecauseNoSeparatorWasCommitted() {
        start()
        type("zzqx")
        controller.onKey(KeyAction.SwitchLayer("symbols"))
        assertThat(played).isEmpty()
    }

    @Test
    fun nothingFiresInSensitiveFields() {
        start(
            FieldProfile.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                0,
            )
        )
        type("teh")
        space()
        type("zzqx")
        space()
        assertThat(played).isEmpty()
    }

    @Test
    fun nothingFiresInEmailAndUrlFields() {
        start(
            FieldProfile.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                0,
            )
        )
        type("zzqx")
        space()
        start(FieldProfile.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, 0))
        type("zzqx")
        space()
        assertThat(played).isEmpty()
    }

    @Test
    fun nothingFiresWhereSuggestionsAreOff() {
        start(settings = KeyboardSettings(suggestionsEnabled = false))
        type("teh")
        space()
        type("zzqx")
        space()
        assertThat(played).isEmpty()
    }

    @Test
    fun nothingFiresWhileTheDictionaryIsNotLoaded() {
        backend.predictor = null
        start()
        type("zzqx")
        space()
        assertThat(played).isEmpty()
    }

    @Test
    fun hapticsOffSilencesBoth() {
        start(settings = KeyboardSettings(hapticsEnabled = false))
        type("teh")
        space()
        type("zzqx")
        space()
        assertThat(played).isEmpty()
    }

    @Test
    fun eachEventHasItsOwnSwitch() {
        start(settings = KeyboardSettings(hapticOnAutocorrect = false))
        type("teh")
        space()
        type("zzqx")
        space()
        assertThat(played).containsExactly(FeedbackKind.UnknownWord)

        played.clear()
        controller.updateSettings(
            KeyboardSettings(hapticOnAutocorrect = true, hapticOnUnknownWord = false)
        )
        type("teh")
        space()
        type("zzqx")
        space()
        assertThat(played).containsExactly(FeedbackKind.Autocorrected)
    }

    @Test
    fun hapticWantedFollowsTheSettings() {
        val all = KeyboardSettings()
        assertThat(hapticWanted(FeedbackKind.Autocorrected, all)).isTrue()
        assertThat(hapticWanted(FeedbackKind.UnknownWord, all)).isTrue()
        assertThat(hapticWanted(FeedbackKind.Standard, all)).isTrue()
        val off = all.copy(hapticsEnabled = false)
        FeedbackKind.entries.forEach { assertThat(hapticWanted(it, off)).isFalse() }
        assertThat(hapticWanted(FeedbackKind.Autocorrected, all.copy(hapticOnAutocorrect = false)))
            .isFalse()
        assertThat(hapticWanted(FeedbackKind.UnknownWord, all.copy(hapticOnUnknownWord = false)))
            .isFalse()
        assertThat(hapticWanted(FeedbackKind.UnknownWord, all.copy(hapticOnAutocorrect = false)))
            .isTrue()
    }

    @Test
    fun theUnknownWordBuzzIsThreePulsesDistinctFromTheDoubleTick() {
        val timings = unknownWordTimings(20)
        // off, on, off, on, off, on: three 20 ms pulses starting 68 ms apart.
        assertThat(timings.toList()).containsExactly(0L, 20L, 48L, 20L, 48L, 20L).inOrder()
        assertThat(timings.size / 2).isEqualTo(3)
        assertThat(autocorrectTimings(20).size / 2).isEqualTo(2)
    }

    @Test
    fun theAutocorrectDoublePulseUsesItsOwnLength() {
        assertThat(autocorrectTimings(20).toList()).containsExactly(0L, 20L, 43L, 20L).inOrder()
        assertThat(autocorrectTimings(60).toList()).containsExactly(0L, 60L, 43L, 60L).inOrder()
        assertThat(autocorrectTimings(5).toList()).containsExactly(0L, 5L, 43L, 5L).inOrder()
    }

    @Test
    fun theSettingsDefaultToTwentyMillisecondPulses() {
        val defaults = KeyboardSettings()
        assertThat(autocorrectTimings(defaults.autocorrectPulseMs)[1]).isEqualTo(20L)
        assertThat(unknownWordTimings(defaults.unknownWordPulseMs)[1]).isEqualTo(20L)
    }
}
