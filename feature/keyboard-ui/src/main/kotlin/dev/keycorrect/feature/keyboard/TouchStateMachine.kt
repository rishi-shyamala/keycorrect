package dev.keycorrect.feature.keyboard

import android.util.Log
import dev.keycorrect.core.engineapi.TouchPoint
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeySlot
import dev.keycorrect.core.layout.KeyStyle
import dev.keycorrect.core.layout.KeyboardGeometry
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/**
 * Where the space bar splits, as a fraction of its width: a drag starting left of it selects, right
 * of it moves the cursor. The renderer draws a faint line there.
 */
internal const val SPACE_SELECT_SPLIT = 0.5f

/** Delay before a held repeatable key (backspace) starts repeating. */
internal const val REPEAT_START_DELAY_MS = 400L

/** Gap between repeats once a held key is repeating. */
internal const val REPEAT_INTERVAL_MS = 50L

/**
 * A press on a letter key becomes a swipe (gesture typing) once the finger has travelled more than
 * this share of that key's width from where it went down, and is over a different key.
 */
internal const val SWIPE_START_KEY_WIDTHS = 0.6f

/** The joystick ring's radius, in heights of the key row the spacebar drag started on. */
internal const val JOYSTICK_RING_KEY_HEIGHTS = 1.2f

/**
 * Past the ring the cursor moves at a rate that grows with the distance past it, reaching its top
 * value at this many key heights beyond the ring.
 */
internal const val JOYSTICK_FULL_SPEED_KEY_HEIGHTS = 2f

/** Characters a second just past the ring, and at [JOYSTICK_FULL_SPEED_KEY_HEIGHTS] past it. */
internal const val JOYSTICK_CHARS_MIN_PER_S = 8f
internal const val JOYSTICK_CHARS_MAX_PER_S = 40f

/** Lines a second just past the ring, and at full speed (vertical, the larger offset wins). */
internal const val JOYSTICK_LINES_MIN_PER_S = 2f
internal const val JOYSTICK_LINES_MAX_PER_S = 12f

/** Time between cursor steps while the finger is held past the ring (the machine's timer). */
internal const val JOYSTICK_TICK_MS = 25L

/** A tick that arrives late counts at most this much time, so a stalled thread does not burst. */
internal const val JOYSTICK_MAX_ELAPSED_MS = 100L

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

    /**
     * Gesture typing is on for this field (setting and field type, docs/PHASE5.md): a press on a
     * letter key that travels far enough becomes a swipe ([KeyGestures.onSwipeStart]).
     */
    val swipeTyping: Boolean
        get() = false

    /** Draw the swipe's trail (the machine publishes the path in [KeyboardTouchState.trail]). */
    val swipeTrail: Boolean
        get() = false

    /** Spacebar drag gestures are on; off, space is a plain key. */
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
 * - Swipe (with [TouchHost.swipeTyping]): a press on a letter key that travels more than
 *   [SWIPE_START_KEY_WIDTHS] of that key's width and is over another key becomes a swipe: the popup
 *   timer and preview are dropped, the points (the down point, then every move, recorded from the
 *   start even while it was still an ordinary press) go into one [SwipePath] reported through
 *   [KeyGestures.onSwipeStart]/[KeyGestures.onSwipeMove], and release ends it with
 *   [KeyGestures.onSwipeEnd] and types nothing. A lost touch is [KeyGestures.onSwipeCancel]. Other
 *   fingers going down meanwhile are ignored. Before the threshold it is an ordinary press.
 * - Space (with [TouchHost.spacebarCursor]): moving past [TouchHost.slopPx] is a drag
 *   ([CursorDragTracker]) and no space is typed. A drag that went down on the left part of the key
 *   (before [SPACE_SELECT_SPLIT] of its width) selects; on the right part it moves the cursor.
 *   Without a drag, releasing types a space however long it was held (the editing panel has its own
 *   button in the strip). A drag shows a joystick ([KeyboardTouchState.joystick]) centred on the
 *   touch-down point: inside its ring the [CursorDragTracker] steps by position; once the finger is
 *   past the ring (anywhere, even outside the keyboard) the cursor moves at a rate along the larger
 *   of the two offsets, one tick every [JOYSTICK_TICK_MS] from [onTimer] so it goes on while the
 *   finger is still; back inside the ring the tracker is re-anchored at the finger (no jump).
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

        /** Space drag: the joystick drawn for it. */
        var joystick: JoystickState? = null

        /** Space drag: the finger is past the ring, so [deadline] is the next rate tick. */
        var rate = false

        /** Rate movement: the clock of the last tick, and the fractions of a step not yet sent. */
        var rateTime = 0L
        var rateX = 0f
        var rateY = 0f

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
        Swipe,
    }

    private val presses = HashMap<Long, Press>()

    /** The points of the swipe in progress; cleared when it ends or is cancelled. */
    private val swipePath = SwipePath()

    /** The pointer whose press is the swipe in progress. */
    private var swipeId: Long? = null

    /**
     * The pointer whose letter-key press is being recorded into [swipePath] while it is still an
     * ordinary press, so that a swipe, once recognised, already holds the start of the gesture. Its
     * path is cleared when the press ends without becoming a swipe.
     */
    private var bufferId: Long? = null

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
            } else if (press.rate) {
                rateTick(press, now)
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
        // A swipe in progress ignores other fingers: they neither end it nor type.
        if (swipeId != null && swipeId != id) return
        if (presses.containsKey(id)) cancel(id)
        val slot = geometry.hitTest(x, y) ?: return
        val key = slot.key
        val press = Press(slot, x, y, x, y, clock())
        // A second finger dismisses an open popup; it then types normally.
        cancelPopup()
        presses[id] = press
        startBuffer(id, press)
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
            else -> if (host.swipeTyping) moveLetter(id, press)
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
        dropBuffer(id, press)
        val slot = press.slot
        val popup = touch.popup?.takeIf { it.owner == id }
        when {
            press.isShift -> hostCall { host.onShiftUp() }
            press.gesture == Gesture.Swipe -> endSwipe(end = true)
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
        dropBuffer(id, press)
        if (touch.popup?.owner == id) touch.popup = null
        if (press.isShift) hostCall { host.onShiftUp() }
        if (press.gesture == Gesture.BackspaceSwipe) {
            hostCall { host.gestures.onBackspaceSwipeEnd(false) }
        }
        if (press.gesture == Gesture.Swipe) endSwipe(end = false)
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
            val joystick = JoystickState(press.downX, press.downY, slot.bottom - slot.top)
            press.joystick = joystick
            touch.joystick = joystick
        }
        if (steppedByPosition(press)) reportPositionSteps(press)
    }

    private fun reportPositionSteps(press: Press) {
        val steps = press.drag?.move(press.x, press.y, clock()) ?: return
        val select = press.gesture == Gesture.SelectDrag
        hostCall { host.gestures.onCursorDrag(steps.dx, steps.dy, select) }
        hostCall { host.onGestureTick() }
    }

    /**
     * Follows the finger with the joystick's knob and switches between rate and position movement.
     * True when the finger is inside the ring, so the drag steps by position.
     */
    private fun steppedByPosition(press: Press): Boolean {
        val joystick = press.joystick ?: return true
        joystick.moveKnob(press.x, press.y)
        val beyond = joystick.distance(press.x, press.y) > joystick.radius
        if (beyond && !press.rate) startRate(press, joystick)
        if (!beyond && press.rate) endRate(press, joystick)
        return !beyond
    }

    /** The finger crossed the ring: from now on [rateTick] moves the cursor. */
    private fun startRate(press: Press, joystick: JoystickState) {
        press.rate = true
        press.rateX = 0f
        press.rateY = 0f
        press.rateTime = clock()
        press.deadline = press.rateTime + JOYSTICK_TICK_MS
        joystick.engaged = true
        hostCall { host.onGestureTick() }
    }

    /** The finger is back inside the ring: position-based stepping again, anchored right here. */
    private fun endRate(press: Press, joystick: JoystickState) {
        press.rate = false
        press.deadline = null
        joystick.engaged = false
        press.drag =
            CursorDragTracker(host.cursorStepPx, host.lineStepPx, press.x, press.y, clock())
    }

    /**
     * Moves the cursor by the steps due since the last tick: along the larger offset from the
     * joystick's centre, at a rate that grows from the minimum just past the ring to the maximum at
     * [JOYSTICK_FULL_SPEED_KEY_HEIGHTS] beyond it. Fractions of a step carry over to the next tick.
     */
    private fun rateTick(press: Press, now: Long) {
        val joystick = press.joystick ?: return
        val elapsed = (now - press.rateTime).coerceIn(0L, JOYSTICK_MAX_ELAPSED_MS)
        press.rateTime = now
        press.deadline = now + JOYSTICK_TICK_MS
        val dx = press.x - joystick.centerX
        val dy = press.y - joystick.centerY
        val past = joystick.distance(press.x, press.y) - joystick.radius
        val t = (past / (joystick.keyHeight * JOYSTICK_FULL_SPEED_KEY_HEIGHTS)).coerceIn(0f, 1f)
        val seconds = elapsed / MS_PER_SECOND
        if (abs(dx) >= abs(dy)) {
            val perSecond =
                JOYSTICK_CHARS_MIN_PER_S + (JOYSTICK_CHARS_MAX_PER_S - JOYSTICK_CHARS_MIN_PER_S) * t
            press.rateX += sign(dx) * perSecond * seconds
            press.rateY = 0f
        } else {
            val perSecond =
                JOYSTICK_LINES_MIN_PER_S + (JOYSTICK_LINES_MAX_PER_S - JOYSTICK_LINES_MIN_PER_S) * t
            press.rateY += sign(dy) * perSecond * seconds
            press.rateX = 0f
        }
        val stepsX = press.rateX.toInt()
        val stepsY = press.rateY.toInt()
        if (stepsX == 0 && stepsY == 0) return
        press.rateX -= stepsX
        press.rateY -= stepsY
        val select = press.gesture == Gesture.SelectDrag
        hostCall { host.gestures.onCursorDrag(stepsX, stepsY, select) }
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

    /** A letter key's press that has gone far enough onto another key becomes the swipe. */
    private fun moveLetter(id: Long, press: Press) {
        if (press.gesture == Gesture.Swipe) {
            swipePath.add(press.x, press.y, clock())
            touch.trailVersion++
            hostCall { host.gestures.onSwipeMove(swipePath) }
        } else if (bufferId == id) {
            swipePath.add(press.x, press.y, clock())
            if (canStartSwipe(id, press)) startSwipe(id, press)
        }
    }

    private fun startBuffer(id: Long, press: Press) {
        if (!host.swipeTyping || swipeId != null || bufferId != null) return
        if (!isLetterKey(press.slot)) return
        bufferId = id
        swipePath.clear()
        swipePath.add(press.downX, press.downY, press.downTime)
    }

    /** The press ended as an ordinary press: forget what was recorded for it. */
    private fun dropBuffer(id: Long, press: Press) {
        if (bufferId == id && press.gesture != Gesture.Swipe) {
            bufferId = null
            swipePath.clear()
        }
    }

    private fun canStartSwipe(id: Long, press: Press): Boolean {
        val slot = press.slot
        if (press.gesture != Gesture.None || swipeId != null || touch.popup?.owner == id) {
            return false
        }
        val reach = (slot.right - slot.left) * SWIPE_START_KEY_WIDTHS
        return isLetterKey(slot) &&
            hypot(press.x - press.downX, press.y - press.downY) > reach &&
            geometry.hitTest(press.x, press.y)?.let { it.index != slot.index } == true
    }

    private fun startSwipe(id: Long, press: Press) {
        press.gesture = Gesture.Swipe
        press.deadline = null
        press.repeating = false
        press.keyLongPressPending = false
        swipeId = id
        touch.preview = null
        touch.popup = null
        publishPressed()
        if (host.swipeTrail) touch.trail = swipePath
        touch.trailVersion++
        hostCall { host.gestures.onSwipeStart(swipePath) }
    }

    private fun endSwipe(end: Boolean) {
        swipeId = null
        bufferId = null
        touch.trail = null
        if (end) hostCall { host.gestures.onSwipeEnd(swipePath) }
        else {
            hostCall { host.gestures.onSwipeCancel() }
        }
        swipePath.clear()
    }

    private fun isLetterKey(slot: KeySlot): Boolean {
        val action = slot.key.action
        return slot.key.style == KeyStyle.Character &&
            action is KeyAction.Text &&
            action.text.firstOrNull()?.isLetter() == true
    }

    private fun release(press: Press) {
        press.deadline = null
        if (press.joystick != null) {
            press.joystick = null
            touch.joystick = null
        }
        publishPressed()
        if (touch.preview?.index == press.slot.index) touch.preview = null
    }

    private fun publishPressed() {
        touch.pressed =
            presses.values.filter { it.gesture != Gesture.Swipe }.mapTo(HashSet()) { it.slot.index }
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
        const val MS_PER_SECOND = 1000f
    }
}
