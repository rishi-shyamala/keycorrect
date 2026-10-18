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
 * Backspace sizes its delete from the cached text before the cursor, so holding it does not read
 * the editor on every repeat (each read is a blocking IPC to the app).
 */
class BackspaceReadsTest {
    private val editor = SimEditor()
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
    }

    private val textField = FieldProfile.from(InputType.TYPE_CLASS_TEXT, 0)

    private fun start(selStart: Int = editor.cursor, selEnd: Int = editor.cursor) =
        controller.onStartInput(
            textField,
            KeyboardSettings(numberRow = false),
            false,
            null,
            selStart,
            selEnd,
        )

    private fun backspace() = controller.onKey(KeyAction.Backspace)

    @Test
    fun heldBackspaceReadsNothingAndDeletesWholeGraphemes() {
        val family = "👨‍👩‍👧"
        val accented = "é"
        editor.setText("!! 😀😀 $family$accented")
        start()
        backspace() // may read the text after the cursor once
        assertThat(editor.text.toString()).isEqualTo("!! 😀😀 $family")
        editor.readCalls = 0
        val expected =
            listOf(
                "!! 😀😀 ",
                "!! 😀😀",
                "!! 😀",
                "!! ",
                "!!",
                "!",
                "",
            )
        for (want in expected) {
            backspace()
            assertThat(editor.text.toString()).isEqualTo(want)
        }
        assertThat(editor.readCalls).isEqualTo(0)
        assertThat(editor.deletes).isEmpty() // never the read-then-delete fallback
    }

    @Test
    fun withoutCachedTextTheEditorDecidesTheDelete() {
        backend.predictor = null // no engine: the text is never cached
        editor.setText("ab😀")
        start()
        backspace()
        assertThat(editor.text.toString()).isEqualTo("ab")
        assertThat(editor.deletes).containsExactly(false)
    }

    @Test
    fun aSelectionIsDeletedAsAWholeByTheEditor() {
        editor.setText("hello world", at = 11)
        editor.anchor = 0
        start(selStart = 0, selEnd = 11)
        backspace()
        assertThat(editor.text.toString()).isEmpty()
        assertThat(editor.deletes).containsExactly(false)
    }
}
