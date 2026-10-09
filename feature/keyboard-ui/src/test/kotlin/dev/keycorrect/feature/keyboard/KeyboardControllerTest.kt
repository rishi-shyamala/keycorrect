package dev.keycorrect.feature.keyboard

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.input.TextEditor
import org.junit.Test

class KeyboardControllerTest {
    private class FakeEditor : TextEditor {
        val text = StringBuilder()
        var enters = 0

        override fun commitText(text: CharSequence) {
            this.text.append(text)
        }

        override fun deleteBackward() {
            if (text.isNotEmpty()) text.setLength(text.length - 1)
        }

        override fun performEnter() {
            enters++
        }
    }

    private val editor = FakeEditor()
    private val controller = KeyboardController(editor)

    @Test
    fun typesShiftedThenLowerCase() {
        controller.onKey(KeyAction.Shift)
        controller.onKey(KeyAction.Text("h"))
        controller.onKey(KeyAction.Text("i"))
        controller.onKey(KeyAction.Space)
        assertThat(editor.text.toString()).isEqualTo("Hi ")
    }

    @Test
    fun backspaceAndEnterDelegateToEditor() {
        controller.onKey(KeyAction.Text("a"))
        controller.onKey(KeyAction.Backspace)
        controller.onKey(KeyAction.Enter)
        assertThat(editor.text.toString()).isEmpty()
        assertThat(editor.enters).isEqualTo(1)
    }

    @Test
    fun layerSwitchChangesState() {
        controller.onKey(KeyAction.SwitchLayer(Layer.Symbols))
        assertThat(controller.state.layer).isEqualTo(Layer.Symbols)
    }
}
