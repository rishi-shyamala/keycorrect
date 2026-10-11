package dev.keycorrect.feature.keyboard

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.keycorrect.core.engineapi.TouchPoint
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyStyle
import dev.keycorrect.core.layout.KeyboardGeometry
import dev.keycorrect.core.layout.KeyboardLayer
import dev.keycorrect.core.layout.ResolvedKey
import dev.keycorrect.core.settings.KeyboardSettings
import kotlinx.coroutines.withTimeoutOrNull

/** Enough for every label of a layer in each shift state, so drawing never re-measures text. */
private const val TEXT_CACHE_SIZE = 256

/**
 * Draws every key of [layer] in a single Canvas and handles multi-touch input.
 *
 * Keys are drawn rather than composed individually, so typing causes no recomposition: pressed keys
 * and the preview/popup state live in [touch] and the shift state is a lambda, all read only in
 * draw phases. The touch handling itself is [TouchStateMachine]; the previews and popups are drawn
 * by [KeyOverlay]. The surface is [heightUnits] key-heights tall whatever the layer, and the rows
 * of a layer with fewer rows are stretched to fill it.
 */
@Composable
internal fun KeyboardSurface(
    layer: KeyboardLayer,
    heightUnits: Float,
    shift: () -> ShiftState,
    enterLabel: String,
    settings: KeyboardSettings,
    showPreviews: Boolean,
    feedback: KeyFeedback,
    touch: KeyboardTouchState,
    onKey: (KeyAction, TouchPoint?) -> Unit,
    onGeometry: (KeyboardGeometry) -> Unit,
    onShiftDown: () -> Unit,
    onShiftUp: () -> Unit,
    @Suppress("UNUSED_PARAMETER") gestures: KeyGestures, // wired in PR A (spacebar, backspace)
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    val textMeasurer = rememberTextMeasurer(cacheSize = TEXT_CACHE_SIZE)
    val currentOnKey by rememberUpdatedState(onKey)
    val currentOnGeometry by rememberUpdatedState(onGeometry)
    val currentOnShiftDown by rememberUpdatedState(onShiftDown)
    val currentOnShiftUp by rememberUpdatedState(onShiftUp)
    val currentSettings by rememberUpdatedState(settings)
    val currentShowPreviews by rememberUpdatedState(showPreviews)
    val currentFeedback by rememberUpdatedState(feedback)
    var size by remember { mutableStateOf(IntSize.Zero) }
    val geometry =
        remember(layer, size) {
            KeyboardGeometry(layer, size.width.toFloat(), size.height.toFloat())
        }
    // Tell the controller what the keys look like (for the decoder's touch model) whenever the
    // geometry changes; never before the surface has a size.
    LaunchedEffect(geometry) { currentOnGeometry(geometry) }
    val textCache =
        remember(textMeasurer) {
            KeyTextCache(
                textMeasurer,
                charStyle = TextStyle(fontSize = 22.sp),
                modifierStyle = TextStyle(fontSize = 16.sp),
                hintStyle = TextStyle(fontSize = 10.sp),
            )
        }
    val host =
        remember(view) {
            object : TouchHost {
                override val showPreviews: Boolean
                    get() = currentShowPreviews

                override val longPressDelayMs: Long
                    get() = currentSettings.longPressDelayMs.toLong()

                override fun onPress(action: KeyAction) =
                    currentFeedback.onKeyPress(view, feedbackKind(action), currentSettings)

                override fun onPopupOpened() = currentFeedback.haptic(view, currentSettings)

                override fun onKey(action: KeyAction, touch: TouchPoint?) =
                    currentOnKey(action, touch)

                override fun onShiftDown() = currentOnShiftDown()

                override fun onShiftUp() = currentOnShiftUp()
            }
        }

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(settings.keyHeightDp.dp * heightUnits)
                .onSizeChanged { size = it }
                .onGloballyPositioned { touch.surfaceOrigin = it.positionInWindow() }
                .pointerInput(geometry) {
                    val machine =
                        TouchStateMachine(geometry, touch, host, SystemClock::uptimeMillis)
                    touch.reset()
                    try {
                        awaitPointerEventScope {
                            while (true) {
                                // Wake for a pending long press or repeat even if no event comes.
                                val deadline = machine.nextDeadline()
                                val event =
                                    if (deadline == null) {
                                        awaitPointerEvent()
                                    } else {
                                        val wait = deadline - SystemClock.uptimeMillis()
                                        withTimeoutOrNull(wait.coerceAtLeast(0L)) {
                                            awaitPointerEvent()
                                        }
                                    }
                                if (event == null) {
                                    machine.onTimer()
                                    continue
                                }
                                val changes = event.changes
                                for (i in changes.indices) {
                                    val change = changes[i]
                                    val id = change.id.value
                                    when {
                                        change.changedToDown() ->
                                            machine.onDown(id, change.position.x, change.position.y)
                                        // Even a release another handler consumed ends the press.
                                        change.changedToUpIgnoreConsumed() -> machine.onUp(id)
                                        change.pressed && change.positionChanged() ->
                                            machine.onMove(id, change.position.x, change.position.y)
                                    }
                                    change.consume()
                                }
                                // A finger that vanished without a release must not stay pressed.
                                machine.retainOnly { id ->
                                    changes.any { it.id.value == id && it.pressed }
                                }
                            }
                        }
                    } finally {
                        // Cancelled (layer change, window gone): nothing may stay pressed or
                        // repeat.
                        machine.cancelAll()
                    }
                }
    ) {
        val gap = KeyGap.toPx()
        val radius = CornerRadius(KeyCornerRadius.toPx())
        val hintInset = 5.dp.toPx()
        val pressed = touch.pressed
        val shiftState = shift()
        textCache.prepare(geometry, shiftState, enterLabel, this)
        for (slot in geometry.slots) {
            val key = slot.key
            if (key.isSpacer) continue
            val isPressed = slot.index in pressed
            drawRoundRect(
                color = keyFill(key, isPressed, shiftState, colors),
                topLeft = Offset(slot.left + gap / 2, slot.top + gap / 2),
                size = Size(slot.right - slot.left - gap, slot.bottom - slot.top - gap),
                cornerRadius = radius,
            )
            textCache.label(slot.index, key)?.let { layout ->
                drawText(
                    layout,
                    color = keyContent(key, isPressed, shiftState, colors),
                    topLeft =
                        Offset(
                            slot.centerX - layout.size.width / 2f,
                            slot.centerY - layout.size.height / 2f,
                        ),
                )
            }
            textCache.hint(slot.index, key)?.let { layout ->
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

/**
 * Measured key labels and hints of the geometry being drawn, so a press or release redraw (and a
 * shift change, for the hints) re-measures nothing. Measuring does not depend on colours, so the
 * cache is keyed by what does: geometry (layer and size), shift state, Enter label, and density.
 * Draw-phase only, not thread-safe.
 */
private class KeyTextCache(
    private val measurer: TextMeasurer,
    private val charStyle: TextStyle,
    private val modifierStyle: TextStyle,
    private val hintStyle: TextStyle,
) {
    private var geometry: KeyboardGeometry? = null
    private var shift = ShiftState.Off
    private var enterLabel = ""
    private var density = 0f
    private var fontScale = 0f
    private var labels = arrayOfNulls<TextLayoutResult>(0)
    private var hints = arrayOfNulls<TextLayoutResult>(0)

    /** Drops whatever is no longer valid for the state about to be drawn. */
    fun prepare(geometry: KeyboardGeometry, shift: ShiftState, enterLabel: String, d: Density) {
        val count = geometry.slots.size
        if (geometry !== this.geometry || d.density != density || d.fontScale != fontScale) {
            labels = arrayOfNulls(count)
            hints = arrayOfNulls(count)
            this.geometry = geometry
            density = d.density
            fontScale = d.fontScale
        } else if (shift != this.shift || enterLabel != this.enterLabel) {
            labels = arrayOfNulls(count)
        }
        this.shift = shift
        this.enterLabel = enterLabel
    }

    fun label(index: Int, key: ResolvedKey): TextLayoutResult? {
        labels[index]?.let {
            return it
        }
        val text = keyLabel(key, shift, enterLabel)
        if (text.isEmpty()) return null
        val style = if (key.style == KeyStyle.Character) charStyle else modifierStyle
        return measurer.measure(text, style).also { labels[index] = it }
    }

    fun hint(index: Int, key: ResolvedKey): TextLayoutResult? {
        hints[index]?.let {
            return it
        }
        val text = key.hint ?: return null
        return measurer.measure(text, hintStyle).also { hints[index] = it }
    }
}

private fun feedbackKind(action: KeyAction): FeedbackKind =
    when (action) {
        KeyAction.Backspace -> FeedbackKind.Delete
        KeyAction.Space -> FeedbackKind.Space
        KeyAction.Enter -> FeedbackKind.Return
        else -> FeedbackKind.Standard
    }
