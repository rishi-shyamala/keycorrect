package dev.keycorrect.feature.keyboard

import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.keycorrect.core.input.EnterAction
import dev.keycorrect.core.input.TextEditor

/** Translates key actions into editor operations and owns the keyboard's observable state. */
class KeyboardController(private val editor: TextEditor) {
    var state by mutableStateOf(KeyboardState())
        private set

    var enterAction: EnterAction by mutableStateOf(EnterAction.NewLine)
        private set

    /** Resets the keyboard for a newly focused field. */
    fun onStartInput(info: EditorInfo) {
        val inputClass = info.inputType and EditorInfo.TYPE_MASK_CLASS
        val layer =
            when (inputClass) {
                EditorInfo.TYPE_CLASS_NUMBER,
                EditorInfo.TYPE_CLASS_PHONE,
                EditorInfo.TYPE_CLASS_DATETIME -> Layer.Symbols
                else -> Layer.Letters
            }
        state = KeyboardState(layer = layer)
        enterAction = EnterAction.from(info.imeOptions)
    }

    fun onKey(action: KeyAction) {
        when (action) {
            is KeyAction.Text -> {
                editor.commitText(state.transform(action.text))
                state = state.afterCharacter()
            }
            KeyAction.Space -> {
                editor.commitText(" ")
                state = state.afterCharacter()
            }
            KeyAction.Backspace -> editor.deleteBackward()
            KeyAction.Enter -> editor.performEnter()
            KeyAction.Shift -> state = state.onShift()
            is KeyAction.SwitchLayer -> state = state.withLayer(action.layer)
        }
    }
}
