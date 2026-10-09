package dev.keycorrect.feature.keyboard

import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.keycorrect.core.input.EnterAction
import dev.keycorrect.core.settings.KeyboardSettings

/**
 * The full keyboard: suggestion strip (placeholder until Phase 2) above the key surface, with the
 * key previews and popups drawn over both.
 */
@Composable
fun KeyboardScreen(
    controller: KeyboardController,
    settings: KeyboardSettings,
    feedback: KeyFeedback,
    modifier: Modifier = Modifier,
) {
    // Only the shift part of the state is read here, so typing never recomposes the screen.
    val shift by remember(controller) { derivedStateOf { controller.state.shift } }
    val enterLabel = enterLabel(controller.enterAction)
    val showPreviews = settings.keyPreview && !controller.profile.sensitive
    val layer = controller.layer
    val touch = remember { KeyboardTouchState() }
    val onKey = remember(controller) { controller::onKey }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                // With gesture nav the IME draws a 48dp bar (switcher / hide-keyboard buttons)
                // that navigationBars (~15dp, gesture handle only) doesn't cover; tappableElement
                // does. The union also keeps keys off the handle when those buttons are hidden.
                .windowInsetsPadding(
                    WindowInsets.navigationBars.union(WindowInsets.tappableElement)
                )
                .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.fillMaxWidth().height(40.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    KeyboardSurface(
                        layer = layer,
                        shift = shift,
                        enterLabel = enterLabel,
                        settings = settings,
                        showPreviews = showPreviews,
                        feedback = feedback,
                        touch = touch,
                        onKey = onKey,
                    )
                    KeySemantics(
                        layer = layer,
                        shift = shift,
                        onKey = onKey,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
            KeyOverlay(
                touch = touch,
                shift = shift,
                enterLabel = enterLabel,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

private fun enterLabel(action: EnterAction): String =
    when (action) {
        EnterAction.NewLine -> "↵"
        is EnterAction.Editor ->
            when (action.actionId) {
                EditorInfo.IME_ACTION_GO -> "Go"
                EditorInfo.IME_ACTION_SEARCH -> "Search"
                EditorInfo.IME_ACTION_SEND -> "Send"
                EditorInfo.IME_ACTION_NEXT -> "Next"
                EditorInfo.IME_ACTION_PREVIOUS -> "Prev"
                EditorInfo.IME_ACTION_DONE -> "Done"
                else -> "↵"
            }
    }
