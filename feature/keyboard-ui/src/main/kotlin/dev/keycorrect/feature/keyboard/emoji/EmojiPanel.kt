package dev.keycorrect.feature.keyboard.emoji

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.keycorrect.core.emoji.Emoji
import dev.keycorrect.core.emoji.EmojiCatalog
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.feature.keyboard.KeyboardController
import dev.keycorrect.feature.keyboard.KeyboardPanel
import dev.keycorrect.feature.keyboard.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The emoji panel (docs/PHASE4.md, PR B), in the place of the keys and as tall as they are:
 * category tabs, a grid of the selected category, and a bar with ABC, search and backspace. A tap
 * inserts through [KeyboardController.onEmojiTap]; a long press on an emoji with variants offers
 * them. While the catalog loads a short message is shown. [onPress] is the key feedback.
 */
@Composable
internal fun EmojiPanel(
    controller: KeyboardController,
    barHeight: Dp,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val catalog = controller.emoji.catalog
    var origin by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var popup by remember { mutableStateOf<VariantPopupState?>(null) }
    Box(
        modifier =
            modifier.onGloballyPositioned {
                origin = it.positionInWindow()
                size = it.size
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (catalog == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.emoji_loading),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                EmojiBody(
                    controller,
                    catalog,
                    onPress,
                    onVariants = { popup = it },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
            EmojiBottomBar(controller, onPress, Modifier.fillMaxWidth().height(barHeight))
        }
        popup?.let { state ->
            VariantPopup(
                state = state,
                origin = origin,
                panel = size,
                onPick = { variant ->
                    popup = null
                    onPress()
                    controller.onEmojiVariantPicked(state.emoji, variant)
                },
                onDismiss = { popup = null },
            )
        }
    }
}

/** Tabs above the grid of the selected tab. */
@Composable
private fun EmojiBody(
    controller: KeyboardController,
    catalog: EmojiCatalog,
    onPress: () -> Unit,
    onVariants: (VariantPopupState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val recents = controller.emoji.recents
    val recentCells = remember(catalog, recents) { recentCells(catalog, recents) }
    val tabs = remember(catalog) { emojiTabs(catalog) }
    val state = controller.emojiPanel
    val selected =
        tabs.firstOrNull { it.key == state.tab }?.key
            ?: tabs[if (recentCells.isEmpty()) 1.coerceAtMost(tabs.lastIndex) else 0].key
    Column(modifier) {
        EmojiTabRow(tabs, selected) {
            onPress()
            state.tab = it
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (selected) {
                EmojiPanelState.RECENTS ->
                    if (recentCells.isEmpty()) {
                        EmptyMessage(R.string.emoji_recents_empty)
                    } else {
                        EmojiGrid(selected, recentCells, controller, onPress, onVariants)
                    }
                EmojiPanelState.KAOMOJI -> KaomojiGrid(catalog, controller, onPress)
                else -> {
                    val cells =
                        remember(catalog, selected) {
                            catalog.groups
                                .firstOrNull { it.name == selected }
                                ?.emoji
                                .orEmpty()
                                .map { EmojiCell(it, null) }
                        }
                    EmojiGrid(selected, cells, controller, onPress, onVariants)
                }
            }
        }
    }
}

@Composable
private fun EmptyMessage(text: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            stringResource(text),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun EmojiTabRow(tabs: List<EmojiTab>, selected: String, onSelect: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(TabHeight)) {
        for (tab in tabs) {
            val on = tab.key == selected
            val description = tab.label?.let { stringResource(it) } ?: tab.key
            Box(
                modifier =
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 1.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(TabCorner))
                        .background(
                            if (on) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer
                        )
                        .clickable(role = Role.Tab) { onSelect(tab.key) }
                        .clearAndSetSemantics {
                            contentDescription = description
                            role = Role.Tab
                            this.selected = on
                            onClick {
                                onSelect(tab.key)
                                true
                            }
                        },
                contentAlignment = Alignment.Center,
            ) {
                Text(tab.icon, fontSize = TAB_ICON_SIZE.sp, maxLines = 1)
            }
        }
    }
}

/** ABC (back to the keys), search and backspace (held to repeat). */
@Composable
private fun EmojiBottomBar(
    controller: KeyboardController,
    onPress: () -> Unit,
    modifier: Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        BarButton(
            text = stringResource(R.string.emoji_abc),
            description = stringResource(R.string.emoji_abc_description),
            highlighted = true,
            modifier = Modifier.width(ABC_WIDTH),
        ) {
            onPress()
            controller.showPanel(KeyboardPanel.Keys)
        }
        BarButton(
            text = "🔍",
            description = stringResource(R.string.emoji_search_description),
            modifier = Modifier.width(BAR_BUTTON_WIDTH),
        ) {
            onPress()
            controller.openEmojiSearch()
        }
        Box(Modifier.weight(1f))
        BackspaceButton(controller, onPress, Modifier.width(ABC_WIDTH))
    }
}

@Composable
private fun BarButton(
    text: String,
    description: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .padding(2.dp)
                .clip(RoundedCornerShape(BarCorner))
                .background(if (highlighted) colors.primary else colors.surfaceContainerHighest)
                .clickable(role = Role.Button, onClick = onClick)
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Button
                    onClick {
                        onClick()
                        true
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (highlighted) colors.onPrimary else colors.onSurface,
            fontSize = BAR_TEXT_SIZE.sp,
            maxLines = 1,
        )
    }
}

/** Backspace sends a delete to the app; held, it repeats after a short delay. */
@Composable
private fun BackspaceButton(
    controller: KeyboardController,
    onPress: () -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val description = stringResource(R.string.emoji_backspace_description)
    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .padding(2.dp)
                .clip(RoundedCornerShape(BarCorner))
                .background(colors.surfaceContainerHighest)
                .pointerInput(controller) {
                    detectTapGestures(
                        onPress = {
                            onPress()
                            controller.onKey(KeyAction.Backspace)
                            val repeat = scope.launch {
                                delay(REPEAT_DELAY_MS)
                                while (true) {
                                    controller.onKey(KeyAction.Backspace)
                                    delay(REPEAT_INTERVAL_MS)
                                }
                            }
                            tryAwaitRelease()
                            repeat.cancel()
                        }
                    )
                }
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Button
                    onClick {
                        controller.onKey(KeyAction.Backspace)
                        true
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Text("⌫", color = colors.onSurface, fontSize = BAR_TEXT_SIZE.sp, maxLines = 1)
    }
}

/** An open variants popup: [emoji]'s forms, anchored on the cell at [anchor] (window pixels). */
internal class VariantPopupState(val emoji: Emoji, val anchor: Offset, val anchorSize: IntSize)

/** A grid cell: [emoji] shown as [value], or in the form a tap would insert when null. */
internal class EmojiCell(val emoji: Emoji, val value: String?)

private fun recentCells(catalog: EmojiCatalog, recents: List<String>): List<EmojiCell> =
    recents.mapNotNull { value ->
        catalog.byValue(value)?.let { EmojiCell(it, value) }
    }

/** One tab of the panel: an emoji [icon] and its accessibility [label] (null: use [key]). */
internal class EmojiTab(val key: String, val icon: String, val label: Int?)

private fun emojiTabs(catalog: EmojiCatalog): List<EmojiTab> = buildList {
    add(EmojiTab(EmojiPanelState.RECENTS, "🕒", R.string.emoji_tab_recents))
    for (group in catalog.groups) {
        val known = KnownTabs[group.name]
        add(EmojiTab(group.name, known?.first ?: group.emoji.first().value, known?.second))
    }
    if (catalog.kaomojiGroups.isNotEmpty()) {
        add(EmojiTab(EmojiPanelState.KAOMOJI, "^_^", R.string.emoji_tab_kaomoji))
    }
}

private val KnownTabs =
    mapOf(
        "Smileys & Emotion" to ("😀" to R.string.emoji_tab_smileys),
        "People & Body" to ("👋" to R.string.emoji_tab_people),
        "Animals & Nature" to ("🐻" to R.string.emoji_tab_animals),
        "Food & Drink" to ("🍔" to R.string.emoji_tab_food),
        "Travel & Places" to ("🚗" to R.string.emoji_tab_travel),
        "Activities" to ("⚽" to R.string.emoji_tab_activities),
        "Objects" to ("💡" to R.string.emoji_tab_objects),
        "Symbols" to ("🔣" to R.string.emoji_tab_symbols),
        "Flags" to ("🏁" to R.string.emoji_tab_flags),
    )

private val TabHeight = 36.dp
private val TabCorner = 8.dp
private val BarCorner = 8.dp
private val ABC_WIDTH = 64.dp
private val BAR_BUTTON_WIDTH = 52.dp
private const val TAB_ICON_SIZE = 18
private const val BAR_TEXT_SIZE = 18
private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 50L
