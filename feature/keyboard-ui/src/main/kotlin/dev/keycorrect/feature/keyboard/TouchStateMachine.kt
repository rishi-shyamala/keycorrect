package dev.keycorrect.feature.keyboard

import android.util.Log
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeySlot
import dev.keycorrect.core.layout.KeyStyle
import dev.keycorrect.core.layout.KeyboardGeometry

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

    /** A key was committed (tap, repeat or popup entry). Never called for the shift key. */
    fun onKey(action: KeyAction)

    /** The shift key went down. It acts on press, not release, so it can chord. */
    fun onShiftDown()

    /** The shift key came up, or its touch was lost. */
    fun onShiftUp()
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
 * - A second finger going down dismisses an open popup; the first finger's release then types
 *   nothing.
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
    private class Press(val slot: KeySlot, var x: Float) {
        /** When the long-press popup opens or the next repeat fires; null when neither is due. */
        var deadline: Long? = null

        /** [deadline] is a repeat rather than the popup. */
        var repeating = false

        /** Set when a popup was cancelled: releasing then types nothing. */
        var cancelled = false

        val isShift: Boolean
            get() = slot.key.action == KeyAction.Shift
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
            if (press.repeating) {
                // Fire once per wake-up, not once per missed interval, so a stalled main thread
                // doesn't delete a burst of characters when it catches up.
                press.deadline = now + REPEAT_INTERVAL_MS
                hostCall { host.onKey(press.slot.key.action) }
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
        val press = Press(slot, x)
        // A second finger dismisses an open popup; it then types normally.
        cancelPopup()
        presses[id] = press
        publishPressed()
        if (host.showPreviews && key.style == KeyStyle.Character) touch.preview = slot
        hostCall { host.onPress(key.action) }
        when {
            press.isShift -> hostCall { host.onShiftDown() }
            key.repeatable -> {
                hostCall { host.onKey(key.action) }
                press.repeating = true
                press.deadline = clock() + REPEAT_START_DELAY_MS
            }
            key.popup.isNotEmpty() -> press.deadline = clock() + host.longPressDelayMs
        }
    }

    fun onMove(id: Long, x: Float, y: Float) {
        onTimer()
        val press = presses[id] ?: return
        press.x = x
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
            popup != null -> {
                touch.popup = null
                hostCall { host.onKey(popup.entries[popup.highlighted].action) }
            }
            press.cancelled -> Unit
            !slot.key.repeatable -> hostCall { host.onKey(slot.key.action) }
        }
    }

    /** The touch [id] was lost without a release: forget it and type nothing. */
    fun cancel(id: Long) {
        val press = presses.remove(id) ?: return
        release(press)
        if (touch.popup?.owner == id) touch.popup = null
        if (press.isShift) hostCall { host.onShiftUp() }
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
        val panel = PopupPanel.above(slot, slot.key.popup.size, geometry.width)
        touch.preview = null
        touch.popup = PopupState(id, slot, panel, slot.key.popup, panel.indexAt(press.x))
        hostCall { host.onPopupOpened() }
    }

    private companion object {
        const val TAG = "TouchStateMachine"
    }
}
