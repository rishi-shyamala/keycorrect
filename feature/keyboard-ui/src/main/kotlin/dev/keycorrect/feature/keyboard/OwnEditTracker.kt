package dev.keycorrect.feature.keyboard

/**
 * Recognises the `onUpdateSelection` callbacks caused by the keyboard's own edits.
 *
 * The platform reports every cursor change asynchronously, including the ones our own edits cause,
 * and the controller has already re-evaluated auto-capitalisation right after such an edit. To skip
 * the redundant query without trusting a callback that may never come, each edit sent counts as one
 * *pending* callback and a callback with a bare cursor consumes one. Nothing guarantees the pairing
 * (a delete at the start of the field changes nothing, rapid edits can be coalesced into fewer
 * callbacks), so a miscount must heal itself and never hide an external change for long:
 * - pending edits expire [windowMs] after the most recent edit, so a stale count is dropped;
 * - a callback with a selection range is the user selecting text, never ours: it clears the count;
 * - the count is capped at [MAX_PENDING].
 *
 * The worst case is a cursor move by the user or app landing within [windowMs] of typing while a
 * count is still pending: auto-capitalisation then catches up on the next edit. A wrongly counted
 * edit only ever costs the old behaviour, one extra query.
 */
internal class OwnEditTracker(
    private val clock: () -> Long,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    private var pending = 0
    private var lastEditAt = 0L

    /** An edit was just sent to the editor. */
    fun onEdit() {
        if (pending < MAX_PENDING) pending++
        lastEditAt = clock()
    }

    /**
     * A selection update arrived. Returns true if it is explained by one of our pending edits (and
     * consumes it), false if it must be treated as an external change.
     */
    fun consumeIfOwn(selectionCollapsed: Boolean): Boolean {
        if (pending == 0) return false
        if (!selectionCollapsed || clock() - lastEditAt > windowMs) {
            pending = 0
            return false
        }
        pending--
        return true
    }

    /** Forgets everything, e.g. for a newly focused field. */
    fun reset() {
        pending = 0
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 250L
        const val MAX_PENDING = 8
    }
}
