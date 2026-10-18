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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextLayoutResult
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

/** Swipe trail stroke width. */
private val TrailWidth = 6.dp

private val JoystickRimWidth = 2.dp
private const val JOYSTICK_BASE_ALPHA = 0.10f
private const val JOYSTICK_DEAD_ZONE_ALPHA = 0.12f
private const val JOYSTICK_RIM_ALPHA = 0.30f
private const val JOYSTICK_RIM_ENGAGED_ALPHA = 0.70f
private const val JOYSTICK_KNOB_ALPHA = 0.55f
private const val JOYSTICK_KNOB_ENGAGED_ALPHA = 0.90f

/** The knob's radius as a share of the ring's. */
private const val JOYSTICK_KNOB_RADIUS = 0.3f

/** The newest part of the trail, in milliseconds of finger time, is drawn at full strength. */
internal const val TRAIL_SOLID_MS = 400L

/** Older than the solid part, the trail fades out over this long, thinning as it goes. */
internal const val TRAIL_FADE_MS = 400L

/** The thinnest the trail gets (share of its width) just before it disappears. */
private const val TRAIL_MIN_WIDTH = 0.35f

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
    val cache = remember(textMeasurer) { OverlayCache(textMeasurer) }
    // Its own layer: a preview, popup or trail frame redraws this and nothing else.
    Canvas(
        modifier.graphicsLayer().onGloballyPositioned {
            touch.overlayOrigin = it.positionInWindow()
        }
    ) {
        val origin = touch.surfaceInOverlay
        val shiftState = shift()
        touch.trail?.let {
            // Read so a new point redraws; the path grows in place.
            if (touch.trailVersion >= 0) drawTrail(it, origin, cache, colors.primary)
        }
        touch.joystick?.let { drawJoystick(it, origin, cache, colors.primary) }
        touch.popup?.let { drawPopup(it, origin, shiftState, colors, cache) }
        touch.preview?.let {
            drawPreview(it, origin, shiftState, enterLabel, keyGap.toPx(), colors, cache)
        }
    }
}

/**
 * What the overlay's draws reuse from frame to frame: the swipe trail's path, the measured preview
 * and popup labels (one layout per distinct text, so a press measures nothing after its first
 * time), and the [Stroke]s, which depend only on the density. Draw-phase (UI thread) only.
 */
private class OverlayCache(private val measurer: TextMeasurer) {
    val trailPath = Path()
    private val previewStyle = TextStyle(fontSize = 32.sp)
    private val popupStyle = TextStyle(fontSize = 22.sp)
    private val previews = HashMap<String, TextLayoutResult>()
    private val popups = HashMap<String, TextLayoutResult>()
    private val rim = StrokeMemo()
    private val hairline = StrokeMemo()
    private val trail = StrokeMemo(StrokeCap.Round, StrokeJoin.Round)

    fun preview(text: String): TextLayoutResult =
        previews.getOrPut(text) { measurer.measure(text, previewStyle) }

    fun popup(text: String): TextLayoutResult =
        popups.getOrPut(text) { measurer.measure(text, popupStyle) }

    fun rim(width: Float): Stroke = rim.of(width)

    fun hairline(width: Float): Stroke = hairline.of(width)

    fun trail(width: Float): Stroke = trail.of(width)
}

/** The last [Stroke] made, reused while the width stays the same. */
private class StrokeMemo(
    private val cap: StrokeCap = StrokeCap.Butt,
    private val join: StrokeJoin = StrokeJoin.Miter,
) {
    private var width = -1f
    private var stroke = Stroke()

    fun of(width: Float): Stroke {
        if (width != this.width) {
            this.width = width
            stroke = Stroke(width = width, cap = cap, join = join)
        }
        return stroke
    }
}

/**
 * The spacebar drag's joystick: a faint base ring at the touch-down point with a fainter dead zone
 * inside it, and a knob following the finger (held on the ring), stronger once the finger is at the
 * ring and the cursor is moving fast. Allocation-free.
 */
private fun DrawScope.drawJoystick(
    joystick: JoystickState,
    origin: Offset,
    cache: OverlayCache,
    color: Color,
) {
    val center = Offset(joystick.centerX + origin.x, joystick.centerY + origin.y)
    val engaged = joystick.engaged
    drawCircle(color.copy(alpha = JOYSTICK_BASE_ALPHA), joystick.radius, center)
    drawCircle(color.copy(alpha = JOYSTICK_DEAD_ZONE_ALPHA), joystick.deadZone, center)
    drawCircle(
        color.copy(alpha = if (engaged) JOYSTICK_RIM_ENGAGED_ALPHA else JOYSTICK_RIM_ALPHA),
        joystick.radius,
        center,
        style = cache.rim(JoystickRimWidth.toPx()),
    )
    drawCircle(
        color.copy(alpha = if (engaged) JOYSTICK_KNOB_ENGAGED_ALPHA else JOYSTICK_KNOB_ALPHA),
        joystick.radius * JOYSTICK_KNOB_RADIUS,
        Offset(joystick.knobX + origin.x, joystick.knobY + origin.y),
    )
}

/**
 * The swipe [path] as a smooth line: the last [TRAIL_SOLID_MS] as one stroked curve (through the
 * midpoints of the points, so no corners), older points as short segments fading and thinning over
 * [TRAIL_FADE_MS]. Ages are relative to the newest point, so a finger held still keeps its trail.
 * Allocation-free: [curve] is reused and the segments are plain lines.
 */
private fun DrawScope.drawTrail(
    path: SwipePath,
    origin: Offset,
    cache: OverlayCache,
    color: Color,
) {
    val curve = cache.trailPath
    val n = path.size
    if (n < 2) return
    val width = TrailWidth.toPx()
    val newest = path.time(n - 1)
    // Walk back to where the solid part ends and where the fade ends.
    var solidStart = n - 1
    while (solidStart > 0 && newest - path.time(solidStart - 1) <= TRAIL_SOLID_MS) solidStart--
    curve.rewind()
    curve.moveTo(path.x(solidStart) + origin.x, path.y(solidStart) + origin.y)
    for (i in solidStart + 1 until n - 1) {
        val mx = (path.x(i) + path.x(i + 1)) / 2f + origin.x
        val my = (path.y(i) + path.y(i + 1)) / 2f + origin.y
        curve.quadraticTo(path.x(i) + origin.x, path.y(i) + origin.y, mx, my)
    }
    curve.lineTo(path.x(n - 1) + origin.x, path.y(n - 1) + origin.y)
    drawPath(
        curve,
        color,
        style = cache.trail(width),
    )
    var i = solidStart
    while (i > 0) {
        val age = newest - path.time(i - 1)
        if (age >= TRAIL_SOLID_MS + TRAIL_FADE_MS) break
        val fade = 1f - (age - TRAIL_SOLID_MS).toFloat() / TRAIL_FADE_MS
        drawLine(
            color = color.copy(alpha = fade),
            start = Offset(path.x(i - 1) + origin.x, path.y(i - 1) + origin.y),
            end = Offset(path.x(i) + origin.x, path.y(i) + origin.y),
            strokeWidth = width * (TRAIL_MIN_WIDTH + (1f - TRAIL_MIN_WIDTH) * fade),
            cap = StrokeCap.Round,
        )
        i--
    }
}

private fun DrawScope.drawPreview(
    slot: KeySlot,
    origin: Offset,
    shift: ShiftState,
    enterLabel: String,
    keyGap: Float,
    colors: ColorScheme,
    cache: OverlayCache,
) {
    val gap = keyGap / 2
    val key =
        Rect(slot.left + gap, slot.top + gap, slot.right - gap, slot.bottom - gap).translate(origin)
    val bounds = previewBounds(key, PreviewOverlap.toPx(), size)
    drawElevated(bounds, PreviewRadius.toPx(), colors.surfaceContainerHighest, colors, cache)
    val layout = cache.preview(keyLabel(slot.key, shift, enterLabel))
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
    cache: OverlayCache,
) {
    val panel = popup.panel
    // Shift down rather than leave the overlay when the key is in the top row.
    val top = max(panel.top + origin.y, 0f)
    val left = panel.left + origin.x
    val bounds = Rect(left, top, left + panel.width, top + panel.cellHeight)
    drawElevated(bounds, PopupRadius.toPx(), colors.surfaceContainerHighest, colors, cache)
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
        val layout = cache.popup(keyLabel(entry, shift, ""))
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
private fun DrawScope.drawElevated(
    bounds: Rect,
    radius: Float,
    fill: Color,
    colors: ColorScheme,
    cache: OverlayCache,
) {
    val spread = ShadowSpread.toPx()
    val shadowColor = Color.Black.copy(alpha = SHADOW_ALPHA)
    for (layer in SHADOW_LAYERS downTo 1) {
        val grow = spread * layer
        drawRoundRect(
            color = shadowColor,
            topLeft = Offset(bounds.left - grow, bounds.top),
            size = Size(bounds.width + 2 * grow, bounds.height + 2 * grow),
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
        style = cache.hairline(1.dp.toPx()),
    )
}
