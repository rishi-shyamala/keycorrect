package dev.keycorrect.feature.keyboard

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val REPEAT_START_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 50L

/**
 * Draws every key of [layer] in a single Canvas and handles multi-touch input.
 *
 * Keys are drawn rather than composed individually, so typing causes no recomposition: the pressed
 * set is read only in the draw phase. Character keys fire on release; repeatable keys (backspace)
 * fire on press and auto-repeat while held.
 */
@Composable
fun KeyboardSurface(
    layer: KeyboardLayer,
    shift: ShiftState,
    enterLabel: String,
    onKey: (KeyAction) -> Unit,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 54.dp,
    keyGap: Dp = 6.dp,
    cornerRadius: Dp = 10.dp,
) {
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)
    val currentOnKey by rememberUpdatedState(onKey)
    var size by remember { mutableStateOf(IntSize.Zero) }
    val geometry =
        remember(layer, size) {
            KeyboardGeometry(layer, size.width.toFloat(), size.height.toFloat())
        }
    var pressed by remember { mutableStateOf(emptySet<Int>()) }

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(rowHeight * layer.rows.size)
                .onSizeChanged { size = it }
                .pointerInput(geometry) {
                    coroutineScope {
                        val active = mutableMapOf<PointerId, KeySlot>()
                        val repeats = mutableMapOf<PointerId, Job>()
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                for (change in event.changes) {
                                    when {
                                        change.changedToDown() -> {
                                            val slot =
                                                geometry.hitTest(
                                                    change.position.x,
                                                    change.position.y,
                                                ) ?: continue
                                            active[change.id] = slot
                                            pressed = pressed + slot.index
                                            view.performHapticFeedback(
                                                HapticFeedbackConstants.KEYBOARD_TAP
                                            )
                                            if (slot.key.repeatable) {
                                                currentOnKey(slot.key.action)
                                                repeats[change.id] = launch {
                                                    delay(REPEAT_START_DELAY_MS)
                                                    while (isActive) {
                                                        currentOnKey(slot.key.action)
                                                        delay(REPEAT_INTERVAL_MS)
                                                    }
                                                }
                                            }
                                        }
                                        change.changedToUp() -> {
                                            val slot = active.remove(change.id) ?: continue
                                            repeats.remove(change.id)?.cancel()
                                            pressed = pressed - slot.index
                                            if (!slot.key.repeatable) currentOnKey(slot.key.action)
                                        }
                                    }
                                    change.consume()
                                }
                            }
                        }
                    }
                }
    ) {
        val gap = keyGap.toPx()
        val radius = CornerRadius(cornerRadius.toPx())
        val labelStyle = TextStyle(fontSize = 22.sp)
        val modifierStyle = TextStyle(fontSize = 16.sp)
        for (slot in geometry.slots) {
            val isPressed = slot.index in pressed
            val (fill, content) = keyColors(slot.key, isPressed, shift, colors)
            drawRoundRect(
                color = fill,
                topLeft = Offset(slot.left + gap / 2, slot.top + gap / 2),
                size = Size(slot.right - slot.left - gap, slot.bottom - slot.top - gap),
                cornerRadius = radius,
            )
            val label = displayLabel(slot.key, shift, enterLabel)
            if (label.isEmpty()) continue
            val style =
                (if (slot.key.role == KeyRole.Character) labelStyle else modifierStyle).copy(
                    color = content
                )
            val layout = textMeasurer.measure(label, style)
            drawText(
                layout,
                topLeft =
                    Offset(
                        slot.centerX - layout.size.width / 2f,
                        slot.centerY - layout.size.height / 2f,
                    ),
            )
        }
    }
}

private fun displayLabel(key: Key, shift: ShiftState, enterLabel: String): String =
    when (key.action) {
        KeyAction.Enter -> enterLabel
        KeyAction.Shift -> if (shift == ShiftState.Locked) "⇪" else key.label
        is KeyAction.Text ->
            if (shift != ShiftState.Off && key.label.length == 1) key.label.uppercase()
            else key.label
        else -> key.label
    }

private fun keyColors(
    key: Key,
    pressed: Boolean,
    shift: ShiftState,
    colors: androidx.compose.material3.ColorScheme,
): Pair<Color, Color> {
    if (pressed) return colors.primaryContainer to colors.onPrimaryContainer
    return when (key.role) {
        KeyRole.Action -> colors.primary to colors.onPrimary
        KeyRole.Modifier ->
            if (key.action == KeyAction.Shift && shift != ShiftState.Off) {
                colors.tertiaryContainer to colors.onTertiaryContainer
            } else {
                colors.secondaryContainer to colors.onSecondaryContainer
            }
        KeyRole.Character,
        KeyRole.Space -> colors.surfaceContainerHighest to colors.onSurface
    }
}
