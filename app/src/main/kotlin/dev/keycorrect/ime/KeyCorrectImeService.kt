package dev.keycorrect.ime

import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import dev.keycorrect.appContainer
import dev.keycorrect.core.designsystem.KeyCorrectTheme
import dev.keycorrect.core.ime.ComposeInputMethodService
import dev.keycorrect.core.input.EditorSession
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.feature.keyboard.KeyFeedback
import dev.keycorrect.feature.keyboard.KeyboardController
import dev.keycorrect.feature.keyboard.KeyboardScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** The system-facing keyboard service. */
class KeyCorrectImeService : ComposeInputMethodService() {
    private val settingsFlow = MutableStateFlow(KeyboardSettings())

    private val controller by lazy {
        KeyboardController(
            EditorSession(
                connection = { currentInputConnection },
                editorInfo = { currentInputEditorInfo },
            ),
            appContainer.keyboardComposer,
        )
    }

    private val feedback by lazy { KeyFeedback(this) }

    override fun onCreate() {
        super.onCreate()
        lifecycleScope.launch {
            appContainer.settingsRepository.settings.collect {
                settingsFlow.value = it
                controller.updateSettings(it)
            }
        }
    }

    @Composable
    override fun KeyboardContent() {
        val settings by settingsFlow.collectAsState()
        KeyCorrectTheme { KeyboardScreen(controller, settings, feedback) }
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        controller.onStartInput(FieldProfile.from(editorInfo), settingsFlow.value)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        controller.onSelectionChanged()
    }
}
