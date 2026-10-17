package dev.keycorrect.feature.keyboard

import androidx.compose.foundation.Canvas
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.keycorrect.core.layout.KeySlot
import kotlin.math.max

private val PreviewRadius = 14.dp
private val PreviewOverlap = 4.dp
private val PopupRadius = 14.dp
private val PopupCellInset = 3.dp
private val ShadowSpread = 1.5.dp
private const val SHADOW_LAYERS = 3
private const val SHADOW_ALPHA = 0.07f

/**
 * Draws the key preview bubble and long-press popup panel above the key rows. It covers the whole
 * keyboard content area (suggestion strip included) so they can overlap the strip, and takes no
 * input. It reads [touch] and [shift] only while drawing, so showing and moving them never
 * recomposes.
 */
@Composable
internal fun KeyOverlay(
    touch: KeyboardTouchState,
    shift: () -> ShiftState,
    enterLabel: String,
    keyGap: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer(cacheSize = 32)
    val previewStyle = remember { TextStyle(fontSize = 32.sp) }
    val popupStyle = remember { TextStyle(fontSize = 22.sp) }
    Canvas(modifier.onGloballyPositioned { touch.overlayOrigin = it.positionInWindow() }) {
        val origin = touch.surfaceInOverlay
        val shiftState = shift()
        touch.popup?.let { drawPopup(it, origin, shiftState, colors, textMeasurer, popupStyle) }
        touch.preview?.let {
            drawPreview(
                it,
                origin,
                shiftState,
                enterLabel,
                keyGap.toPx(),
                colors,
                textMeasurer,
                previewStyle,
            )
        }
    }
}

private fun DrawScope.drawPreview(
    slot: KeySlot,
    origin: Offset,
    shift: ShiftState,
    enterLabel: String,
    keyGap: Float,
    colors: ColorScheme,
    textMeasurer: TextMeasurer,
    style: TextStyle,
) {
    val gap = keyGap / 2
    val key =
        Rect(slot.left + gap, slot.top + gap, slot.right - gap, slot.bottom - gap).translate(origin)
    val bounds = previewBounds(key, PreviewOverlap.toPx(), size)
    drawElevated(bounds, PreviewRadius.toPx(), colors.surfaceContainerHighest, colors)
    val layout = textMeasurer.measure(keyLabel(slot.key, shift, enterLabel), style)
    drawText(
        layout,
        color = colors.onSurface,
        topLeft =
            Offset(
                bounds.center.x - layout.size.width / 2f,
                bounds.center.y - layout.size.height / 2f,
            ),
    )
}

private fun DrawScope.drawPopup(
    popup: PopupState,
    origin: Offset,
    shift: ShiftState,
    colors: ColorScheme,
    textMeasurer: TextMeasurer,
    style: TextStyle,
) {
    val panel = popup.panel
    // Shift down rather than leave the overlay when the key is in the top row.
    val top = max(panel.top + origin.y, 0f)
    val left = panel.left + origin.x
    val bounds = Rect(left, top, left + panel.width, top + panel.cellHeight)
    drawElevated(bounds, PopupRadius.toPx(), colors.surfaceContainerHighest, colors)
    val inset = PopupCellInset.toPx()
    val cellRadius = CornerRadius(PopupRadius.toPx() - inset)
    popup.entries.forEachIndexed { i, entry ->
        val cellLeft = left + i * panel.cellWidth
        val selected = i == popup.highlighted
        if (selected) {
            drawRoundRect(
                color = colors.primaryContainer,
                topLeft = Offset(cellLeft + inset, top + inset),
                size = Size(panel.cellWidth - 2 * inset, panel.cellHeight - 2 * inset),
                cornerRadius = cellRadius,
            )
        }
        val layout = textMeasurer.measure(keyLabel(entry, shift, ""), style)
        drawText(
            layout,
            color = if (selected) colors.onPrimaryContainer else colors.onSurface,
            topLeft =
                Offset(
                    cellLeft + (panel.cellWidth - layout.size.width) / 2f,
                    top + (panel.cellHeight - layout.size.height) / 2f,
                ),
        )
    }
}

/** A rounded surface with a soft, cheap drop shadow (stacked translucent layers) and a hairline. */
private fun DrawScope.drawElevated(bounds: Rect, radius: Float, fill: Color, colors: ColorScheme) {
    val spread = ShadowSpread.toPx()
    for (layer in SHADOW_LAYERS downTo 1) {
        val grow = spread * layer
        val shadow = bounds.inflate(grow).translate(0f, grow)
        drawRoundRect(
            color = Color.Black.copy(alpha = SHADOW_ALPHA),
            topLeft = shadow.topLeft,
            size = shadow.size,
            cornerRadius = CornerRadius(radius + grow),
        )
    }
    drawRoundRect(
        color = fill,
        topLeft = bounds.topLeft,
        size = bounds.size,
        cornerRadius = CornerRadius(radius),
    )
    drawRoundRect(
        color = colors.outlineVariant,
        topLeft = bounds.topLeft,
        size = bounds.size,
        cornerRadius = CornerRadius(radius),
        style = Stroke(width = 1.dp.toPx()),
    )
}
