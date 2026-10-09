package dev.keycorrect.feature.keyboard

import android.text.InputType
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.settings.KeyboardSettings
import org.junit.Test

/**
 * The main composing / autocorrect / undo / recompose scenarios against an editor that reports a
 * selection update after every operation, also those inside a batch (no coalescing). None of those
 * intermediate states may be taken for an external change.
 */
class NonCoalescingEditorTest {
    private val editor = SimEditor()
    private val backend = FakeBackend()
    private val predictor = ScriptedPredictor()
    private val controller: KeyboardController

    init {
        backend.predictor = predictor
        controller =
            KeyboardController(
                editor,
                KeyboardComposer(BuiltInLayouts.repository()),
                testEnv(backend),
                clock = { 10_000L },
            )
        editor.onOperation = { editor.echo(controller) }
    }

    private fun start() =
        controller.onStartInput(
            FieldProfile.from(InputType.TYPE_CLASS_TEXT, 0),
            KeyboardSettings(),
            false,
            null,
            editor.cursor,
            editor.cursor,
        )

    private fun type(text: String) = text.forEach { controller.onKey(KeyAction.Text("$it")) }

    private fun space() = controller.onKey(KeyAction.Space)

    private fun backspace() = controller.onKey(KeyAction.Backspace)

    private fun text() = editor.text.toString()

    @Test
    fun aWordStaysComposedWhileTyping() {
        start()
        type("hello")
        assertThat(editor.composing).isEqualTo("hello")
        type("x")
        assertThat(editor.composing).isEqualTo("hellox")
    }

    @Test
    fun autocorrectAndItsUndoSurviveEveryIntermediateReport() {
        predictor.word("teh", listOf("the"), autocorrect = "the")
        start()
        type("teh")
        space()
        assertThat(text()).isEqualTo("the ")
        backspace() // the undo is a batch of delete + commit
        assertThat(text()).isEqualTo("teh ")
        assertThat(controller.touchLog.snapshot().last().reverted).isTrue()
    }

    @Test
    fun recomposingAWordSurvivesTheIntermediateDeleteReport() {
        predictor.word("hello", listOf("hello", "help"))
        start()
        type("hello")
        space()
        backspace() // delete + setComposing in one batch; the editor reports between them
        assertThat(text()).isEqualTo("hello")
        assertThat(editor.composing).isEqualTo("hello")
        assertThat(predictor.requests.last().composing.text).isEqualTo("hello")
        type("o")
        assertThat(editor.composing).isEqualTo("helloo")
        backspace()
        backspace()
        assertThat(editor.composing).isEqualTo("hell")
    }

    @Test
    fun aSuggestionTapReplacesTheWord() {
        predictor.word("hel", listOf("help", "hello"))
        start()
        type("hel")
        controller.onSuggestionTap(controller.strip.centre!!)
        assertThat(text()).isEqualTo("help ")
        assertThat(editor.composing).isEmpty()
    }

    @Test
    fun aRealCursorMoveIsStillExternal() {
        start()
        type("hel")
        editor.moveCursor(0)
        editor.echo(controller)
        assertThat(editor.composing).isEmpty()
        assertThat(text()).isEqualTo("hel")
    }
}
