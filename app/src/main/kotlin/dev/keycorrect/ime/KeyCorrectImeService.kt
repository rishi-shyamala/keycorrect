package dev.keycorrect.ime

import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import dev.keycorrect.core.designsystem.KeyCorrectTheme
import dev.keycorrect.core.ime.ComposeInputMethodService
import dev.keycorrect.core.input.EditorSession
import dev.keycorrect.feature.keyboard.KeyboardController
import dev.keycorrect.feature.keyboard.KeyboardScreen

/** The system-facing keyboard service. */
class KeyCorrectImeService : ComposeInputMethodService() {
    private val controller by lazy {
        KeyboardController(
            EditorSession(
                connection = { currentInputConnection },
                editorInfo = { currentInputEditorInfo },
            )
        )
    }

    @Composable
    override fun KeyboardContent() {
        KeyCorrectTheme { KeyboardScreen(controller) }
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        controller.onStartInput(editorInfo)
    }
}
