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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.keycorrect.core.input.EnterAction

/** The full keyboard: suggestion strip (placeholder until Phase 2) above the key surface. */
@Composable
fun KeyboardScreen(controller: KeyboardController, modifier: Modifier = Modifier) {
    val state = controller.state
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
        Box(modifier = Modifier.fillMaxWidth().height(40.dp))
        KeyboardSurface(
            layer = BuiltInLayouts.forLayer(state.layer),
            shift = state.shift,
            enterLabel = enterLabel(controller.enterAction),
            onKey = controller::onKey,
        )
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
