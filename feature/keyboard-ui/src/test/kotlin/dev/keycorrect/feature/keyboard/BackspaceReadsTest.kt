package dev.keycorrect.feature.keyboard

import android.text.InputType
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.input.TextEditor
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.settings.KeyboardSettings
import org.junit.Test

/** An editor that ignores `deleteSurroundingText`, as one that rejects or truncates it would. */
private class RejectingEditor(private val sim: SimEditor) : TextEditor by sim {
    override fun deleteSurroundingText(before: Int, after: Int) = Unit
}

/**
 * Backspace sizes its delete from the cached text before the cursor, so holding it does not read
 * the editor on every repeat (each read is a blocking IPC to the app).
 */
class BackspaceReadsTest {
    private val editor = SimEditor()
    private val backend = FakeBackend()
    private val controller: KeyboardController

    /** What the controller edits through: [editor], or a wrapper of it. */
    private var target: TextEditor = editor

    init {
        backend.predictor = ScriptedPredictor()
        controller =
            KeyboardController(
                object : TextEditor by editor {
                    override fun deleteSurroundingText(before: Int, after: Int) =
                        target.deleteSurroundingText(before, after)
                },
                KeyboardComposer(BuiltInLayouts.repository()),
                testEnv(backend),
            )
        editor.onOperation = { editor.echo(controller) } // the platform reports each edit
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
            if (want.isNotEmpty()) assertThat(editor.readCalls).isEqualTo(0)
        }
        // Emptying the field makes the controller check once whether it is empty (the strip's
        // tools); that is its own read, not the delete's.
        assertThat(editor.readCalls).isAtMost(1)
        assertThat(editor.surroundingCalls).isEqualTo(0)
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

    @Test
    fun aFieldOpenedWithARangeSelectedDeletesTheRangeNotTheTextBeforeIt() {
        editor.setText("hello world", at = 11)
        editor.anchor = 6 // "world" is selected; no update has been reported
        start(selStart = 6, selEnd = 11)
        backspace()
        assertThat(editor.text.toString()).isEqualTo("hello ")
        assertThat(editor.deletes).containsExactly(false)
    }

    @Test
    fun aFieldOpenedWithAnUnknownSelectionAsksTheEditorFirst() {
        editor.setText("ab😀")
        start(selStart = -1, selEnd = -1)
        backspace()
        assertThat(editor.text.toString()).isEqualTo("ab")
        assertThat(editor.deletes).containsExactly(false)
    }

    @Test
    fun onceTheSelectionIsReportedTheZeroReadPathResumes() {
        editor.setText("abc😀")
        start(selStart = -1, selEnd = -1)
        backspace() // the editor decides; its update reports a collapsed selection
        editor.deletes.clear()
        backspace()
        backspace()
        assertThat(editor.text.toString()).isEqualTo("a")
        assertThat(editor.deletes).isEmpty()
    }

    @Test
    fun aRejectedDeleteIsNotTrustedForTheNextBackspace() {
        editor.setText("ab😀")
        start()
        target = RejectingEditor(editor) // the delete of the emoji does nothing
        backspace()
        assertThat(editor.text.toString()).isEqualTo("ab😀")
        target = editor
        backspace() // no echo confirmed the delete: the editor decides, so the emoji goes whole
        assertThat(editor.text.toString()).isEqualTo("ab")
        assertThat(editor.deletes).containsExactly(false)
    }

    @Test
    fun anExternalChangeMakesTheNextBackspaceReadAgain() {
        editor.setText("abc")
        start()
        backspace()
        assertThat(editor.text.toString()).isEqualTo("ab")
        editor.setText("zz😀") // the app rewrote the field and moved the cursor
        editor.echo(controller)
        editor.readCalls = 0
        backspace()
        assertThat(editor.text.toString()).isEqualTo("zz")
        assertThat(editor.readCalls).isGreaterThan(0) // the cache was read afresh
    }

    @Test
    fun withoutConfirmationsEveryOtherBackspaceStillDeletesCorrectly() {
        editor.onOperation = null // an editor that reports nothing
        editor.setText("x😀😀😀y")
        start()
        repeat(5) { backspace() }
        assertThat(editor.text.toString()).isEmpty()
    }
}
