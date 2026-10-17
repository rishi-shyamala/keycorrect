package dev.keycorrect.feature.keyboard

import android.util.Log
import dev.keycorrect.core.engineapi.TouchPoint
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeySlot
import dev.keycorrect.core.layout.KeyStyle
import dev.keycorrect.core.layout.KeyboardGeometry
import kotlin.math.hypot

/**
 * Where the space bar splits, as a fraction of its width: a drag starting left of it selects, right
 * of it moves the cursor. The renderer draws a faint line there.
 */
internal const val SPACE_SELECT_SPLIT = 0.5f

/** Delay before a held repeatable key (backspace) starts repeating. */
internal const val REPEAT_START_DELAY_MS = 400L

/** Gap between repeats once a held key is repeating. */
internal const val REPEAT_INTERVAL_MS = 50L

/** What the touch handling needs from its surroundings. */
internal interface TouchHost {
    /** Whether a key preview bubble is shown for character keys. */
    val showPreviews: Boolean

    /** How long a key with popup entries must be held before the popup opens. */
    val longPressDelayMs: Long

    /** A finger went down on a key: play the press feedback. */
    fun onPress(action: KeyAction)

    /** A long-press popup opened. */
    fun onPopupOpened()

    /**
     * The emoji key opens the clipboard when held (PR C); off, it is a plain key. Read when the
     * finger goes down.
     */
    val clipboardLongPress: Boolean
        get() = false

    /**
     * A key with a long press of its own (the emoji key, see [clipboardLongPress]) was held for
     * [longPressDelayMs]: releasing then does not also tap it. The haptic is played first, by the
     * machine, through [onGestureTick].
     */
    fun onKeyLongPress(action: KeyAction) = Unit

    /**
     * A key was committed (tap, repeat or popup entry). Never called for the shift key. [touch] is
     * where and when the finger went down for a plain tap, and null for repeats and popup entries
     * (whose position says nothing about the key meant).
     */
    fun onKey(action: KeyAction, touch: TouchPoint?)

    /** The shift key went down. It acts on press, not release, so it can chord. */
    fun onShiftDown()

    /** The shift key came up, or its touch was lost. */
    fun onShiftUp()

    /** Receives the spacebar and backspace gestures. */
    val gestures: KeyGestures
        get() = KeyGestures.None

    /** Spacebar drag/hold gestures are on; off, space is a plain key. */
    /**
     * Gesture typing is on for this field (setting and field type, docs/PHASE5.md): a press on a
     * letter key that travels far enough becomes a swipe ([KeyGestures.onSwipeStart]).
     */
    val swipeTyping: Boolean
        get() = false

    val spacebarCursor: Boolean
        get() = false

    /** Backspace swipe is on; off, backspace only taps and repeats. */
    val backspaceSwipe: Boolean
        get() = false

    /** Finger travel (px) before a press becomes a drag or swipe. */
    val slopPx: Float
        get() = 0f

    /** Spacebar travel (px) per character at a slow drag. */
    val cursorStepPx: Float
        get() = 1f

    /** Spacebar travel (px) per line. */
    val lineStepPx: Float
        get() = 1f

    /** Backspace swipe travel (px) per further word. */
    val wordStepPx: Float
        get() = 1f

    /** A light haptic tick: a drag step, a swipe word, or a hold armed. */
    fun onGestureTick() = Unit
}

/**
 * The multi-touch state machine behind [KeyboardSurface], free of Compose input types so it can be
 * unit-tested: feed it pointer events and it emits key actions to [host] and updates the transient
 * [touch] state the drawing reads.
 *
 * - Character and other keys commit on release; backspace ([ResolvedKey.repeatable]) commits on
 *   press and auto-repeats; shift is reported on press and release without committing anything.
 * - A key with popup entries opens its popup after [TouchHost.longPressDelayMs]; releasing over a
 *   cell commits that entry, and moving far below the key cancels it.
 * - The emoji key (with [TouchHost.clipboardLongPress]) is long-pressed after the same delay: a
 *   haptic tick and [TouchHost.onKeyLongPress]; the release then types nothing.
 * - A second finger going down dismisses an open popup; the first finger's release then types
 *   nothing. Other fingers never affect a drag or swipe in progress.
 * - Space (with [TouchHost.spacebarCursor]): moving past [TouchHost.slopPx] is a drag
 *   ([CursorDragTracker]) and no space is typed. A drag that went down on the left part of the key
 *   (before [SPACE_SELECT_SPLIT] of its width) selects; on the right part it moves the cursor.
 *   Without a drag, releasing types a space however long it was held (the editing panel has its own
 *   button in the strip).
 * - Backspace (with [TouchHost.backspaceSwipe]): it still deletes on press. Moving left past the
 *   slop before the first repeat starts a swipe: the repeat stops, words = 1 + the extra leftward
 *   travel / [TouchHost.wordStepPx] (0 back within the slop), and release commits if words > 0. A
 *   lost touch ends a drag or swipe without committing.
 *
 * A host callback that throws (e.g. an input connection that died mid-keystroke) is logged and
 * dropped: it must not escape into the pointer-input loop and end it, which would leave the
 * keyboard dead to touch. The machine updates its own state before calling out, so the touch state
 * stays consistent whatever the host does.
 *
 * Timers are not coroutines: [nextDeadline] says when [onTimer] is next due and every event first
 * runs whatever is already due, so ordering follows [clock] and nothing can outlive the machine.
 * Call [cancelAll] when the owner goes away.
 */
internal class TouchStateMachine(
    private val geometry: KeyboardGeometry,
    private val touch: KeyboardTouchState,
    private val host: TouchHost,
    private val clock: () -> Long,
) {
    /** A finger on a key. */
    private class Press(
        val slot: KeySlot,
        var x: Float,
        var y: Float,
        val downX: Float,
        val downY: Float,
        val downTime: Long,
    ) {
        /** Where and when this press started, in surface pixels and clock milliseconds. */
        fun touchPoint() = TouchPoint(downX, downY, downTime)

        /** When the long-press popup opens or the next repeat fires; null when neither is due. */
        var deadline: Long? = null

        /** [deadline] is a repeat rather than the popup. */
        var repeating = false

        /** Set when a popup was cancelled: releasing then types nothing. */
        var cancelled = false

        /** Where a space or backspace press has got to; [Gesture.None] for a plain press. */
        var gesture = Gesture.None

        /** Emoji key: [deadline] is its long press (the clipboard). */
        var keyLongPressPending = false

        /** A repeat has fired, so a backspace can no longer become a swipe. */
        var repeatFired = false

        /** Space: set while the finger is dragging. */
        var drag: CursorDragTracker? = null

        /** Backspace swipe: the word count last reported. */
        var swipeWords = 0

        val isShift: Boolean
            get() = slot.key.action == KeyAction.Shift
    }

    private enum class Gesture {
        None,
        CursorDrag,
        KeyLongPress,
        SelectDrag,
        BackspaceSwipe,
    }

    private val presses = HashMap<Long, Press>()

    /**
     * Milliseconds from [clock] at which [onTimer] next has work, or null if nothing is pending.
     */
    fun nextDeadline(): Long? = presses.values.mapNotNull { it.deadline }.minOrNull()

    /** Runs every long-press and repeat that is due. */
    fun onTimer() {
        val now = clock()
        // Copy: a host callback must not be able to invalidate the iteration.
        for ((id, press) in presses.entries.toList()) {
            val deadline = press.deadline ?: continue
            if (deadline > now || presses[id] !== press) continue
            if (press.keyLongPressPending) {
                press.deadline = null
                press.keyLongPressPending = false
                press.gesture = Gesture.KeyLongPress
                touch.preview = null
                hostCall { host.onGestureTick() }
                hostCall { host.onKeyLongPress(press.slot.key.action) }
            } else if (press.repeating) {
                press.repeatFired = true
                // Fire once per wake-up, not once per missed interval, so a stalled main thread
                // doesn't delete a burst of characters when it catches up.
                press.deadline = now + REPEAT_INTERVAL_MS
                hostCall { host.onKey(press.slot.key.action, null) }
            } else {
                press.deadline = null
                openPopup(id, press)
            }
        }
    }

    fun onDown(id: Long, x: Float, y: Float) {
        onTimer()
        if (presses.containsKey(id)) cancel(id)
        val slot = geometry.hitTest(x, y) ?: return
        val key = slot.key
        val press = Press(slot, x, y, x, y, clock())
        // A second finger dismisses an open popup; it then types normally.
        cancelPopup()
        presses[id] = press
        publishPressed()
        if (host.showPreviews && key.style == KeyStyle.Character) touch.preview = slot
        hostCall { host.onPress(key.action) }
        when {
            press.isShift -> hostCall { host.onShiftDown() }
            key.repeatable -> {
                hostCall { host.onKey(key.action, null) }
                press.repeating = true
                press.deadline = clock() + REPEAT_START_DELAY_MS
            }
            key.action == KeyAction.Emoji && host.clipboardLongPress -> {
                press.keyLongPressPending = true
                press.deadline = clock() + host.longPressDelayMs
            }
            key.popup.isNotEmpty() -> press.deadline = clock() + host.longPressDelayMs
        }
    }

    fun onMove(id: Long, x: Float, y: Float) {
        onTimer()
        val press = presses[id] ?: return
        press.x = x
        press.y = y
        when (press.slot.key.action) {
            KeyAction.Space -> if (host.spacebarCursor) moveSpace(press)
            KeyAction.Backspace -> if (host.backspaceSwipe) moveBackspace(press)
            else -> Unit
        }
        val popup = touch.popup?.takeIf { it.owner == id } ?: return
        if (isFarBelow(y, popup.slot)) {
            cancelPopup()
        } else {
            popup.highlighted = popup.panel.indexAt(x)
        }
    }

    fun onUp(id: Long) {
        onTimer()
        val press = presses.remove(id) ?: return
        release(press)
        val slot = press.slot
        val popup = touch.popup?.takeIf { it.owner == id }
        when {
            press.isShift -> hostCall { host.onShiftUp() }
            press.gesture == Gesture.BackspaceSwipe ->
                hostCall { host.gestures.onBackspaceSwipeEnd(press.swipeWords > 0) }
            press.gesture != Gesture.None -> Unit
            popup != null -> {
                touch.popup = null
                hostCall { host.onKey(popup.entries[popup.highlighted].action, null) }
            }
            press.cancelled -> Unit
            !slot.key.repeatable -> hostCall { host.onKey(slot.key.action, press.touchPoint()) }
        }
    }

    /** The touch [id] was lost without a release: forget it and type nothing. */
    fun cancel(id: Long) {
        val press = presses.remove(id) ?: return
        release(press)
        if (touch.popup?.owner == id) touch.popup = null
        if (press.isShift) hostCall { host.onShiftUp() }
        if (press.gesture == Gesture.BackspaceSwipe) {
            hostCall { host.gestures.onBackspaceSwipeEnd(false) }
        }
    }

    /**
     * Drops every touch for which [isDown] is false, for events that no longer mention a finger we
     * thought was down (its release was missed).
     */
    fun retainOnly(isDown: (Long) -> Boolean) {
        if (presses.isEmpty()) return
        for (id in presses.keys.toList()) if (!isDown(id)) cancel(id)
    }

    /** Cancels every touch and clears the transient state. */
    fun cancelAll() {
        for (id in presses.keys.toList()) cancel(id)
        touch.reset()
    }

    private inline fun hostCall(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // Type only: exception messages from the editor connection could contain user text.
            Log.w(TAG, "Host callback failed: ${e.javaClass.name}")
        }
    }

    private fun moveSpace(press: Press) {
        val drag = press.drag
        if (drag == null) {
            if (hypot(press.x - press.downX, press.y - press.downY) <= host.slopPx) return
            // Past the slop: the half of the space bar the finger went down on picks the drag.
            val slot = press.slot
            val split = slot.left + (slot.right - slot.left) * SPACE_SELECT_SPLIT
            press.gesture = if (press.downX < split) Gesture.SelectDrag else Gesture.CursorDrag
            press.drag =
                CursorDragTracker(
                    host.cursorStepPx,
                    host.lineStepPx,
                    press.downX,
                    press.downY,
                    press.downTime,
                )
        }
        val steps = press.drag?.move(press.x, press.y, clock()) ?: return
        val select = press.gesture == Gesture.SelectDrag
        hostCall { host.gestures.onCursorDrag(steps.dx, steps.dy, select) }
        hostCall { host.onGestureTick() }
    }

    private fun moveBackspace(press: Press) {
        val left = press.downX - press.x
        if (press.gesture == Gesture.None && !press.repeatFired && left > host.slopPx) {
            press.gesture = Gesture.BackspaceSwipe
            press.deadline = null
            press.repeating = false
        }
        if (press.gesture == Gesture.BackspaceSwipe) {
            val extra = left - host.slopPx
            val words = if (extra < 0f) 0 else 1 + (extra / host.wordStepPx).toInt()
            if (words != press.swipeWords) {
                press.swipeWords = words
                hostCall { host.gestures.onBackspaceSwipe(words) }
                hostCall { host.onGestureTick() }
            }
        }
    }

    private fun release(press: Press) {
        press.deadline = null
        publishPressed()
        if (touch.preview?.index == press.slot.index) touch.preview = null
    }

    private fun publishPressed() {
        touch.pressed = presses.values.mapTo(HashSet()) { it.slot.index }
    }

    private fun cancelPopup() {
        val popup = touch.popup ?: return
        presses[popup.owner]?.cancelled = true
        touch.popup = null
    }

    private fun openPopup(id: Long, press: Press) {
        val slot = press.slot
        val panel = PopupPanel.above(slot, slot.key.popup.size, geometry.width, geometry.unit)
        touch.preview = null
        touch.popup = PopupState(id, slot, panel, slot.key.popup, panel.indexAt(press.x))
        hostCall { host.onPopupOpened() }
    }

    private companion object {
        const val TAG = "TouchStateMachine"
    }
}
