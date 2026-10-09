package dev.keycorrect.feature.keyboard

/**
 * Recognises the `onUpdateSelection` callbacks caused by the keyboard's own edits.
 *
 * The platform reports every cursor change asynchronously, including the ones our own edits cause,
 * and the controller has already re-evaluated auto-capitalisation right after such an edit. To skip
 * the redundant query without trusting a callback that may never come, each edit sent counts as one
 * *pending* callback and a callback with a bare cursor consumes one. Nothing guarantees the pairing
 * (rapid edits can be coalesced into fewer callbacks), so a miscount must heal itself and never
 * hide an external change for long:
 * - pending edits expire [windowMs] after the most recent edit, so a stale count is dropped;
 * - a callback with a selection range is the user selecting text, never ours: it clears the count;
 * - the count is capped at [MAX_PENDING].
 *
 * Edits known not to move the cursor are not counted at all, so they cannot swallow a later
 * external change: the caller skips Enter that is a key event or an editor action, and [onDelete]
 * skips a delete while the last reported cursor was at position 0 (the only no-op delete knowable
 * without a query; the position is tracked from the callbacks, so a stale value can at worst
 * miscount one edit). A delete whose no-op status is unknown is still counted, which is why the
 * window is short: a miscounted edit can mask an external move for at most [windowMs].
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

    /** The last cursor reported to [consumeIfOwn] was a bare cursor at position 0. */
    private var cursorAtStart = false

    /** An edit that inserts text (so it moves the cursor off position 0) was just sent. */
    fun onEdit() {
        cursorAtStart = false
        count()
    }

    /**
     * A backward delete was just sent. Not counted when the cursor is known to be at the start of
     * the field: nothing is deleted, so no callback follows.
     */
    fun onDelete() {
        if (!cursorAtStart) count()
    }

    private fun count() {
        if (pending < MAX_PENDING) pending++
        lastEditAt = clock()
    }

    /**
     * A selection update arrived. Returns true if it is explained by one of our pending edits (and
     * consumes it), false if it must be treated as an external change. [atStart] is whether the new
     * selection is a bare cursor at position 0.
     */
    fun consumeIfOwn(selectionCollapsed: Boolean, atStart: Boolean = false): Boolean {
        cursorAtStart = selectionCollapsed && atStart
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
        cursorAtStart = false
    }

    companion object {
        /**
         * Long enough for the app's callback to arrive after an edit on a busy main thread, short
         * enough that a miscounted edit does not hide a real cursor move for long.
         */
        const val DEFAULT_WINDOW_MS = 150L
        const val MAX_PENDING = 8
    }
}
