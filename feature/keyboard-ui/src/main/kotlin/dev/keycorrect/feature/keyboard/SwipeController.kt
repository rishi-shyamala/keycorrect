package dev.keycorrect.feature.keyboard

import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.feature.keyboard.prediction.PredictiveInput
import dev.keycorrect.feature.keyboard.prediction.SwipeCase

/**
 * The controller side of gesture typing (docs/PHASE5.md): turns the touch layer's swipe events
 * ([KeyGestures.onSwipeStart] and friends) into decoder requests and a commit. Main thread only.
 *
 * Previews: while the finger moves, at most one request per [PREVIEW_INTERVAL_MS] goes to the
 * decoder thread, each carrying a copy of the path so far
 * ([dev.keycorrect.feature.keyboard.SwipePath.toGestureInput], the only allocation per preview).
 * [PredictiveInput.swipePreview] advances the decoder sequence with every request, which is what
 * makes the older ones stale, so the strip only shows the newest answer. A move that adds no point
 * since the last request is skipped.
 *
 * Nothing is typed until the finger lifts ([onEnd]); a cancel ([onCancel]) types nothing.
 */
internal class SwipeController(
    private val input: PredictiveInput,
    private val clock: () -> Long,
    /** Gesture typing is on for the current field ([KeyboardController.swipeTypingActive]). */
    private val enabled: () -> Boolean,
    /** How the word is cased by the shift state right now. */
    private val case: () -> SwipeCase,
    /** A word was typed by a swipe: the controller consumes shift and refreshes auto-caps. */
    private val onCommitted: () -> Unit,
) {
    private var active = false
    private var lastRequestAt = 0L
    private var lastRequestSize = 0

    fun onStart(path: SwipePath) {
        active = false
        if (!enabled()) return
        active = input.swipeBegin()
        if (active) request(path, clock())
    }

    fun onMove(path: SwipePath) {
        if (!active) return
        val now = clock()
        if (now - lastRequestAt < PREVIEW_INTERVAL_MS || path.size == lastRequestSize) return
        request(path, now)
    }

    fun onEnd(path: SwipePath) {
        if (!active) return
        active = false
        if (input.swipeEnd(path.toGestureInput(), case())) onCommitted()
    }

    /**
     * Any key (another finger's, or a held key repeating) cancels a swipe in progress, except
     * Shift, which changes no text: the swipe's context would be stale, and "keys win" is the
     * simplest safe rule. The swipe's own lift then does nothing.
     */
    fun onKey(action: KeyAction) {
        if (action != KeyAction.Shift) onCancel()
    }

    fun onCancel() {
        if (!active) return
        active = false
        input.swipeCancel()
    }

    private fun request(path: SwipePath, now: Long) {
        val gesture = path.toGestureInput() ?: return
        lastRequestAt = now
        lastRequestSize = path.size
        input.swipePreview(gesture, case())
    }

    companion object {
        /** The least time between two preview requests (P5-4: about every 50 ms). */
        const val PREVIEW_INTERVAL_MS = 50L
    }
}
