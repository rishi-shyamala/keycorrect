package dev.keycorrect.feature.keyboard.emoji

import android.graphics.Paint
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.keycorrect.core.emoji.EmojiCatalog
import dev.keycorrect.feature.keyboard.KeyboardController
import dev.keycorrect.feature.keyboard.R
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The emoji of one tab: a lazy grid whose column count follows the width. Each tab has its own
 * scroll position ([tabKey]).
 *
 * Built for fast flings: items have a key and a content type, a cell is drawn straight from a
 * shared [android.graphics.Paint] (no text layout object per cell), cells get their callbacks from
 * one [CellActions] that is updated rather than replaced, and nothing in the grid scope reads state
 * while scrolling.
 */
@Composable
internal fun EmojiGrid(
    tabKey: String,
    cells: List<EmojiCell>,
    controller: KeyboardController,
    onPress: () -> Unit,
    onVariants: (VariantPopupState) -> Unit,
) {
    val actions = remember(controller) { CellActions(controller) }
    SideEffect {
        actions.onPress = onPress
        actions.onVariants = onVariants
    }
    val density = LocalDensity.current
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val paint =
        remember(density.density, density.fontScale, color) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = with(density) { EMOJI_SIZE.sp.toPx() }
                textAlign = Paint.Align.CENTER
                this.color = color
            }
        }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = (maxWidth / CELL_WIDTH).toInt().coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        key(tabKey) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = rememberLazyGridState(),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    cells,
                    key = { it.value ?: it.emoji.value },
                    contentType = { EMOJI_CELL_TYPE },
                ) { cell ->
                    EmojiCellView(cell, actions, paint)
                }
            }
        }
    }
}

/** What a cell calls; one per grid, updated in place so cells never see a new lambda. */
internal class CellActions(val controller: KeyboardController) {
    var onPress: () -> Unit = {}
    var onVariants: (VariantPopupState) -> Unit = {}
}

/** Where a cell was last laid out, kept off the snapshot system so a layout never recomposes. */
private class CellPosition {
    var coordinates: LayoutCoordinates? = null
}

@Composable
private fun EmojiCellView(cell: EmojiCell, actions: CellActions, paint: Paint) {
    val emoji = cell.emoji
    val controller = actions.controller
    val value = cell.value ?: controller.emojiValue(emoji)
    val position = remember { CellPosition() }
    val hasVariants = emoji.variants.isNotEmpty()
    val openVariants = {
        position.coordinates?.let {
            actions.onVariants(VariantPopupState(emoji, it.positionInWindow(), it.size))
        }
        Unit
    }
    val tap = {
        actions.onPress()
        if (cell.value != null) controller.onEmojiPicked(value, emoji.value)
        else controller.onEmojiTap(emoji)
    }
    val variantsLabel = stringResource(R.string.emoji_variants_action)
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .height(CELL_HEIGHT)
                .onGloballyPositioned { position.coordinates = it }
                .pointerInput(cell, value) {
                    detectTapGestures(
                        onTap = { tap() },
                        onLongPress = if (hasVariants) ({ openVariants() }) else null,
                    )
                }
                .drawBehind {
                    val metrics = paint.fontMetrics
                    val baseline = (size.height - (metrics.descent + metrics.ascent)) / 2f
                    drawContext.canvas.nativeCanvas.drawText(
                        value,
                        size.width / 2f,
                        baseline,
                        paint,
                    )
                }
                .clearAndSetSemantics {
                    contentDescription = emoji.name
                    role = Role.Button
                    onClick {
                        tap()
                        true
                    }
                    if (hasVariants) {
                        val open = {
                            openVariants()
                            true
                        }
                        onLongClick(variantsLabel) { open() }
                        customActions = listOf(CustomAccessibilityAction(variantsLabel) { open() })
                    }
                }
    )
}

/** The kaomoji, by group, in a grid of a few wide columns; a tap types the text. */
@Composable
internal fun KaomojiGrid(
    catalog: EmojiCatalog,
    controller: KeyboardController,
    onPress: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = (maxWidth / KAOMOJI_WIDTH).toInt().coerceAtLeast(2)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = rememberLazyGridState(),
            modifier = Modifier.fillMaxSize(),
        ) {
            for (group in catalog.kaomojiGroups) {
                item(key = "g:" + group.name, span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        group.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                items(group.items, key = { "k:" + group.name + it }) { text ->
                    Box(
                        modifier =
                            Modifier.fillMaxWidth().height(KAOMOJI_HEIGHT).clickable(
                                role = Role.Button
                            ) {
                                onPress()
                                controller.onEmojiPicked(text, text, record = false)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text,
                            fontSize = KAOMOJI_SIZE.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The long-press popup: the base emoji and its variants in a small grid over the panel, above the
 * cell it came from where there is room. A tap outside closes it.
 */
@Composable
internal fun VariantPopup(
    state: VariantPopupState,
    origin: Offset,
    panel: IntSize,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val emoji = state.emoji
    val forms =
        remember(emoji) {
            listOf(emoji.value to emoji.name) + emoji.variants.map { it.value to it.label }
        }
    val columns = forms.size.coerceAtMost(POPUP_COLUMNS)
    val rows = ceil(forms.size / columns.toFloat()).toInt()
    Box(Modifier.fillMaxSize().clickable(onClick = onDismiss)) {
        val density = LocalDensity.current
        val cell = with(density) { POPUP_CELL.roundToPx() }
        val width = columns * cell
        val height = minOf(rows * cell, (panel.height * POPUP_MAX_HEIGHT).roundToInt())
        val x =
            (state.anchor.x - origin.x + state.anchorSize.width / 2f - width / 2f)
                .roundToInt()
                .coerceIn(0, maxOf(0, panel.width - width))
        val y = (state.anchor.y - origin.y - height).roundToInt().coerceAtLeast(0)
        Surface(
            modifier =
                Modifier.offset { IntOffset(x, y) }
                    .size(
                        with(density) { width.toDp() },
                        with(density) { height.toDp() },
                    ),
            shape = RoundedCornerShape(POPUP_CORNER),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shadowElevation = POPUP_ELEVATION,
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(forms, key = { it.first }) { (value, label) ->
                    Box(
                        modifier =
                            Modifier.height(POPUP_CELL)
                                .clickable(role = Role.Button) { onPick(value) }
                                .clearAndSetSemantics {
                                    contentDescription = label
                                    role = Role.Button
                                },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(value, fontSize = EMOJI_SIZE.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

private val CELL_WIDTH = 42.dp
private val CELL_HEIGHT = 44.dp
private val KAOMOJI_WIDTH = 120.dp
private val KAOMOJI_HEIGHT = 40.dp
private val POPUP_CELL = 48.dp
private val POPUP_CORNER = 12.dp
private val POPUP_ELEVATION = 8.dp
private const val EMOJI_CELL_TYPE = "emoji"
private const val MIN_COLUMNS = 6
private const val MAX_COLUMNS = 14
private const val POPUP_COLUMNS = 6
private const val POPUP_MAX_HEIGHT = 0.9f
private const val EMOJI_SIZE = 26
private const val KAOMOJI_SIZE = 14
