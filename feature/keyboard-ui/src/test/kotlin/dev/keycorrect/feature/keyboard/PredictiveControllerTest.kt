package dev.keycorrect.feature.keyboard

import android.text.InputType
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.engineapi.Candidate
import dev.keycorrect.core.engineapi.CandidateSource
import dev.keycorrect.core.engineapi.PredictResult
import dev.keycorrect.core.engineapi.TouchPoint
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.feature.keyboard.prediction.CommitMethod
import dev.keycorrect.feature.keyboard.prediction.ShortcutExpander
import dev.keycorrect.feature.keyboard.prediction.ShortcutRule
import dev.keycorrect.feature.keyboard.prediction.SlotKind
import org.junit.Test

/**
 * The composing / autocorrect / suggestion behaviour of [KeyboardController] with a scripted
 * engine.
 */
class PredictiveControllerTest {
    private val editor = SimEditor()
    private val backend = FakeBackend()
    private val predictor = ScriptedPredictor()
    private val scheduler = ManualScheduler()
    private var now = 10_000L
    private var controller = build()

    private fun build(
        executor: java.util.concurrent.Executor = java.util.concurrent.Executor { it.run() },
        expander: ShortcutExpander = ShortcutExpander(),
    ): KeyboardController {
        backend.predictor = predictor
        return KeyboardController(
            editor,
            KeyboardComposer(BuiltInLayouts.repository()),
            testEnv(backend, executor, scheduler, expander),
            clock = { now },
        )
    }

    private val textField = FieldProfile.from(InputType.TYPE_CLASS_TEXT, 0)

    private fun start(
        profile: FieldProfile = textField,
        settings: KeyboardSettings = KeyboardSettings(),
    ) = controller.onStartInput(profile, settings)

    private fun type(text: String) = text.forEach { controller.onKey(KeyAction.Text("$it")) }

    private fun space() = controller.onKey(KeyAction.Space)

    private fun backspace() = controller.onKey(KeyAction.Backspace)

    private fun text() = editor.text.toString()

    // ------------------------------------------------------------------ composing

    @Test
    fun lettersAreComposedAndSuggestionsShown() {
        predictor.word("hel", listOf("help", "hello", "held"))
        start()
        type("hel")
        assertThat(text()).isEqualTo("hel")
        assertThat(editor.composing).isEqualTo("hel")
        val strip = controller.strip
        assertThat(strip.centre?.text).isEqualTo("help")
        assertThat(strip.left?.text).isEqualTo("hello")
        assertThat(strip.right?.text).isEqualTo("held")
        assertThat(strip.centre?.description).isEqualTo("Suggestion: help")
    }

    @Test
    fun touchesAndTheLayoutIdReachTheDecoder() {
        start()
        controller.onKey(KeyAction.Text("h"), TouchPoint(10f, 20f, 5))
        controller.onKey(KeyAction.Text("e"), null)
        val request = predictor.requests.last()
        assertThat(request.composing.touches)
            .containsExactly(TouchPoint(10f, 20f, 5), null)
            .inOrder()
        assertThat(request.maxCandidates).isEqualTo(3)
        assertThat(request.maxPhraseWords).isEqualTo(4)
    }

    @Test
    fun apostrophesAndDigitsContinueAWordButDoNotStartOne() {
        start()
        type("don't")
        assertThat(editor.composing).isEqualTo("don't")
        space()
        type("4x")
        assertThat(text()).isEqualTo("don't 4x")
        assertThat(editor.composing).isEqualTo("x")
    }

    @Test
    fun noComposingWhileThereIsNoEngine() {
        backend.predictor = null
        start()
        type("hello")
        assertThat(text()).isEqualTo("hello")
        assertThat(editor.composeCalls).isEqualTo(0)
        assertThat(editor.readCalls).isEqualTo(0)
    }

    @Test
    fun noComposingInTheMiddleOfAWord() {
        start()
        editor.setText("wrld", at = 1)
        controller.onStartInput(textField, KeyboardSettings())
        type("o")
        assertThat(editor.composeCalls).isEqualTo(0)
        assertThat(text()).isEqualTo("world")
    }

    @Test
    fun suggestionsSwitchedOffInSettingsMeansPlainCommits() {
        start(settings = KeyboardSettings(suggestionsEnabled = false))
        type("teh")
        space()
        assertThat(text()).isEqualTo("teh ")
        assertThat(editor.composeCalls).isEqualTo(0)
        assertThat(predictor.requests).isEmpty()
    }

    // ------------------------------------------------------------------ sensitive fields

    @Test
    fun passwordFieldsNeverComposeReadOrDecode() {
        start(
            FieldProfile.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                0,
            )
        )
        type("hunter2")
        space()
        backspace()
        controller.onKey(KeyAction.Enter)
        assertThat(editor.composeCalls).isEqualTo(0)
        assertThat(editor.readCalls).isEqualTo(0)
        assertThat(predictor.requests).isEmpty()
        assertThat(controller.strip.isEmpty).isTrue()
        assertThat(controller.touchLog.size).isEqualTo(0)
        assertThat(editor.deletes).containsExactly(true)
    }

    @Test
    fun theGateIsTheSensitiveFlagAloneEvenIfEverythingElseSaysYes() {
        val forced =
            FieldProfile.from(InputType.TYPE_CLASS_TEXT, 0)
                .copy(sensitive = true, suggestionsEnabled = true, autocorrectEnabled = true)
        start(forced, KeyboardSettings(suggestionsEnabled = true, autocorrectEnabled = true))
        controller.onKey(KeyAction.Text("a"), TouchPoint(1f, 1f, 1))
        type("bc")
        assertThat(editor.composeCalls).isEqualTo(0)
        assertThat(editor.readCalls).isEqualTo(0)
        assertThat(predictor.requests).isEmpty()
        assertThat(controller.touchLog.size).isEqualTo(0)
    }

    // ------------------------------------------------------------------ autocorrect

    @Test
    fun autocorrectIsAppliedOnSpaceAndPunctuation() {
        predictor.word("teh", listOf("the"), autocorrect = "the")
        start()
        type("teh")
        assertThat(controller.strip.centre?.text).isEqualTo("the")
        assertThat(controller.strip.centre?.bold).isTrue()
        assertThat(controller.strip.centre?.description).isEqualTo("Autocorrect to the")
        assertThat(controller.strip.left?.text).isEqualTo("teh")
        space()
        assertThat(text()).isEqualTo("the ")
        assertThat(editor.composing).isEmpty()
        assertThat(controller.strip.isEmpty).isTrue()

        type("teh")
        controller.onKey(KeyAction.Text(","))
        assertThat(text()).isEqualTo("the the,")
    }

    @Test
    fun enterAppliesTheAutocorrectThenPerformsEnter() {
        predictor.word("teh", listOf("the"), autocorrect = "the")
        start()
        type("teh")
        controller.onKey(KeyAction.Enter)
        assertThat(text()).isEqualTo("the")
        assertThat(editor.enters).isEqualTo(1)
    }

    @Test
    fun oneBackspaceUndoesAutocorrectKeepingTheSeparator() {
        predictor.word("teh", listOf("the"), autocorrect = "the")
        start()
        type("teh")
        space()
        assertThat(text()).isEqualTo("the ")
        backspace()
        assertThat(text()).isEqualTo("teh ")
        assertThat(controller.touchLog.snapshot().last().reverted).isTrue()
        // The word is not corrected again: backspace over the space re-composes it, then space.
        backspace()
        assertThat(editor.composing).isEqualTo("teh")
        space()
        assertThat(text()).isEqualTo("teh ")
    }

    @Test
    fun anyOtherKeyClosesTheUndoWindow() {
        predictor.word("teh", listOf("the"), autocorrect = "the")
        start()
        type("teh")
        space()
        type("a")
        backspace() // removes the "a" from the composing word
        backspace() // plain delete of the space: no undo any more
        assertThat(text()).isEqualTo("the")
        assertThat(editor.composing).isEqualTo("the")
    }

    @Test
    fun noAutocorrectWhenTheSettingIsOff() {
        predictor.word("teh", listOf("the"), autocorrect = "the")
        start(settings = KeyboardSettings(autocorrectEnabled = false))
        type("teh")
        assertThat(predictor.requests.last().autocorrectAllowed).isFalse()
        assertThat(controller.strip.centre?.bold).isFalse()
        space()
        assertThat(text()).isEqualTo("teh ")
    }

    @Test
    fun noAutocorrectInFieldsThatForbidIt() {
        predictor.word("teh", listOf("the"), autocorrect = "the")
        start(
            FieldProfile.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                0,
            )
        )
        type("teh")
        space()
        assertThat(text()).isEqualTo("teh ")
    }

    // ------------------------------------------------------------------ cursor jumps

    @Test
    fun anUnexpectedCursorMoveFinishesTheWordAsTyped() {
        predictor.word("hel", listOf("help"))
        start()
        type("hel")
        now += 1_000 // the keyboard's own pending edits have long expired
        editor.moveCursor(0)
        controller.onSelectionChanged(true, true, cursor = 0, composingStart = 0, composingEnd = 3)
        assertThat(editor.composing).isEmpty()
        assertThat(text()).isEqualTo("hel")
        assertThat(controller.strip.isEmpty).isTrue()
    }

    @Test
    fun theEchoOfOurOwnComposingEditIsNotAJump() {
        start()
        type("hel")
        now += 1_000
        editor.echo(controller)
        assertThat(editor.composing).isEqualTo("hel")
    }

    @Test
    fun backspacingIntoAWordComposesItAgain() {
        predictor.word("hello", listOf("hello", "help"))
        start()
        type("hello")
        space()
        assertThat(editor.composing).isEmpty()
        backspace()
        assertThat(text()).isEqualTo("hello")
        assertThat(editor.composing).isEqualTo("hello")
        assertThat(predictor.requests.last().composing.text).isEqualTo("hello")
        backspace()
        assertThat(editor.composing).isEqualTo("hell")
    }

    @Test
    fun leavingTheFieldFinishesTheWord() {
        start()
        type("hel")
        controller.onFinishInput()
        assertThat(editor.composing).isEmpty()
        assertThat(text()).isEqualTo("hel")
    }

    // ------------------------------------------------------------------ threading

    @Test
    fun staleResultsAreDropped() {
        val executor = ManualExecutor()
        controller = build(executor)
        predictor.word("t", listOf("to"))
        predictor.word("te", listOf("tea"))
        start()
        type("te")
        assertThat(executor.queue).hasSize(2)
        executor.runAll()
        // The decode for "t" never ran (it was superseded) and only "te"'s answer arrived.
        assertThat(predictor.requests.map { it.composing.text }).containsExactly("te")
        assertThat(controller.strip.centre?.text).isEqualTo("tea")
    }

    @Test
    fun aResultForOldInputIsNeverShown() {
        val executor = ManualExecutor()
        controller = build(executor)
        predictor.word("te", listOf("tea"))
        start()
        type("te")
        backspace()
        backspace() // composing is empty again: a new sequence
        executor.runAll()
        assertThat(controller.strip.centre?.text).isNotEqualTo("tea")
    }

    @Test
    fun aSeparatorDecodesSynchronouslyWhenNoFreshResultIsThere() {
        val executor = ManualExecutor()
        controller = build(executor)
        predictor.word("teh", listOf("the"), autocorrect = "the")
        start()
        type("teh") // nothing has been decoded yet
        space()
        assertThat(text()).isEqualTo("the ")
        assertThat(predictor.requests.map { it.composing.text }).contains("teh")
    }

    // ------------------------------------------------------------------ strip taps

    @Test
    fun tappingASuggestionReplacesTheWordAndAddsASpace() {
        predictor.word("hel", listOf("help", "hello"))
        start()
        type("hel")
        controller.onSuggestionTap(controller.strip.centre!!)
        assertThat(text()).isEqualTo("help ")
        assertThat(editor.composing).isEmpty()
        assertThat(controller.touchLog.snapshot().last().method)
            .isEqualTo(CommitMethod.SuggestionTap)
    }

    @Test
    fun noSpaceAfterAPickWhenTheSettingIsOff() {
        predictor.word("hel", listOf("help"))
        start(settings = KeyboardSettings(autoSpaceAfterPick = false))
        type("hel")
        controller.onSuggestionTap(controller.strip.centre!!)
        assertThat(text()).isEqualTo("help")
    }

    @Test
    fun nextWordPredictionsAppearAfterASpaceAndAreCommittedOnTap() {
        predictor.nextWords = listOf("world", "there")
        start()
        type("hello")
        space()
        assertThat(controller.strip.centre?.text).isEqualTo("world")
        assertThat(predictor.requests.last().composing.text).isEmpty()
        assertThat(predictor.requests.last().context.before).isEqualTo("hello ")
        controller.onSuggestionTap(controller.strip.centre!!)
        assertThat(text()).isEqualTo("hello world ")
        assertThat(editor.composeCalls).isEqualTo(5) // only the typed word was ever composed
    }

    @Test
    fun noNextWordPredictionsWhenSwitchedOff() {
        predictor.nextWords = listOf("world")
        start(settings = KeyboardSettings(nextWordPredictions = false))
        type("hello")
        space()
        assertThat(controller.strip.isEmpty).isTrue()
    }

    @Test
    fun phrasesAreInsertedWhole() {
        predictor.word("how", listOf("how", "hot", "house"), phrase = "how are you")
        start()
        type("how")
        val phrase = controller.strip.right!!
        assertThat(phrase.kind).isEqualTo(SlotKind.Phrase)
        assertThat(phrase.text).isEqualTo("how are you")
        controller.onSuggestionTap(phrase)
        assertThat(text()).isEqualTo("how are you ")
    }

    @Test
    fun noPhraseRequestedWhenPhraseCompletionIsOff() {
        start(settings = KeyboardSettings(phraseCompletion = false))
        type("how")
        assertThat(predictor.requests.last().maxPhraseWords).isEqualTo(0)
    }

    @Test
    fun tappingTheLiteralKeepsTheWordAndOffersToAddIt() {
        predictor.word("zzq", listOf("zap"), autocorrect = "zap")
        start()
        type("zzq")
        val literal = controller.strip.left!!
        assertThat(literal.kind).isEqualTo(SlotKind.Literal)
        controller.onSuggestionTap(literal)
        assertThat(text()).isEqualTo("zzq ")
        assertThat(controller.strip.addWord).isEqualTo("zzq")
        assertThat(scheduler.pending.single().delayMs).isEqualTo(3_000L)
        controller.onAddWordTap()
        assertThat(backend.added).containsExactly("zzq")
        assertThat(controller.strip.addWord).isNull()
        // And it is not corrected when typed again.
        backspace()
        space()
        assertThat(text()).isEqualTo("zzq ")
    }

    @Test
    fun theAddWordChipGoesAwayByItselfAndWithTheNextKey() {
        predictor.word("zzq", listOf("zap"), autocorrect = "zap")
        start()
        type("zzq")
        controller.onSuggestionTap(controller.strip.left!!)
        scheduler.fireAll()
        assertThat(controller.strip.addWord).isNull()

        predictor.word("qqz", listOf("quiz"), autocorrect = "quiz")
        type("qqz")
        controller.onSuggestionTap(controller.strip.left!!)
        assertThat(controller.strip.addWord).isNotNull()
        type("a")
        assertThat(controller.strip.addWord).isNull()
    }

    @Test
    fun noChipForAKnownWord() {
        backend.known += "hello"
        predictor.word("hello", listOf("hello"), autocorrect = "help")
        start()
        type("hello")
        controller.onSuggestionTap(controller.strip.left!!)
        assertThat(text()).isEqualTo("hello ")
        assertThat(controller.strip.addWord).isNull()
    }

    // ------------------------------------------------------------------ shortcuts

    private fun shortcut(trigger: String, expansion: String, auto: Boolean = true) {
        backend.shortcuts[trigger] = ShortcutRule(expansion, auto)
        predictor.words[trigger] =
            PredictResult(
                listOf(Candidate(expansion, 0f, CandidateSource.Shortcut)),
                Candidate(trigger, 0f, CandidateSource.Literal),
                null,
            )
    }

    @Test
    fun anAutoExpandShortcutExpandsOnSpaceAndCanBeUndone() {
        shortcut("omw", "on my way")
        start()
        type("omw")
        space()
        assertThat(text()).isEqualTo("on my way ")
        backspace()
        assertThat(text()).isEqualTo("omw ")
    }

    @Test
    fun aSuggestOnlyShortcutIsNotExpandedAutomatically() {
        shortcut("omw", "on my way", auto = false)
        start()
        type("omw")
        space()
        assertThat(text()).isEqualTo("omw ")
    }

    @Test
    fun expansionsResolveDateTimeAndLeaveClipboardAlone() {
        controller =
            build(
                expander =
                    ShortcutExpander(
                        now = { 0L },
                        formatDate = { "9 Oct" },
                        formatTime = { "14:05" },
                    )
            )
        shortcut("dt", "{date} at {time} {clipboard}")
        start()
        type("dt")
        space()
        assertThat(text()).isEqualTo("9 Oct at 14:05 {clipboard} ")
    }

    @Test
    fun cursorMarkerPutsTheCursorInsideTheExpansion() {
        shortcut("sig", "Best,{cursor} Rishi")
        start()
        type("sig")
        space()
        assertThat(text()).isEqualTo("Best, Rishi")
        assertThat(editor.cursor).isEqualTo("Best,".length)
    }

    // ------------------------------------------------------------------ touch log

    @Test
    fun committedWordsAreLoggedWithTheirTouches() {
        start()
        controller.onKey(KeyAction.Text("h"), TouchPoint(1f, 2f, 3))
        controller.onKey(KeyAction.Text("i"), TouchPoint(4f, 5f, 6))
        space()
        val record = controller.touchLog.snapshot().single()
        assertThat(record.typed).isEqualTo("hi")
        assertThat(record.touches).hasSize(2)
        assertThat(record.method).isEqualTo(CommitMethod.Typed)
    }

    @Test
    fun nothingIsLoggedWhereLearningIsOff() {
        start(
            FieldProfile.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                0,
            )
        )
        type("hi")
        space()
        assertThat(controller.touchLog.size).isEqualTo(0)
    }
}
