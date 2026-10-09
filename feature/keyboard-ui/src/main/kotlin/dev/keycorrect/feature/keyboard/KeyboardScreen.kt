package dev.keycorrect.feature.keyboard

import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.inline.InlineContentView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.keycorrect.core.input.EnterAction
import dev.keycorrect.core.settings.KeyboardSettings

/**
 * The full keyboard: suggestion strip (autofill chips for now) above the key surface, with the key
 * previews and popups drawn over both.
 */
@Composable
fun KeyboardScreen(
    controller: KeyboardController,
    settings: KeyboardSettings,
    feedback: KeyFeedback,
    modifier: Modifier = Modifier,
    autofill: InlineAutofillController? = null,
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
                SuggestionStrip(autofill)
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

/** Fixed-height strip above the keys; shows the autofill suggestions when there are any. */
@Composable
private fun SuggestionStrip(autofill: InlineAutofillController?) {
    Box(modifier = Modifier.fillMaxWidth().height(SuggestionStripHeight)) {
        if (autofill != null) {
            val items by autofill.suggestions.collectAsState()
            if (items.isNotEmpty()) InlineSuggestionRow(items)
        }
    }
}

/**
 * Scrollable chips on the left; pinned items (the password-manager icon) fixed at the right edge.
 * Each view is keyed by identity so Compose never re-parents an [InlineContentView].
 */
@Composable
private fun InlineSuggestionRow(items: List<InlineSuggestionItem>) {
    val regular = items.filterNot { it.pinned }
    val pinned = items.filter { it.pinned }
    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SuggestionSpacing),
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SuggestionSpacing),
        ) {
            regular.forEach { item -> key(item.view) { InlineSuggestionView(item.view) } }
        }
        pinned.forEach { item -> key(item.view) { InlineSuggestionView(item.view) } }
    }
}

@Composable
private fun InlineSuggestionView(view: InlineContentView) {
    AndroidView(
        factory = {
            // A view can only have one parent; drop any stale one before Compose adopts it.
            (view.parent as? ViewGroup)?.removeView(view)
            if (view.layoutParams == null) {
                view.layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            }
            view
        }
    )
}

private val SuggestionStripHeight = 40.dp
private val SuggestionSpacing = 6.dp

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
