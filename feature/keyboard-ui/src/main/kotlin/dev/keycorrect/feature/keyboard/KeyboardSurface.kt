package dev.keycorrect.feature.keyboard

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
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeySlot
import dev.keycorrect.core.layout.KeyStyle
import dev.keycorrect.core.layout.KeyboardGeometry
import dev.keycorrect.core.layout.KeyboardLayer
import dev.keycorrect.core.settings.KeyboardSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val REPEAT_START_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 50L

/** Enough for every label of a layer in each shift state, so drawing never re-measures text. */
private const val TEXT_CACHE_SIZE = 256

/** A finger on a key. */
private class Press(val slot: KeySlot, var x: Float) {
    /** The long-press timer or the backspace repeat loop. */
    var job: Job? = null

    /** Set when a popup was cancelled: releasing then types nothing. */
    var cancelled = false
}

/**
 * Draws every key of [layer] in a single Canvas and handles multi-touch input.
 *
 * Keys are drawn rather than composed individually, so typing causes no recomposition: pressed keys
 * and the preview/popup state live in [touch] and are read only in draw phases. Character keys fire
 * on release; repeatable keys (backspace) fire on press and auto-repeat while held; keys with popup
 * entries open them after [KeyboardSettings.longPressDelayMs] and commit the highlighted entry on
 * release. The previews and popups themselves are drawn by [KeyOverlay].
 */
@Composable
internal fun KeyboardSurface(
    layer: KeyboardLayer,
    shift: ShiftState,
    enterLabel: String,
    settings: KeyboardSettings,
    showPreviews: Boolean,
    feedback: KeyFeedback,
    touch: KeyboardTouchState,
    onKey: (KeyAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    val textMeasurer = rememberTextMeasurer(cacheSize = TEXT_CACHE_SIZE)
    val currentOnKey by rememberUpdatedState(onKey)
    val currentSettings by rememberUpdatedState(settings)
    val currentShowPreviews by rememberUpdatedState(showPreviews)
    val currentFeedback by rememberUpdatedState(feedback)
    var size by remember { mutableStateOf(IntSize.Zero) }
    val geometry =
        remember(layer, size) {
            KeyboardGeometry(layer, size.width.toFloat(), size.height.toFloat())
        }
    val totalWeight = remember(layer) { layer.rows.sumOf { it.height.toDouble() }.toFloat() }
    val charStyle = remember { TextStyle(fontSize = 22.sp) }
    val modifierStyle = remember { TextStyle(fontSize = 16.sp) }
    val hintStyle = remember { TextStyle(fontSize = 10.sp) }

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(settings.keyHeightDp.dp * totalWeight)
                .onSizeChanged { size = it }
                .onGloballyPositioned { touch.surfaceOrigin = it.positionInWindow() }
                .pointerInput(geometry) {
                    coroutineScope {
                        val presses = HashMap<PointerId, Press>()
                        touch.reset()

                        fun cancelPopup() {
                            val popup = touch.popup ?: return
                            presses[popup.owner]?.cancelled = true
                            touch.popup = null
                        }

                        fun onDown(id: PointerId, position: Offset) {
                            val slot = geometry.hitTest(position.x, position.y) ?: return
                            val key = slot.key
                            val press = Press(slot, position.x)
                            presses[id] = press
                            // A second finger dismisses an open popup; it then types normally.
                            cancelPopup()
                            touch.pressed = touch.pressed + slot.index
                            if (currentShowPreviews && key.style == KeyStyle.Character) {
                                touch.preview = slot
                            }
                            currentFeedback.onKeyPress(
                                view,
                                feedbackKind(key.action),
                                currentSettings,
                            )
                            if (key.repeatable) {
                                currentOnKey(key.action)
                                press.job = launch {
                                    delay(REPEAT_START_DELAY_MS)
                                    while (isActive) {
                                        currentOnKey(key.action)
                                        delay(REPEAT_INTERVAL_MS)
                                    }
                                }
                            } else if (key.popup.isNotEmpty()) {
                                press.job = launch {
                                    delay(currentSettings.longPressDelayMs.toLong())
                                    val panel =
                                        PopupPanel.above(slot, key.popup.size, geometry.width)
                                    touch.preview = null
                                    touch.popup =
                                        PopupState(
                                            id,
                                            slot,
                                            panel,
                                            key.popup,
                                            panel.indexAt(press.x),
                                        )
                                    currentFeedback.haptic(view, currentSettings)
                                }
                            }
                        }

                        fun onMove(id: PointerId, position: Offset) {
                            val press = presses[id] ?: return
                            press.x = position.x
                            val popup = touch.popup?.takeIf { it.owner == id } ?: return
                            if (isFarBelow(position.y, popup.slot)) {
                                cancelPopup()
                            } else {
                                popup.highlighted = popup.panel.indexAt(position.x)
                            }
                        }

                        fun onUp(id: PointerId) {
                            val press = presses.remove(id) ?: return
                            press.job?.cancel()
                            val slot = press.slot
                            touch.pressed = touch.pressed - slot.index
                            if (touch.preview?.index == slot.index) touch.preview = null
                            val popup = touch.popup?.takeIf { it.owner == id }
                            when {
                                popup != null -> {
                                    touch.popup = null
                                    currentOnKey(popup.entries[popup.highlighted].action)
                                }
                                press.cancelled -> Unit
                                !slot.key.repeatable -> currentOnKey(slot.key.action)
                            }
                        }

                        try {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    for (change in event.changes) {
                                        when {
                                            change.changedToDown() ->
                                                onDown(change.id, change.position)
                                            change.changedToUp() -> onUp(change.id)
                                            change.pressed && change.positionChanged() ->
                                                onMove(change.id, change.position)
                                        }
                                        change.consume()
                                    }
                                }
                            }
                        } finally {
                            touch.reset()
                        }
                    }
                }
    ) {
        val gap = KeyGap.toPx()
        val radius = CornerRadius(KeyCornerRadius.toPx())
        val hintInset = 5.dp.toPx()
        val pressed = touch.pressed
        for (slot in geometry.slots) {
            val key = slot.key
            if (key.isSpacer) continue
            val (fill, content) = keyColors(key, slot.index in pressed, shift, colors)
            drawRoundRect(
                color = fill,
                topLeft = Offset(slot.left + gap / 2, slot.top + gap / 2),
                size = Size(slot.right - slot.left - gap, slot.bottom - slot.top - gap),
                cornerRadius = radius,
            )
            val label = keyLabel(key, shift, enterLabel)
            if (label.isNotEmpty()) {
                val layout =
                    textMeasurer.measure(
                        label,
                        if (key.style == KeyStyle.Character) charStyle else modifierStyle,
                    )
                drawText(
                    layout,
                    color = content,
                    topLeft =
                        Offset(
                            slot.centerX - layout.size.width / 2f,
                            slot.centerY - layout.size.height / 2f,
                        ),
                )
            }
            key.hint?.let { hint ->
                val layout = textMeasurer.measure(hint, hintStyle)
                drawText(
                    layout,
                    color = colors.onSurfaceVariant,
                    topLeft =
                        Offset(
                            slot.right - gap / 2 - hintInset - layout.size.width,
                            slot.top + gap / 2 + hintInset / 2,
                        ),
                )
            }
        }
    }
}

private fun feedbackKind(action: KeyAction): FeedbackKind =
    when (action) {
        KeyAction.Backspace -> FeedbackKind.Delete
        KeyAction.Space -> FeedbackKind.Space
        KeyAction.Enter -> FeedbackKind.Return
        else -> FeedbackKind.Standard
    }
