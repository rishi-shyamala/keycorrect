package dev.keycorrect.feature.keyboard

import android.view.ViewGroup
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.feature.keyboard.emoji.EmojiPanel
import dev.keycorrect.feature.keyboard.emoji.EmojiSearchRow

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
    // Shift is a lambda read only in draw and semantics phases, so neither typing nor a shift
    // change recomposes anything here. It is the effective shift: keys draw shifted while the
    // shift key is held, as a chord would type them.
    val shift = remember(controller) { { controller.effectiveShift } }
    val enterLabel = enterLabel(controller.enterAction)
    val touch = remember { KeyboardTouchState() }
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
                SuggestionArea(controller, settings, feedback, autofill)
                KeyArea(controller, settings, feedback, touch)
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

/**
 * What fills the key area: the editing or emoji panel while one is open, else the keys (also while
 * the emoji search is on, with the search row in the strip's place) with their accessibility nodes.
 * Either way it is [KeyboardController.heightUnits] key heights tall, so nothing jumps when the
 * panel opens.
 */
@Composable
private fun KeyArea(
    controller: KeyboardController,
    settings: KeyboardSettings,
    feedback: KeyFeedback,
    touch: KeyboardTouchState,
) {
    val panelHeight = settings.keyHeightDp.dp * controller.heightUnits
    if (controller.panel == KeyboardPanel.Emoji && !controller.emojiSearch.active) {
        val view = LocalView.current
        EmojiPanel(
            controller = controller,
            barHeight = settings.keyHeightDp.dp,
            onPress = { feedback.haptic(view, settings) },
            modifier = Modifier.fillMaxWidth().height(panelHeight),
        )
        return
    }
    if (controller.panel == KeyboardPanel.Editing) {
        val view = LocalView.current
        EditingPanel(
            editing = controller.editing,
            onPress = { feedback.haptic(view, settings) },
            modifier =
                Modifier.fillMaxWidth().height(settings.keyHeightDp.dp * controller.heightUnits),
        )
        return
    }
    val layer = controller.layer
    val shift = remember(controller) { { controller.effectiveShift } }
    val enterLabel = enterLabel(controller.enterAction)
    val enterDescription = enterDescription(controller.enterAction, fallback = "Enter")
    val onKey = remember(controller) { controller::onKey }
    // Accessibility actions have no touch position.
    val onSemanticKey = remember(controller) { { action: KeyAction -> controller.onKey(action) } }
    val onGeometry = remember(controller) { controller::onGeometry }
    val onShiftDown = remember(controller) { controller::onShiftDown }
    val onShiftUp = remember(controller) { controller::onShiftUp }
    Box(modifier = Modifier.fillMaxWidth()) {
        KeyboardSurface(
            layer = layer,
            heightUnits = controller.heightUnits,
            shift = shift,
            enterLabel = enterLabel,
            settings = settings,
            showPreviews = settings.keyPreview && !controller.profile.sensitive,
            feedback = feedback,
            touch = touch,
            onKey = onKey,
            onGeometry = onGeometry,
            onShiftDown = onShiftDown,
            onShiftUp = onShiftUp,
            gestures = controller.gestures,
            terminalModifiers = controller.terminalModifiers,
        )
        KeySemantics(
            layer = layer,
            shift = shift,
            enterDescription = enterDescription,
            onKey = onSemanticKey,
            modifier = Modifier.matchParentSize(),
            terminalModifiers = controller.terminalModifiers,
        )
    }
}

/**
 * Fixed-height area above the keys: autofill chips when there are any (they take priority), else
 * the selection strip while a range is selected, else the prediction strip, else nothing. The
 * height never changes, so the keys below don't jump. The controller's strip is read inside
 * [PredictionStrip] only, so a new suggestion recomposes that and nothing else.
 */
@Composable
private fun SuggestionArea(
    controller: KeyboardController,
    settings: KeyboardSettings,
    feedback: KeyFeedback,
    autofill: InlineAutofillController?,
) {
    Box(modifier = Modifier.fillMaxWidth().height(SuggestionStripHeight)) {
        val items = autofill?.suggestions?.collectAsState()?.value.orEmpty()
        if (controller.panel == KeyboardPanel.Emoji && controller.emojiSearch.active) {
            val view = LocalView.current
            EmojiSearchRow(controller, onPress = { feedback.haptic(view, settings) })
        } else if (items.isNotEmpty()) {
            InlineSuggestionRow(items)
        } else if (controller.selectionStripVisible) {
            SelectionStrip(controller.editing)
        } else {
            PredictionStrip(
                strip = { controller.strip },
                onSlot = controller::onSuggestionTap,
                onAddWord = controller::onAddWordTap,
                onSlotLongPress = controller::onSuggestionLongPress,
                onNeverYes = controller::onNeverSuggestConfirm,
                onNeverCancel = controller::onNeverSuggestCancel,
                canBlock = { controller.canBlockSuggestions },
            )
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

internal val SuggestionStripHeight = 40.dp
private val SuggestionSpacing = 6.dp
