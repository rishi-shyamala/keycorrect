package dev.keycorrect.feature.keyboard

import android.text.InputType
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.engineapi.FieldClass
import dev.keycorrect.core.engineapi.TouchPoint
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.layout.KeyboardGeometry
import dev.keycorrect.core.learning.AutocorrectReverted
import dev.keycorrect.core.learning.CommitMethod
import dev.keycorrect.core.learning.DeviceClass
import dev.keycorrect.core.learning.LearningEvent
import dev.keycorrect.core.learning.LearningSink
import dev.keycorrect.core.learning.PhraseOutcome
import dev.keycorrect.core.learning.RevertKind
import dev.keycorrect.core.learning.UserAction
import dev.keycorrect.core.learning.WordCommitted
import dev.keycorrect.core.learning.WordReplaced
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.feature.keyboard.prediction.SlotKind
import org.junit.Test

/** A sink that remembers everything it is given. */
internal class RecordingSink : LearningSink {
    data class SentenceWord(val sessionId: Long, val word: String, val end: Boolean)

    val events = mutableListOf<LearningEvent>()
    val sentence = mutableListOf<SentenceWord>()

    override fun onEvent(event: LearningEvent) {
        events += event
    }

    override fun onSentenceWord(
        sessionId: Long,
        word: String,
        sentenceEnd: Boolean,
        timeMillis: Long,
    ) {
        sentence += SentenceWord(sessionId, word, sentenceEnd)
    }

    inline fun <reified T : LearningEvent> of(): List<T> = events.filterIsInstance<T>()

    val isSilent: Boolean
        get() = events.isEmpty() && sentence.isEmpty()
}

/**
 * The learning events [KeyboardController] emits (docs/PLAN.md §6.2-6.3), and their privacy gate.
 */
class LearningEventsTest {
    private val editor = SimEditor()
    private val backend = FakeBackend()
    private val predictor = ScriptedPredictor()
    private val scheduler = ManualScheduler()
    private val sink = RecordingSink()
    private var now = 10_000L
    private var wall = 1_700_000_000_000L
    private val controller: KeyboardController

    init {
        backend.predictor = predictor
        backend.known +=
            listOf("the", "hello", "world", "going", "to", "cat", "cats", "test", "set")
        controller =
            KeyboardController(
                editor,
                KeyboardComposer(BuiltInLayouts.repository()),
                testEnv(backend, scheduler = scheduler),
                clock = { now },
                learning = sink,
                wallClock = { wall },
            )
    }

    private val textField = FieldProfile.from(InputType.TYPE_CLASS_TEXT, 0)

    private fun start(
        profile: FieldProfile = textField,
        settings: KeyboardSettings = KeyboardSettings(),
        newField: Boolean = true,
    ) {
        controller.onStartInput(
            profile,
            settings,
            restarting = !newField,
            identity = EditorIdentity(1, "pkg", 1, 0, null),
            initialSelStart = editor.cursor,
            initialSelEnd = editor.cursor,
        )
    }

    private fun type(text: String) = text.forEach { controller.onKey(KeyAction.Text("$it")) }

    private fun space() = controller.onKey(KeyAction.Space)

    private fun backspace() = controller.onKey(KeyAction.Backspace)

    private fun text() = editor.text.toString()

    private fun tap(slot: dev.keycorrect.feature.keyboard.prediction.StripSlot?) =
        controller.onSuggestionTap(slot!!)

    private fun committed() = sink.of<WordCommitted>()

    private fun reverts() = sink.of<AutocorrectReverted>()

    // ------------------------------------------------------------------ the gate

    private fun typeSomeWords() {
        predictor.word("teh", listOf("the"), autocorrect = "the")
        type("hello teh")
        space()
        type("world.")
        space()
        backspace()
        backspace()
        type("x ")
    }

    @Test
    fun anOpenGateEmitsEverything() {
        start()
        typeSomeWords()
        assertThat(committed()).isNotEmpty()
        assertThat(sink.sentence).isNotEmpty()
    }

    @Test
    fun aSensitiveFieldEmitsNothing() {
        start(
            FieldProfile.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                0,
            )
        )
        typeSomeWords()
        assertThat(sink.isSilent).isTrue()
    }

    @Test
    fun aNoLearningFieldEmitsNothing() {
        start(textField.copy(learningEnabled = false))
        typeSomeWords()
        assertThat(sink.isSilent).isTrue()
    }

    @Test
    fun aNoSuggestionsFieldEmitsNothing() {
        start(
            FieldProfile.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                0,
            )
        )
        typeSomeWords()
        assertThat(sink.isSilent).isTrue()
    }

    @Test
    fun incognitoEmitsNothing() {
        start(settings = KeyboardSettings(incognito = true))
        typeSomeWords()
        assertThat(sink.isSilent).isTrue()
    }

    @Test
    fun learningOffEmitsNothing() {
        start(settings = KeyboardSettings(learningEnabled = false))
        typeSomeWords()
        assertThat(sink.isSilent).isTrue()
    }

    @Test
    fun suggestionsOffEmitsNothing() {
        start(settings = KeyboardSettings(suggestionsEnabled = false))
        typeSomeWords()
        assertThat(sink.isSilent).isTrue()
    }

    @Test
    fun theSentenceBufferSwitchOnlyStopsSentenceWords() {
        start(settings = KeyboardSettings(sentenceBufferEnabled = false))
        typeSomeWords()
        assertThat(committed()).isNotEmpty()
        assertThat(sink.sentence).isEmpty()
    }

    @Test
    fun turningIncognitoOnMidFieldStopsEverythingAtOnce() {
        start()
        type("hello")
        space()
        val before = sink.events.size
        controller.updateSettings(KeyboardSettings(incognito = true))
        type("world")
        space()
        assertThat(sink.events.size).isEqualTo(before)
        val words = sink.sentence.size
        type("more")
        space()
        assertThat(sink.sentence.size).isEqualTo(words)
    }

    @Test
    fun aWordBeingTypedWhenIncognitoTurnsOnIsNotReported() {
        start()
        type("hel")
        controller.updateSettings(KeyboardSettings(incognito = true))
        type("lo")
        space()
        assertThat(sink.isSilent || committed().none { it.committed == "hello" }).isTrue()
        assertThat(sink.sentence.none { it.word == "hello" }).isTrue()
    }

    @Test
    fun nothingRememberedWhileClosedIsReportedWhenItReopens() {
        start(settings = KeyboardSettings(incognito = true))
        predictor.word("teh", listOf("the"), autocorrect = "the")
        type("teh")
        space()
        controller.updateSettings(KeyboardSettings())
        backspace() // the undo window of a correction made while closed
        assertThat(reverts()).isEmpty()
    }

    @Test
    fun nothingIsEmittedFromSensitiveFieldsEvenAfterAnOpenField() {
        start()
        type("hello")
        space()
        val n = sink.events.size
        controller.onFinishInput()
        start(
            FieldProfile.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                0,
            )
        )
        type("secret")
        space()
        assertThat(sink.events.size).isEqualTo(n)
    }

    // ------------------------------------------------------------------ WordCommitted

    @Test
    fun aTypedWordIsReportedWithItsContext() {
        start()
        type("hello")
        space()
        val e = committed().single()
        assertThat(e.method).isEqualTo(CommitMethod.Typed)
        assertThat(e.typed).isEqualTo("hello")
        assertThat(e.committed).isEqualTo("hello")
        assertThat(e.context).isEmpty()
        assertThat(e.sentenceInitial).isTrue()
        assertThat(e.committedIsKnown).isTrue()
        assertThat(e.typedIsKnown).isTrue()
        assertThat(e.sessionId).isNotEqualTo(0L)
        assertThat(e.timeMillis).isEqualTo(wall)
        assertThat(e.profile.field).isEqualTo(FieldClass.Text)
    }

    @Test
    fun anUnknownTypedWordIsFlaggedAsUnknown() {
        start()
        type("zorp")
        space()
        val e = committed().single()
        assertThat(e.committedIsKnown).isFalse()
        assertThat(e.typedIsKnown).isFalse()
    }

    @Test
    fun contextIsTheLastTwoWordsOfTheSentence() {
        start()
        type("alpha beta gamma")
        space()
        type("delta")
        space()
        val e = committed().last()
        assertThat(e.committed).isEqualTo("delta")
        assertThat(e.context).containsExactly("beta", "gamma").inOrder()
        assertThat(e.sentenceInitial).isFalse()
    }

    @Test
    fun contextStopsAtASentenceBoundary() {
        start()
        type("alpha beta.")
        space()
        type("gamma")
        space()
        val e = committed().last()
        assertThat(e.committed).isEqualTo("gamma")
        assertThat(e.context).isEmpty()
        assertThat(e.sentenceInitial).isTrue()
        type("delta")
        space()
        assertThat(committed().last().context).containsExactly("gamma")
    }

    @Test
    fun contextStopsAtANewline() {
        editor.enterInsertsLineBreak = true
        start(
            FieldProfile.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, 0)
        )
        type("alpha beta")
        controller.onKey(KeyAction.Enter)
        type("gamma")
        space()
        val e = committed().last()
        assertThat(e.committed).isEqualTo("gamma")
        assertThat(e.context).isEmpty()
    }

    @Test
    fun anAutocorrectIsReportedOnceItsUndoWindowCloses() {
        predictor.word("teh", listOf("the"), autocorrect = "the")
        start()
        type("teh")
        space()
        assertThat(committed()).isEmpty() // the undo window is still open
        type("w")
        val e = committed().single()
        assertThat(e.method).isEqualTo(CommitMethod.Autocorrected)
        assertThat(e.typed).isEqualTo("teh")
        assertThat(e.committed).isEqualTo("the")
        assertThat(e.typedIsKnown).isFalse()
        assertThat(e.committedIsKnown).isTrue()
        assertThat(e.tieRunnerUp).isNull()
        assertThat(reverts()).isEmpty()
    }

    @Test
    fun aTieAutocorrectCarriesTheRunnerUp() {
        predictor.word("tset", listOf("test", "set"), autocorrect = "test", runnerUp = "set")
        start()
        type("tset")
        space()
        type("x")
        assertThat(committed().single().tieRunnerUp).isEqualTo("set")
    }

    @Test
    fun shownSuggestionsAreReportedWithRankAndTiming() {
        predictor.word("he", listOf("hello", "help", "held"))
        predictor.word("hel", listOf("help", "hello", "held"))
        start()
        type("he")
        now += 400
        type("l")
        now += 400
        type("lo")
        space()
        val e = committed().single()
        val hello = e.shown.first { it.text == "hello" }
        assertThat(hello.shownAtChars).isEqualTo(2)
        assertThat(hello.visibleMillis).isAtLeast(800)
        val held = e.shown.first { it.text == "held" }
        assertThat(held.rank).isEqualTo(2)
    }

    @Test
    fun aTappedSuggestionIsReportedWithItsRank() {
        predictor.word("hel", listOf("help", "hello", "held"))
        start()
        type("hel")
        tap(controller.strip.left) // "hello"
        val e = committed().single()
        assertThat(e.method).isEqualTo(CommitMethod.SuggestionPicked)
        assertThat(e.typed).isEqualTo("hel")
        assertThat(e.committed).isEqualTo("hello")
        assertThat(e.pickedRank).isEqualTo(1)
    }

    @Test
    fun aTappedPredictionHasNoTypedText() {
        predictor.nextWords = listOf("the", "to", "a")
        start()
        type("going")
        space()
        tap(controller.strip.centre)
        val e = committed().last()
        assertThat(e.method).isEqualTo(CommitMethod.PredictionPicked)
        assertThat(e.typed).isEmpty()
        assertThat(e.committed).isEqualTo("the")
        assertThat(e.context).containsExactly("going")
        assertThat(e.pickedRank).isEqualTo(0)
    }

    @Test
    fun aTappedLiteralIsReported() {
        predictor.word("zorp", listOf("zap"), autocorrect = "zap")
        start()
        type("zorp")
        tap(controller.strip.left) // the literal
        val e = committed().single()
        assertThat(e.method).isEqualTo(CommitMethod.LiteralPicked)
        assertThat(e.committed).isEqualTo("zorp")
    }

    @Test
    fun aShortcutExpansionIsNeverReported() {
        backend.shortcuts["omw"] =
            dev.keycorrect.feature.keyboard.prediction.ShortcutRule("on my way", autoExpand = true)
        predictor.words["omw"] =
            dev.keycorrect.core.engineapi.PredictResult(
                emptyList(),
                lit("omw"),
                dev.keycorrect.core.engineapi.Candidate(
                    "on my way",
                    0f,
                    dev.keycorrect.core.engineapi.CandidateSource.Shortcut,
                ),
            )
        start()
        type("omw")
        space()
        assertThat(text()).isEqualTo("on my way ")
        assertThat(sink.events).isEmpty()
        assertThat(sink.sentence).isEmpty()
    }

    // ------------------------------------------------------------------ touches

    private fun keyCentre(c: Char): Pair<Float, Float> {
        val slot =
            controller.layer
                .let { KeyboardGeometry(it, GEO_W, GEO_H) }
                .slots
                .first {
                    (it.key.action as? KeyAction.Text)?.text == c.toString()
                }
        return slot.centerX to slot.centerY
    }

    private fun keyWidth(c: Char): Pair<Float, Float> {
        val slot =
            KeyboardGeometry(controller.layer, GEO_W, GEO_H).slots.first {
                (it.key.action as? KeyAction.Text)?.text == c.toString()
            }
        return (slot.right - slot.left) to (slot.bottom - slot.top)
    }

    private fun tapKey(c: Char, onChar: Char = c, dxKeys: Float = 0f, dyKeys: Float = 0f) {
        val (cx, cy) = keyCentre(onChar)
        val (w, h) = keyWidth(onChar)
        controller.onKey(
            KeyAction.Text("$c"),
            TouchPoint(cx + dxKeys * w, cy + dyKeys * h, now),
        )
    }

    @Test
    fun touchesAreReportedInKeyUnitsFromTheKeyOfTheCommittedChar() {
        start()
        controller.onGeometry(KeyboardGeometry(controller.layer, GEO_W, GEO_H))
        predictor.word("tex", listOf("the"), autocorrect = "tex")
        tapKey('c', dxKeys = 0.25f, dyKeys = -0.1f)
        tapKey('a', dxKeys = -0.3f)
        tapKey('t', dxKeys = 0.1f, dyKeys = 0.2f)
        space()
        val e = committed().single()
        assertThat(e.touches).hasSize(3)
        val c = e.touches[0]!!
        assertThat(c.char).isEqualTo('c')
        assertThat(c.dx).isWithin(0.01f).of(0.25f)
        assertThat(c.dy).isWithin(0.01f).of(-0.1f)
        assertThat(e.touches[1]!!.dx).isWithin(0.01f).of(-0.3f)
        assertThat(e.touches[2]!!.dy).isWithin(0.01f).of(0.2f)
    }

    @Test
    fun anAutocorrectedNeighbourKeyTypoIsMeasuredFromTheIntendedKey() {
        start()
        controller.onGeometry(KeyboardGeometry(controller.layer, GEO_W, GEO_H))
        predictor.word("rhe", listOf("the"), autocorrect = "the")
        // The user meant "the" but hit 'r' (left of 't') near the border, then h, e on target.
        val (rx, ry) = keyCentre('r')
        val (tx, ty) = keyCentre('t')
        val (w, h) = keyWidth('t')
        controller.onKey(KeyAction.Text("r"), TouchPoint(rx + 0.4f * w, ry, now))
        tapKey('h')
        tapKey('e', dxKeys = 0.05f)
        space()
        type("x") // closes the undo window
        val e = committed().single()
        assertThat(e.method).isEqualTo(CommitMethod.Autocorrected)
        val first = e.touches[0]!!
        assertThat(first.char).isEqualTo('t')
        assertThat(first.dx).isWithin(0.01f).of((rx + 0.4f * w - tx) / w)
        assertThat(first.dy).isWithin(0.01f).of((ry - ty) / h)
        assertThat(first.dx).isLessThan(0f) // left of t's centre
        assertThat(e.touches[1]!!.char).isEqualTo('h')
        assertThat(e.touches[2]!!.char).isEqualTo('e')
    }

    @Test
    fun aTouchTooFarFromTheIntendedKeyIsDropped() {
        start()
        controller.onGeometry(KeyboardGeometry(controller.layer, GEO_W, GEO_H))
        predictor.word("phe", listOf("the"), autocorrect = "the")
        tapKey('p') // far from 't'
        tapKey('h')
        tapKey('e')
        space()
        type("x")
        val e = committed().single()
        assertThat(e.touches[0]).isNull()
        assertThat(e.touches[1]).isNotNull()
    }

    @Test
    fun nonTapCharsHaveNoSampleAndCompletionsAlignByPrefix() {
        start()
        controller.onGeometry(KeyboardGeometry(controller.layer, GEO_W, GEO_H))
        predictor.word("hel", listOf("hello"))
        tapKey('h')
        controller.onKey(KeyAction.Text("e"), null) // key repeat / popup: no touch
        tapKey('l')
        tap(controller.strip.centre)
        val e = committed().single()
        assertThat(e.committed).isEqualTo("hello")
        assertThat(e.touches).hasSize(3)
        assertThat(e.touches[0]).isNotNull()
        assertThat(e.touches[1]).isNull()
        assertThat(e.touches[2]).isNotNull()
    }

    // ------------------------------------------------------------------ AutocorrectReverted

    @Test
    fun oneBackspaceIsHeldUntilTheRestoredWordIsFinished() {
        predictor.word("teh", listOf("the"), autocorrect = "the")
        start()
        type("teh")
        space()
        backspace()
        assertThat(text()).isEqualTo("teh ")
        assertThat(reverts()).isEmpty() // not yet: the user may be about to edit it
        assertThat(committed()).isEmpty() // and there is no "kept" event for the correction
        type("w") // moves on without touching it: the word is kept as typed
        val r = reverts().single()
        assertThat(r.kind).isEqualTo(RevertKind.ImmediateBackspace)
        assertThat(r.typed).isEqualTo("teh")
        assertThat(r.corrected).isEqualTo("the")
        assertThat(r.replacement).isEqualTo("teh")
        assertThat(committed()).isEmpty()
    }

    @Test
    fun anUndoneWordRefinishedUnchangedIsAKeepOnceTheUserMovesOn() {
        predictor.word("teh", listOf("the"), autocorrect = "the")
        start()
        type("teh")
        space()
        backspace() // undo
        backspace() // the space: composed again
        assertThat(editor.composing).isEqualTo("teh")
        space() // finished unchanged, but they could still go back to it
        assertThat(text()).isEqualTo("teh ")
        assertThat(reverts()).isEmpty()
        type("w")
        val r = reverts().single()
        assertThat(r.kind).isEqualTo(RevertKind.ImmediateBackspace)
        assertThat(r.replacement).isEqualTo("teh")
    }

    @Test
    fun enterKeepsAnUndoneWord() {
        predictor.word("teh", listOf("the"), autocorrect = "the")
        start()
        type("teh")
        space()
        backspace()
        assertThat(reverts()).isEmpty()
        controller.onKey(KeyAction.Enter)
        assertThat(reverts().single().kind).isEqualTo(RevertKind.ImmediateBackspace)
    }

    @Test
    fun undoThenEditingTheWordIntoAnotherIsEditedLaterAndNotAKeep() {
        predictor.word("teh", listOf("the"), autocorrect = "the")
        start()
        type("teh")
        space()
        backspace() // undo: "teh "
        backspace() // "teh" composed again
        backspace() // "te"
        type("a") // "tea": what they meant
        assertThat(reverts()).isEmpty()
        space()
        type("w")
        val r = reverts().single()
        assertThat(r.kind).isEqualTo(RevertKind.EditedLater)
        assertThat(r.typed).isEqualTo("teh")
        assertThat(r.corrected).isEqualTo("the")
        assertThat(r.replacement).isEqualTo("tea")
    }

    @Test
    fun undoThenEditingAnAlreadyFinishedWordIsEditedLater() {
        predictor.word("teh", listOf("the"), autocorrect = "the")
        start()
        type("teh")
        space()
        backspace()
        backspace()
        space() // finished unchanged: held
        backspace() // they come back to it
        backspace()
        type("a")
        space()
        type("w")
        val r = reverts().single()
        assertThat(r.kind).isEqualTo(RevertKind.EditedLater)
        assertThat(r.replacement).isEqualTo("tea")
    }

    @Test
    fun undoThenPickingASuggestionIsSwitchedToSuggestion() {
        predictor.word("tset", listOf("test", "set"), autocorrect = "test")
        start()
        type("tset")
        space()
        backspace()
        backspace() // "tset" composed again
        predictor.word("tset", listOf("test", "set"))
        type("")
        assertThat(reverts()).isEmpty()
        controller.onSuggestionTap(
            dev.keycorrect.feature.keyboard.prediction.StripSlot(
                "set",
                SlotKind.Suggestion,
                "Suggestion: set",
            )
        )
        val r = reverts().single()
        assertThat(r.kind).isEqualTo(RevertKind.SwitchedToSuggestion)
        assertThat(r.replacement).isEqualTo("set")
    }

    @Test
    fun undoThenAbandoningTheWordReportsNothing() {
        predictor.word("teh", listOf("the"), autocorrect = "the")
        start()
        type("teh")
        space()
        backspace()
        backspace()
        backspace()
        backspace()
        backspace() // the word is gone
        assertThat(text()).isEmpty()
        space()
        type("w")
        controller.onKey(KeyAction.Enter)
        assertThat(reverts()).isEmpty()
        assertThat(committed().none { it.method == CommitMethod.Autocorrected }).isTrue()
    }

    @Test
    fun undoThenLeavingTheFieldReportsNothing() {
        predictor.word("teh", listOf("the"), autocorrect = "the")
        start()
        type("teh")
        space()
        backspace()
        controller.onFinishInput()
        start()
        type("w")
        assertThat(reverts()).isEmpty()
    }

    @Test
    fun undoThenTheCursorMovingAwayReportsNothing() {
        predictor.word("teh", listOf("the"), autocorrect = "the")
        start()
        type("teh")
        space()
        backspace()
        editor.echo(controller)
        editor.moveCursor(0)
        editor.echo(controller)
        type("w")
        assertThat(reverts()).isEmpty()
    }

    @Test
    fun anUndoneWordIsOnlyHeldBackFromCorrectionForThatOccurrence() {
        predictor.word("teh", listOf("the"), autocorrect = "the")
        start()
        type("teh")
        space()
        backspace() // "teh "
        backspace()
        space() // not corrected again while they put it back
        assertThat(text()).isEqualTo("teh ")
        type("teh")
        space() // a new occurrence is corrected again (the learning thresholds decide for good)
        assertThat(text()).isEqualTo("teh the ")
    }

    @Test
    fun theOccurrenceGuardAlsoWorksWhereLearningIsOff() {
        predictor.word("teh", listOf("the"), autocorrect = "the")
        start(settings = KeyboardSettings(incognito = true))
        type("teh")
        space()
        backspace()
        backspace()
        space()
        assertThat(text()).isEqualTo("teh ")
        type("teh")
        space()
        assertThat(text()).isEqualTo("teh the ")
        assertThat(sink.isSilent).isTrue()
    }

    @Test
    fun deletingTheCorrectionAndRetypingTheOriginalIsKeptAndNotCorrectedAgain() {
        predictor.word("teh", listOf("the"), autocorrect = "the")
        start()
        type("teh")
        space()
        type("w") // closes the undo window; backspacing is now a plain edit
        backspace()
        backspace() // the space: the word is composed again
        assertThat(editor.composing).isEqualTo("the")
        backspace()
        backspace()
        backspace() // the whole word is gone
        assertThat(text()).isEmpty()
        type("teh")
        // The strip no longer offers to apply the correction.
        assertThat(controller.strip.centre?.kind).isNotEqualTo(SlotKind.Autocorrect)
        space()
        assertThat(text()).isEqualTo("teh ")
        assertThat(reverts()).isEmpty() // finished, but it could still be edited
        type("w")
        val r = reverts().single()
        assertThat(r.kind).isEqualTo(RevertKind.DeletedAndRetyped)
        assertThat(r.typed).isEqualTo("teh")
        assertThat(r.corrected).isEqualTo("the")
        assertThat(r.replacement).isEqualTo("teh")
    }

    @Test
    fun deletingTheCorrectionAndTypingSomethingElseIsEditedLater() {
        predictor.word("teh", listOf("the"), autocorrect = "the")
        start()
        type("teh")
        space()
        type("w")
        backspace()
        backspace()
        repeat(3) { backspace() }
        type("tea")
        space()
        type("w")
        val r = reverts().single()
        assertThat(r.kind).isEqualTo(RevertKind.EditedLater)
        assertThat(r.replacement).isEqualTo("tea")
    }

    @Test
    fun editingTheCorrectedWordIntoAnotherSpellingIsEditedLater() {
        predictor.word("recieve", listOf("receive"), autocorrect = "receive")
        start()
        type("recieve")
        space()
        type("w")
        backspace()
        backspace() // composed again: "receive"
        backspace() // "receiv"
        type("ed")
        // "received": a different word, close to the original and the correction
        space()
        val r = reverts().single()
        assertThat(r.kind).isEqualTo(RevertKind.EditedLater)
        assertThat(r.typed).isEqualTo("recieve")
        assertThat(r.corrected).isEqualTo("receive")
        assertThat(r.replacement).isEqualTo("received")
    }

    @Test
    fun anUnrelatedRewordingOfACorrectedWordIsNotAnEdit() {
        predictor.word("teh", listOf("the"), autocorrect = "the")
        start()
        type("teh")
        space()
        type("w")
        backspace()
        backspace()
        backspace()
        backspace()
        type("banana")
        space()
        assertThat(reverts()).isEmpty()
    }

    @Test
    fun switchingToAnotherSuggestionAfterTheUndoIsReportedWithTheRunnerUp() {
        predictor.word("tset", listOf("test", "set"), autocorrect = "test", runnerUp = "set")
        start()
        type("tset")
        space()
        backspace() // undo: "tset "
        backspace() // the space: "tset" composed again
        predictor.word("tset", listOf("test", "set"))
        type("") // nothing
        controller.onSuggestionTap(
            dev.keycorrect.feature.keyboard.prediction.StripSlot(
                "set",
                SlotKind.Suggestion,
                "Suggestion: set",
            )
        )
        val r = reverts().single()
        assertThat(r.kind).isEqualTo(RevertKind.SwitchedToSuggestion)
        assertThat(r.typed).isEqualTo("tset")
        assertThat(r.corrected).isEqualTo("test")
        assertThat(r.replacement).isEqualTo("set")
        assertThat(committed().single().method).isEqualTo(CommitMethod.SuggestionPicked)
    }

    @Test
    fun anUndoneTieThatIsKeptAsTypedIsStillAnImmediateRevert() {
        predictor.word("tset", listOf("test", "set"), autocorrect = "test", runnerUp = "set")
        start()
        type("tset")
        space()
        backspace()
        type("x") // moves on: the held-back revert is reported now
        val r = reverts().single()
        assertThat(r.kind).isEqualTo(RevertKind.ImmediateBackspace)
    }

    // ------------------------------------------------------------------ WordReplaced

    @Test
    fun replacingAJustTappedSuggestionIsAMisTap() {
        predictor.word("hel", listOf("help", "hello", "held"))
        start()
        type("hel")
        tap(controller.strip.centre) // "help "
        now += 1_000
        type("x") // closes the undo window
        backspace()
        backspace() // the word is composed again
        assertThat(editor.composing).isEqualTo("help")
        backspace()
        backspace()
        backspace()
        backspace()
        type("hello")
        space()
        val r = sink.of<WordReplaced>().single()
        assertThat(r.original).isEqualTo("help")
        assertThat(r.replacement).isEqualTo("hello")
        assertThat(r.originalWasPicked).isTrue()
        assertThat(r.millisAfterCommit).isAtLeast(1_000)
    }

    @Test
    fun aTypedWordThatIsFixedLaterIsAReplacement() {
        start()
        type("cat")
        space()
        type("w")
        backspace()
        backspace()
        backspace() // "ca"
        type("ts")
        space()
        val r = sink.of<WordReplaced>().single()
        assertThat(r.original).isEqualTo("cat")
        assertThat(r.replacement).isEqualTo("cats")
        assertThat(r.originalWasPicked).isFalse()
    }

    @Test
    fun recomposingAWordAndFinishingItUnchangedReportsNothingNew() {
        start()
        type("hello")
        space()
        backspace()
        space()
        assertThat(committed()).hasSize(1)
        assertThat(sink.of<WordReplaced>()).isEmpty()
    }

    // ------------------------------------------------------------------ PhraseOutcome

    @Test
    fun aTappedPhraseThatStaysIsFullyAccepted() {
        predictor.word("go", listOf("got"), phrase = "going to the")
        start()
        type("go")
        tap(controller.strip.right) // the phrase
        assertThat(text()).startsWith("going to the")
        type("park")
        space()
        val p = sink.of<PhraseOutcome>().single()
        assertThat(p.phrase).containsExactly("going", "to", "the").inOrder()
        assertThat(p.acceptedWords).isEqualTo(3)
        assertThat(p.divergedWith).isNull()
    }

    @Test
    fun aTappedPhraseWhoseTailWasDeletedIsPartlyAccepted() {
        predictor.word("go", listOf("got"), phrase = "going to the")
        start()
        type("go")
        tap(controller.strip.right)
        // delete " the " (auto-space, then "the")
        repeat(5) { backspace() }
        assertThat(text().trimEnd()).isEqualTo("going to")
        space()
        type("my")
        space()
        val p = sink.of<PhraseOutcome>().single()
        assertThat(p.acceptedWords).isEqualTo(2)
        assertThat(p.divergedWith).isEqualTo("my")
    }

    @Test
    fun anIgnoredPhraseTypedOutPartlyDivergesAtTheFirstDifferentWord() {
        predictor.word("go", listOf("got"), phrase = "going to the")
        start()
        type("going")
        // the phrase was on offer while "go" was typed, but the word was finished by typing
        predictor.words.remove("go")
        space()
        type("home")
        space()
        assertThat(sink.of<PhraseOutcome>()).isEmpty() // no phrase was on offer at "going"
    }

    // ------------------------------------------------------------------ UserAction

    @Test
    fun theAddWordChipReportsAnAddToDictionary() {
        predictor.word("zorp", listOf("zap"), autocorrect = "zap")
        start()
        type("zorp")
        tap(controller.strip.left) // literal: the add-word chip appears
        assertThat(controller.strip.addWord).isEqualTo("zorp")
        controller.onAddWordTap()
        assertThat(backend.added).containsExactly("zorp")
        val a = sink.of<UserAction>().single()
        assertThat(a.kind).isEqualTo(UserAction.Kind.AddToDictionary)
        assertThat(a.word).isEqualTo("zorp")
    }

    @Test
    fun longPressingASuggestionAsksInlineAndYesBlocksIt() {
        predictor.word("hel", listOf("help", "hello", "held"))
        start()
        type("hel")
        assertThat(controller.canBlockSuggestions).isTrue()
        controller.onSuggestionLongPress(controller.strip.centre!!)
        assertThat(controller.strip.neverSuggest).isEqualTo("help")
        assertThat(sink.of<UserAction>()).isEmpty()
        controller.onNeverSuggestConfirm()
        val a = sink.of<UserAction>().single()
        assertThat(a.kind).isEqualTo(UserAction.Kind.NeverSuggest)
        assertThat(a.word).isEqualTo("help")
        assertThat(controller.strip.neverSuggest).isNull()
        // hidden at once, wherever it was
        val shown =
            listOfNotNull(controller.strip.left, controller.strip.centre, controller.strip.right)
        assertThat(shown.map { it.text }).doesNotContain("help")
        assertThat(text()).isEqualTo("hel")
    }

    @Test
    fun cancelOnThePromptReportsNothing() {
        predictor.word("hel", listOf("help", "hello", "held"))
        start()
        type("hel")
        controller.onSuggestionLongPress(controller.strip.left!!)
        assertThat(controller.strip.neverSuggest).isEqualTo("hello")
        controller.onNeverSuggestCancel()
        assertThat(controller.strip.neverSuggest).isNull()
        assertThat(controller.strip.left?.text).isEqualTo("hello")
        assertThat(sink.of<UserAction>()).isEmpty()
    }

    @Test
    fun thePromptGoesAwayOnItsOwnAndOnTyping() {
        predictor.word("hel", listOf("help", "hello", "held"))
        start()
        type("hel")
        controller.onSuggestionLongPress(controller.strip.centre!!)
        scheduler.fireAll()
        assertThat(controller.strip.neverSuggest).isNull()
        controller.onSuggestionLongPress(controller.strip.centre!!)
        type("l")
        assertThat(controller.strip.neverSuggest).isNull()
        assertThat(sink.of<UserAction>()).isEmpty()
    }

    @Test
    fun theLiteralAndPhrasesCannotBeBlocked() {
        predictor.word("zorp", listOf("zap"), autocorrect = "zap", phrase = "zorp and more")
        start()
        type("zorp")
        controller.onSuggestionLongPress(controller.strip.left!!) // the literal
        assertThat(controller.strip.neverSuggest).isNull()
        controller.onSuggestionLongPress(controller.strip.right!!) // the phrase
        assertThat(controller.strip.neverSuggest).isNull()
    }

    @Test
    fun noBlockingWhereLearningIsOff() {
        start(settings = KeyboardSettings(incognito = true))
        predictor.word("hel", listOf("help", "hello", "held"))
        type("hel")
        assertThat(controller.canBlockSuggestions).isFalse()
        controller.onSuggestionLongPress(controller.strip.centre!!)
        assertThat(controller.strip.neverSuggest).isNull()
    }

    // ------------------------------------------------------------------ sentence words

    @Test
    fun sentenceWordsCarryTheirSentenceEnd() {
        start()
        type("hello world.")
        space()
        type("again")
        space()
        assertThat(sink.sentence.map { it.word to it.end })
            .containsExactly("hello" to false, "world" to true, "again" to false)
            .inOrder()
        assertThat(sink.sentence.map { it.sessionId }.toSet()).hasSize(1)
    }

    @Test
    fun aWordEndingWithEnterEndsTheSentence() {
        editor.enterInsertsLineBreak = true
        start(
            FieldProfile.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, 0)
        )
        type("hello")
        controller.onKey(KeyAction.Enter)
        assertThat(sink.sentence.single().end).isTrue()
    }

    @Test
    fun aTappedWordIsFollowedByAPunctuationMarkToEndTheSentence() {
        predictor.word("hel", listOf("hello"))
        start(settings = KeyboardSettings(autoSpaceAfterPick = false))
        type("hel")
        tap(controller.strip.centre)
        type("!")
        assertThat(sink.sentence.single().word).isEqualTo("hello")
        assertThat(sink.sentence.single().end).isTrue()
    }

    // ------------------------------------------------------------------ sessions and profile

    @Test
    fun eachNewFieldStartsANewSession() {
        start()
        type("hello")
        space()
        controller.onFinishInput()
        start(newField = true)
        type("world")
        space()
        val ids = committed().map { it.sessionId }
        assertThat(ids).hasSize(2)
        assertThat(ids[1]).isGreaterThan(ids[0])
    }

    @Test
    fun theSameFieldKeepsItsSession() {
        start()
        type("hello")
        space()
        controller.onFinishInput()
        start(newField = false)
        type("world")
        space()
        assertThat(committed().map { it.sessionId }.toSet()).hasSize(1)
    }

    @Test
    fun theProfileNamesDeviceOrientationLayoutAndField() {
        controller.updateDevice(tablet = true, landscape = true)
        start(
            FieldProfile.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                0,
            )
        )
        type("hello")
        space()
        val p = committed().single().profile
        assertThat(p.device).isEqualTo(DeviceClass.Tablet)
        assertThat(p.landscape).isTrue()
        assertThat(p.layoutKey).isEqualTo("letters@54dp")
        assertThat(p.field).isEqualTo(FieldClass.Email)
    }

    @Test
    fun aHardwareKeyboardWinsOverTheScreenClass() {
        controller.updateDevice(tablet = true, landscape = false, hardwareKeyboard = true)
        start()
        type("hello")
        space()
        assertThat(committed().single().profile.device).isEqualTo(DeviceClass.HardwareKeyboard)
    }

    @Test
    fun theLayoutKeyFollowsTheLayerAndKeyHeight() {
        start(settings = KeyboardSettings(keyHeightDp = 60))
        type("hello")
        space()
        assertThat(committed().last().profile.layoutKey).isEqualTo("letters@60dp")
        controller.onKey(KeyAction.SwitchLayer("symbols"))
        controller.onKey(KeyAction.SwitchLayer("letters"))
        controller.updateSettings(KeyboardSettings(keyHeightDp = 48))
        type("world")
        space()
        assertThat(committed().last().profile.layoutKey).isEqualTo("letters@48dp")
    }

    private companion object {
        const val GEO_W = 1080f
        const val GEO_H = 600f
    }
}
