package dev.keycorrect.feature.keyboard

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ColorScheme
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
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
import dev.keycorrect.core.layout.KeySlot
import dev.keycorrect.core.layout.KeyStyle
import dev.keycorrect.core.layout.KeyboardGeometry
import dev.keycorrect.core.layout.KeyboardLayer
import dev.keycorrect.core.layout.ResolvedKey
import dev.keycorrect.core.settings.KeyboardSettings
import kotlinx.coroutines.withTimeoutOrNull

/** Enough for every label of a layer in each shift state, so drawing never re-measures text. */
private const val TEXT_CACHE_SIZE = 256

/** Key icons are drawn this big: about 1.6 times the 16 sp modifier glyphs they replace. */
private const val KEY_ICON_DP = 26f

/** Height of the spacebar's split line as a share of the key's height. */
private const val SPLIT_LINE_HEIGHT = 0.4f

/** Backspace swipe travel per further word, in dp. */
private const val WORD_STEP_DP = 40f

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
    gestures: KeyGestures,
    modifier: Modifier = Modifier,
    terminalModifiers: TerminalModifiers? = null,
    clipboardLongPress: Boolean = false,
    onKeyLongPress: (KeyAction) -> Unit = {},
    swipeTyping: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    val density = LocalDensity.current
    val touchSlop by rememberUpdatedState(LocalViewConfiguration.current.touchSlop)
    val textMeasurer = rememberTextMeasurer(cacheSize = TEXT_CACHE_SIZE)
    val currentOnKey by rememberUpdatedState(onKey)
    val currentOnGeometry by rememberUpdatedState(onGeometry)
    val currentOnShiftDown by rememberUpdatedState(onShiftDown)
    val currentOnShiftUp by rememberUpdatedState(onShiftUp)
    val currentSettings by rememberUpdatedState(settings)
    val currentShowPreviews by rememberUpdatedState(showPreviews)
    val currentGestures by rememberUpdatedState(gestures)
    val currentDensity by rememberUpdatedState(density)
    val currentFeedback by rememberUpdatedState(feedback)
    val currentClipboardLongPress by rememberUpdatedState(clipboardLongPress)
    val currentOnKeyLongPress by rememberUpdatedState(onKeyLongPress)
    val currentSwipeTyping by rememberUpdatedState(swipeTyping)
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

                override val clipboardLongPress: Boolean
                    get() = currentClipboardLongPress

                override fun onKeyLongPress(action: KeyAction) = currentOnKeyLongPress(action)

                override fun onKey(action: KeyAction, touch: TouchPoint?) =
                    currentOnKey(action, touch)

                override fun onShiftDown() = currentOnShiftDown()

                override fun onShiftUp() = currentOnShiftUp()

                override val gestures: KeyGestures
                    get() = currentGestures

                override val swipeTyping: Boolean
                    get() = currentSwipeTyping && currentSettings.gestureTyping

                override val swipeTrail: Boolean
                    get() = currentSettings.gestureTrail

                override val spacebarCursor: Boolean
                    get() = currentSettings.spacebarCursor

                override val backspaceSwipe: Boolean
                    get() = currentSettings.backspaceSwipe

                override val slopPx: Float
                    get() = touchSlop

                override val joystickSpeedScale: Float
                    get() = currentSettings.cursorSpeed.joystickScale

                override val wordStepPx: Float
                    get() = currentDensity.density * WORD_STEP_DP

                override fun onGestureTick() = currentFeedback.haptic(view, currentSettings)
            }
        }

    val drawer = remember(textCache) { KeyDrawer(textCache) }
    // Two layers: every key unpressed in one (redrawn only when the layer, shift, size, theme or
    // settings change), and just the held keys over it in the other, so a press or release redraws
    // those few keys and no label. Each has its own graphicsLayer so its invalidation stays there.
    Box(modifier = modifier.fillMaxWidth().height(settings.keyHeightDp.dp * heightUnits)) {
        Canvas(
            modifier =
                Modifier.fillMaxSize()
                    .graphicsLayer()
                    .onSizeChanged { size = it }
                    .onGloballyPositioned { touch.surfaceOrigin = it.positionInWindow() }
                    .pointerInput(geometry) {
                        val machine =
                            TouchStateMachine(geometry, touch, host, SystemClock::uptimeMillis)
                        touch.reset()
                        try {
                            awaitPointerEventScope {
                                while (true) {
                                    // Wake for a pending long press or repeat even if no event
                                    // comes.
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
                                                machine.onDown(
                                                    id,
                                                    change.position.x,
                                                    change.position.y,
                                                )
                                            // Even a release another handler consumed ends the
                                            // press.
                                            change.changedToUpIgnoreConsumed() -> machine.onUp(id)
                                            change.pressed && change.positionChanged() ->
                                                machine.onMove(
                                                    id,
                                                    change.position.x,
                                                    change.position.y,
                                                )
                                        }
                                        change.consume()
                                    }
                                    // A finger that vanished without a release must not stay
                                    // pressed.
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
            drawer.set(shift, enterLabel, colors, terminalModifiers)
            drawer.begin(this, geometry, settings)
            for (slot in geometry.slots) {
                if (!slot.key.isSpacer) drawKey(drawer, slot, isPressed = false)
            }
        }
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            val pressed = touch.pressed
            if (pressed.isEmpty()) return@Canvas
            drawer.set(shift, enterLabel, colors, terminalModifiers)
            drawer.begin(this, geometry, settings)
            val slots = geometry.slots
            for (index in pressed) {
                val slot = slots.getOrNull(index)
                if (slot != null && !slot.key.isSpacer) {
                    // Paint the keyboard background over the key first, so the unpressed key below
                    // leaves no fringe at the pressed one's rounded corners.
                    drawRect(
                        colors.surfaceContainer,
                        Offset(slot.left, slot.top),
                        Size(slot.right - slot.left, slot.bottom - slot.top),
                    )
                    drawKey(drawer, slot, isPressed = true)
                }
            }
        }
    }
}

/**
 * What drawing a key needs that does not change between keys (or frames, unless the settings do),
 * worked out once per frame by [begin] so the per-key draw allocates nothing. UI thread only.
 */
private class KeyDrawer(val text: KeyTextCache) {
    var gap = 0f
    var iconPx = 0f
    var splitWidth = 0f
    var hintInset = 0f
    var radius = CornerRadius.Zero
    var spaceSplit = false
    var shift = ShiftState.Off
    var enterLabel = ""
    var colors: ColorScheme? = null
    var modifiers: TerminalModifiers? = null

    var shiftOf: () -> ShiftState = { ShiftState.Off }

    /** The inputs that come from composition; [shift] is read in the draw phase, as before. */
    fun set(
        shift: () -> ShiftState,
        enterLabel: String,
        colors: ColorScheme,
        modifiers: TerminalModifiers?,
    ) {
        shiftOf = shift
        this.enterLabel = enterLabel
        this.colors = colors
        this.modifiers = modifiers
    }

    /** Start of a frame: the pixel sizes and the current shift state, read here in draw. */
    fun begin(scope: DrawScope, geometry: KeyboardGeometry, settings: KeyboardSettings) {
        with(scope) {
            gap = settings.keyGapDp.dp.toPx()
            iconPx = KEY_ICON_DP.dp.toPx()
            splitWidth = 1.dp.toPx()
            radius = CornerRadius(KeyCornerRadius.toPx())
            hintInset = 5.dp.toPx()
        }
        spaceSplit = settings.spacebarCursor
        shift = shiftOf()
        text.prepare(geometry, shift, enterLabel, scope)
    }
}

/** Draws one key of the surface: fill, icon or label, the spacebar's split line and the hint. */
private fun DrawScope.drawKey(d: KeyDrawer, slot: KeySlot, isPressed: Boolean) {
    val colors = d.colors ?: return
    val key = slot.key
    val gap = d.gap
    val shiftState = d.shift
    val latch = latchOf(key, d.modifiers)
    drawRoundRect(
        color = keyFill(key, isPressed, shiftState, colors, latch),
        topLeft = Offset(slot.left + gap / 2, slot.top + gap / 2),
        size = Size(slot.right - slot.left - gap, slot.bottom - slot.top - gap),
        cornerRadius = d.radius,
    )
    val icon = keyIconFor(key, shiftState, d.enterLabel)
    if (icon != null) {
        with(KeyIconArt) {
            drawKeyIcon(
                icon,
                keyContent(key, isPressed, shiftState, colors, latch),
                slot.centerX,
                slot.centerY,
                d.iconPx,
            )
        }
    } else {
        d.text.label(slot.index, key)?.let { layout ->
            drawText(
                layout,
                color = keyContent(key, isPressed, shiftState, colors, latch),
                topLeft =
                    Offset(
                        slot.centerX - layout.size.width / 2f,
                        slot.centerY - layout.size.height / 2f,
                    ),
            )
        }
    }
    if (d.spaceSplit && key.action == KeyAction.Space) {
        // The spacebar gestures' split: drags left of it select, right of it move.
        val x = slot.left + (slot.right - slot.left) * SPACE_SELECT_SPLIT
        val half = (slot.bottom - slot.top - gap) * SPLIT_LINE_HEIGHT / 2
        drawLine(
            color = keyContent(key, isPressed, shiftState, colors, latch).copy(alpha = 0.25f),
            start = Offset(x, slot.centerY - half),
            end = Offset(x, slot.centerY + half),
            strokeWidth = d.splitWidth,
        )
    }
    d.text.hint(slot.index, key)?.let { layout ->
        drawText(
            layout,
            color = colors.onSurfaceVariant,
            topLeft =
                Offset(
                    slot.right - gap / 2 - d.hintInset - layout.size.width,
                    slot.top + gap / 2 + d.hintInset / 2,
                ),
        )
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
