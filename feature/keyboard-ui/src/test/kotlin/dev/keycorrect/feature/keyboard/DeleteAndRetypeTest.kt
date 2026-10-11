package dev.keycorrect.feature.keyboard

import android.text.InputType
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.learning.AutocorrectReverted
import dev.keycorrect.core.learning.RevertKind
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.feature.keyboard.prediction.SlotKind
import org.junit.Test

/**
 * An autocorrected word that is deleted entirely and typed again as the user wrote it is a verdict
 * on the correction, however long the deletion took: whatever was typed after the word and deleted
 * again on the way, and whether the word was re-composed on the way or not (docs/PLAN.md §6.2: a
 * tracking window of about five words / 120 s). It is not corrected a second time, and once it is
 * finished unchanged the keep event (`DeletedAndRetyped`) follows when the user moves on. Run
 * against an editor that reports nothing and one that reports every intermediate state.
 */
abstract class DeleteAndRetypeScenarios(private val echoing: Boolean) {
    private val editor = SimEditor()
    private val backend = FakeBackend()
    private val predictor = ScriptedPredictor()
    private val sink = RecordingSink()
    private var now = 10_000L
    private val controller: KeyboardController

    init {
        backend.predictor = predictor
        controller =
            KeyboardController(
                editor,
                KeyboardComposer(BuiltInLayouts.repository()),
                testEnv(backend),
                clock = { now },
                learning = sink,
            )
        if (echoing) editor.onOperation = { editor.echo(controller) }
        predictor.word("seperate", listOf("separate"), autocorrect = "separate")
        predictor.word("teh", listOf("the"), autocorrect = "the")
        controller.onStartInput(
            FieldProfile.from(InputType.TYPE_CLASS_TEXT, 0),
            KeyboardSettings(),
            false,
            EditorIdentity(1, "pkg", 1, 0, null),
            editor.cursor,
            editor.cursor,
        )
    }

    private fun key(c: Char) = controller.onKey(KeyAction.Text("$c"))

    /** Types [s]; a space is the space key. */
    private fun type(s: String) = s.forEach { if (it == ' ') space() else key(it) }

    private fun space() = controller.onKey(KeyAction.Space)

    private fun backspace() = controller.onKey(KeyAction.Backspace)

    private fun text() = editor.text.toString()

    private fun reverts() = sink.of<AutocorrectReverted>()

    /** Backspaces, one key each, until the text is [target]. */
    private fun backspaceDownTo(target: String) {
        var guard = 0
        while (text() != target) {
            check(text().startsWith(target) && guard++ < 200) { "stuck at \"${text()}\"" }
            backspace()
        }
    }

    /** "keep " and the corrected word after it, which is the one tracked. */
    private fun typeCorrectedWord() {
        type("keep ")
        type("seperate")
        space()
        assertThat(text()).isEqualTo("keep separate ")
    }

    private fun retypeAndMoveOn() {
        type("seperate")
        // The strip no longer offers to apply the correction.
        assertThat(controller.strip.centre?.kind).isNotEqualTo(SlotKind.Autocorrect)
        space()
        assertThat(text()).isEqualTo("keep seperate ")
        assertThat(reverts()).isEmpty() // finished, but it could still be edited
        type("w")
    }

    private fun assertDeletedAndRetyped() {
        val r = reverts().single()
        assertThat(r.kind).isEqualTo(RevertKind.DeletedAndRetyped)
        assertThat(r.typed).isEqualTo("seperate")
        assertThat(r.corrected).isEqualTo("separate")
        assertThat(r.replacement).isEqualTo("seperate")
        assertThat(r.context).containsExactly("keep")
    }

    // ------------------------------------------------------------------ the device scenario

    @Test
    fun deletingTheWordAfterTypingAndDeletingACharacterAfterItIsDeletedAndRetyped() {
        typeCorrectedWord()
        key('x')
        backspace() // x
        backspace() // the space: the word is composed again
        assertThat(editor.composing).isEqualTo("separate")
        repeat(8) { backspace() }
        assertThat(text()).isEqualTo("keep ")
        retypeAndMoveOn()
        assertDeletedAndRetyped()
    }

    @Test
    fun whateverWasTypedAfterTheWordAndDeletedAgainDoesNotMatter() {
        for (after in listOf("x", "x ", "xy z", "x. ", "hello wor", "1", "(", "ab cd ")) {
            reset()
            typeCorrectedWord()
            type(after)
            backspaceDownTo("keep separate ")
            repeat(9) { backspace() }
            assertThat(text()).isEqualTo("keep ")
            retypeAndMoveOn()
            assertDeletedAndRetyped()
        }
    }

    @Test
    fun theWordCanBeDeletedWithoutEverBeingComposedAgain() {
        // The engine is momentarily gone (it is being reloaded, say): every backspace is a plain
        // one.
        typeCorrectedWord()
        key('x')
        backend.predictor = null
        backspace()
        backspace()
        repeat(8) { backspace() }
        assertThat(editor.composing).isEmpty()
        assertThat(text()).isEqualTo("keep ")
        backend.predictor = predictor
        retypeAndMoveOn()
        assertDeletedAndRetyped()
    }

    @Test
    fun theWordCanBeCutOutByTheApp() {
        typeCorrectedWord()
        key('x')
        backspace()
        editor.echo(controller) // the editor catches up with the keyboard's own edits
        editor.setText("keep ") // a "cut" in the app: the report is an external change
        editor.echo(controller)
        retypeAndMoveOn()
        assertDeletedAndRetyped()
    }

    @Test
    fun theRetypedWordIsNotCorrectedWhileItIsBeingPutBack() {
        typeCorrectedWord()
        key('x')
        repeat(10) { backspace() }
        type("seper")
        assertThat(controller.strip.centre?.kind).isNotEqualTo(SlotKind.Autocorrect)
        type("ate")
        assertThat(controller.strip.centre?.kind).isNotEqualTo(SlotKind.Autocorrect)
        space()
        assertThat(text()).isEqualTo("keep seperate ")
    }

    @Test
    fun anAbandonedDeletionReportsNothing() {
        typeCorrectedWord()
        key('x')
        repeat(10) { backspace() }
        assertThat(text()).isEqualTo("keep ")
        type("w")
        controller.onKey(KeyAction.Enter)
        assertThat(reverts()).isEmpty()
    }

    @Test
    fun theWordThatIsStillThereIsNotTakenForADeletedOne() {
        typeCorrectedWord()
        // The same two words later on, with the corrected word still in the text: a new sentence.
        type("keep ")
        type("seperate")
        space()
        assertThat(text()).isEqualTo("keep separate keep separate ")
        type("w")
        assertThat(reverts()).isEmpty()
    }

    // ------------------------------------------------------------------ a different word

    @Test
    fun deletingTheWordAndWritingAnotherSpellingIsEditedLater() {
        typeCorrectedWord()
        key('x')
        repeat(10) { backspace() }
        type("seperated")
        space()
        type("w")
        val r = reverts().single()
        assertThat(r.kind).isEqualTo(RevertKind.EditedLater)
        assertThat(r.typed).isEqualTo("seperate")
        assertThat(r.corrected).isEqualTo("separate")
        assertThat(r.replacement).isEqualTo("seperated")
    }

    @Test
    fun deletingTheWordWithoutRecomposingAndWritingAnotherSpellingIsEditedLater() {
        typeCorrectedWord()
        key('x')
        backend.predictor = null
        repeat(10) { backspace() }
        backend.predictor = predictor
        type("seperated")
        space()
        type("w")
        val r = reverts().single()
        assertThat(r.kind).isEqualTo(RevertKind.EditedLater)
        assertThat(r.replacement).isEqualTo("seperated")
    }

    @Test
    fun anUnrelatedWordInItsPlaceIsNeitherAVerdictNorProtectedFromCorrection() {
        typeCorrectedWord()
        key('x')
        repeat(10) { backspace() }
        type("teh")
        space() // an unrelated word is corrected as usual
        assertThat(text()).isEqualTo("keep the ")
        type("w")
        assertThat(reverts()).isEmpty()
    }

    @Test
    fun anUnrelatedWordWithoutRecomposingIsCorrectedAsUsualToo() {
        typeCorrectedWord()
        key('x')
        backend.predictor = null
        repeat(10) { backspace() }
        backend.predictor = predictor
        type("teh")
        space()
        assertThat(text()).isEqualTo("keep the ")
        type("w")
        assertThat(reverts()).isEmpty()
    }

    // ------------------------------------------------------------------ the tracking window

    @Test
    fun fourWordsLaterIsStillInsideTheWindow() {
        typeCorrectedWord()
        type("a b c d ")
        backspaceDownTo("keep ")
        retypeAndMoveOn()
        assertDeletedAndRetyped()
    }

    @Test
    fun fiveWordsLaterIsBeyondTheWindow() {
        typeCorrectedWord()
        type("a b c d e ")
        backspaceDownTo("keep ")
        type("seperate")
        space()
        assertThat(text()).isEqualTo("keep separate ") // corrected again
        type("w")
        assertThat(reverts()).isEmpty()
    }

    @Test
    fun aMinuteAndAHalfLaterIsStillInsideTheWindow() {
        typeCorrectedWord()
        key('x')
        now += 90_000L
        repeat(10) { backspace() }
        retypeAndMoveOn()
        assertDeletedAndRetyped()
    }

    @Test
    fun overTwoMinutesLaterIsBeyondTheWindow() {
        typeCorrectedWord()
        key('x')
        now += 121_000L
        repeat(10) { backspace() }
        assertThat(text()).isEqualTo("keep ")
        type("seperate")
        space()
        assertThat(text()).isEqualTo("keep separate ") // corrected again
        type("w")
        assertThat(reverts()).isEmpty()
    }

    @Test
    fun beyondTheWindowWithoutRecomposingIsNoEventEither() {
        typeCorrectedWord()
        key('x')
        backend.predictor = null
        now += 121_000L
        repeat(10) { backspace() }
        backend.predictor = predictor
        type("seperate")
        space()
        assertThat(text()).isEqualTo("keep separate ")
        type("w")
        assertThat(reverts()).isEmpty()
    }

    // ------------------------------------------------------------------ helpers

    /** A new field: nothing is remembered from the scenario before. */
    private fun reset() {
        controller.onFinishInput()
        editor.setText("")
        controller.onStartInput(
            FieldProfile.from(InputType.TYPE_CLASS_TEXT, 0),
            KeyboardSettings(),
            false,
            EditorIdentity(1, "pkg", 1, 0, null),
            editor.cursor,
            editor.cursor,
        )
        sink.events.clear()
    }
}

/** Nothing is reported between the keyboard's own edits (the editor of the other tests). */
class DeleteAndRetypeTest : DeleteAndRetypeScenarios(echoing = false)

/** The editor reports every intermediate state, also those inside a batch. */
class DeleteAndRetypeNonCoalescingTest : DeleteAndRetypeScenarios(echoing = true)
