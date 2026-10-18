package dev.keycorrect.feature.keyboard

import dev.keycorrect.core.input.TextEditor

/**
 * The editor's selection and composing region as `onUpdateSelection` reports them. Unknown fields
 * are -1; [composingStart]/[composingEnd] are -1 (or an empty range) when nothing is composing.
 */
internal data class EditorSelection(
    val selStart: Int = -1,
    val selEnd: Int = -1,
    val composingStart: Int = -1,
    val composingEnd: Int = -1,
) {
    val known: Boolean
        get() = selStart >= 0 && selEnd >= 0

    val collapsed: Boolean
        get() = selStart == selEnd

    val composingLength: Int
        get() =
            if (composingStart >= 0 && composingEnd > composingStart) composingEnd - composingStart
            else 0

    /**
     * The same state with a reversed selection or composing region put the right way round (some
     * editors report the anchor before the focus).
     */
    fun normalised(): EditorSelection {
        val selReversed = selStart >= 0 && selEnd >= 0 && selStart > selEnd
        val compReversed = composingStart >= 0 && composingEnd >= 0 && composingStart > composingEnd
        if (!selReversed && !compReversed) return this
        return EditorSelection(
            if (selReversed) selEnd else selStart,
            if (selReversed) selStart else selEnd,
            if (compReversed) composingEnd else composingStart,
            if (compReversed) composingStart else composingEnd,
        )
    }
}

/** How [OwnEditTracker.onUpdate] classified a selection update. */
internal sealed interface UpdateVerdict {
    /**
     * Explained by our own edits (or an echo of the current state). [composingLength] is the
     * editor's composing length when it differs from ours and the update is the latest word on the
     * state (a normalising editor), else -1: the caller then re-reads the word from the editor.
     */
    data class Own(val composingLength: Int = -1) : UpdateVerdict

    /** The cursor or text changed behind our back. */
    data object External : UpdateVerdict
}

/**
 * Tells the `onUpdateSelection` callbacks caused by the keyboard's own edits from external changes
 * by *state*, not by counting.
 *
 * The tracker keeps a model of where the editor's cursor and composing region should be, and every
 * edit sent through a [TrackedEditor] records the state it is expected to produce (one expectation
 * per batch, as the platform reports one update per batch). An update is then classified:
 * 1. It exactly matches a pending expectation: that one and every older one are consumed (older
 *    ones had their echoes dropped or coalesced). The newest match wins, so a late echo of an
 *    earlier state consumes only what it can explain.
 * 2. Otherwise it matches one with a different composing *length* (the same region start, cursor at
 *    its end): an editor that normalises the composing text. It is accepted, and when nothing newer
 *    is pending the caller resyncs its word from the editor.
 * 3. Otherwise all pending expectations are dropped, and the update is still ours if it merely
 *    repeats the current state (a duplicated or late echo) or, while composing, leaves the cursor
 *    at the end of the composing region (case 2 without a pending edit). Anything else, such as a
 *    cursor outside the composing region, a lost region or a different cursor position, is
 *    external.
 *
 * An editor that does not coalesce may report intermediate states inside a batch (a delete then a
 * compose: "cursor at the word start, nothing composing", then the final state). Each batch
 * therefore also remembers the states it passed through; an update matching one of those, in a
 * batch still under way or pending, is ours too and consumes nothing, so the final echo still
 * matches its expectation. [TrackedEditor] records an edit before passing it on, so an editor
 * reporting synchronously is understood as well.
 *
 * Expectations expire after [ttlMs]; an expired one only costs a fallback to the state check in 3.
 * A deleted grapheme's length is not known to the tracker, so it expects the cursor within
 * [MAX_DELETE] units to the left.
 */
internal class OwnEditTracker(
    private val clock: () -> Long,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    private class Expectation(
        val cursorMin: Int,
        val cursorMax: Int,
        val compLength: Int,
        val compStart: Int,
        val at: Long,
        /** Other states the same batch passed through before this one. */
        val intermediates: List<Expectation> = emptyList(),
    )

    // The model: where the editor should be after everything sent so far. -1 = unknown.
    private var selStart = -1
    private var selEnd = -1
    private var compStart = NONE
    private var compLength = 0

    // Min/max the cursor can be after the latest edit (equal unless a delete's size is unknown).
    private var cursorMin = -1
    private var cursorMax = -1

    private val pending = ArrayDeque<Expectation>()
    private var batchDepth = 0
    private var batchDirty = false

    // The state after each edit of the batch under way.
    private val batchSteps = ArrayList<Expectation>()

    /** Number of expectations not yet matched. */
    val pendingCount: Int
        get() = pending.size

    /**
     * The tracked selection, but only while it is trustworthy: no own edit in flight (expired
     * expectations do not count) and a known selection. Null otherwise.
     */
    fun settledSelection(): EditorSelection? {
        expire()
        return if (pending.isEmpty() && selStart >= 0 && selEnd >= 0) {
            EditorSelection(selStart, selEnd)
        } else {
            null
        }
    }

    // ------------------------------------------------------------------ recording our edits

    /** `setComposingText` of [length] chars: the region is replaced (or started at the cursor). */
    fun onSetComposing(length: Int) {
        if (length == 0) {
            onCommit(0)
            return
        }
        val start = regionStart()
        compLength = length
        if (start >= 0) {
            compStart = start
            moveTo(start + length)
        } else {
            compStart = UNKNOWN
            moveTo(-1)
        }
        record()
    }

    /** `commitText` of [length] chars with the platform's [newCursorPosition]. */
    fun onCommit(length: Int, newCursorPosition: Int = 1) {
        val start = regionStart()
        compStart = NONE
        compLength = 0
        if (start >= 0) {
            val end =
                if (newCursorPosition > 0) start + length + newCursorPosition - 1
                else start + newCursorPosition
            moveTo(maxOf(0, end))
        } else {
            moveTo(-1)
        }
        record()
    }

    /** `finishComposingText`: the text stays, the region goes. */
    fun onFinishComposing() {
        if (compLength == 0) return
        compStart = NONE
        compLength = 0
        record()
    }

    /** `deleteSurroundingText(before, after)`. */
    fun onDeleteSurrounding(before: Int) {
        val had = selStart >= 0
        compStart = NONE
        compLength = 0
        if (had) {
            val newStart = maxOf(0, selStart - before)
            val width = selEnd - selStart
            selStart = newStart
            selEnd = newStart + width
            cursorMin = selEnd
            cursorMax = selEnd
        } else {
            moveTo(-1)
        }
        record()
    }

    /** A backward delete of a selection or one grapheme (its size is unknown). */
    fun onDeleteBackward() {
        compStart = NONE
        compLength = 0
        if (selStart >= 0 && selStart != selEnd) {
            moveTo(selStart)
        } else if (selEnd == 0) {
            return // at the start of the field: nothing is deleted, no update follows
        } else if (selEnd > 0) {
            val max = selEnd - 1
            val min = maxOf(0, selEnd - MAX_DELETE)
            selStart = -1
            selEnd = -1
            cursorMin = min
            cursorMax = max
        } else {
            moveTo(-1)
        }
        record()
    }

    fun beginBatch() {
        batchDepth++
    }

    fun endBatch() {
        if (--batchDepth > 0) return
        batchDepth = 0
        if (batchDirty) {
            batchDirty = false
            push(batchSteps.dropLast(1))
        }
        batchSteps.clear()
    }

    // ------------------------------------------------------------------ classifying updates

    /**
     * The latest [onUpdate] was the editor's echo of everything sent so far, with a cursor exactly
     * where the model put it (a position that was known): nothing is in flight and the editor did
     * what we asked. False for an intermediate state of a batch, an echo of an older edit, an
     * unknown expected position and every external update.
     */
    var lastUpdateConfirmed = false
        private set

    /** Classifies [update], consuming the expectations it explains. */
    fun onUpdate(raw: EditorSelection): UpdateVerdict {
        lastUpdateConfirmed = false
        val update = raw.normalised()
        expire()
        if (!update.known || !update.collapsed) return external(update)

        val exact = pending.indexOfLast { matchesExactly(it, update) }
        if (exact < 0 && passesThroughIntermediate(update)) return UpdateVerdict.Own()
        val index = if (exact >= 0) exact else pending.indexOfLast { matchesNormalised(it, update) }
        if (index >= 0) {
            val matched = pending[index]
            repeat(index + 1) { pending.removeFirst() }
            val latest = pending.isEmpty()
            lastUpdateConfirmed = latest && batchDepth == 0 && matched.cursorMin >= 0
            val resync =
                if (latest && update.composingLength != compLength && update.composingLength > 0) {
                    update.composingLength
                } else {
                    -1
                }
            if (latest) adopt(update)
            return UpdateVerdict.Own(resync)
        }
        pending.clear()
        val cursor = update.selEnd
        if (compLength > 0) {
            val atEnd = update.composingLength > 0 && cursor == update.composingEnd
            val sameStart = compStart < 0 || update.composingStart == compStart
            if (atEnd && sameStart) {
                val resync =
                    if (update.composingLength != compLength) update.composingLength else -1
                adopt(update)
                return UpdateVerdict.Own(resync)
            }
            return external(update)
        }
        if (
            update.composingLength == 0 && selStart >= 0 && selStart == selEnd && cursor == selEnd
        ) {
            lastUpdateConfirmed = batchDepth == 0
            return UpdateVerdict.Own()
        }
        return external(update)
    }

    /** Whether [update] is a state a batch (under way or pending) passed through on its way. */
    private fun passesThroughIntermediate(update: EditorSelection): Boolean =
        batchSteps.any { matchesExactly(it, update) } ||
            pending.any { p -> p.intermediates.any { matchesExactly(it, update) } }

    private fun external(update: EditorSelection): UpdateVerdict {
        pending.clear()
        adopt(update)
        return UpdateVerdict.External
    }

    /** Takes [update] as the truth about the editor. */
    private fun adopt(raw: EditorSelection) {
        val update = raw.normalised()
        if (!update.known) {
            selStart = -1
            selEnd = -1
            cursorMin = -1
            cursorMax = -1
        } else {
            selStart = update.selStart
            selEnd = update.selEnd
            cursorMin = selEnd
            cursorMax = selEnd
        }
        compLength = update.composingLength
        compStart = if (compLength > 0) update.composingStart else NONE
    }

    /** Forgets everything, e.g. for a newly focused field. [initial] is its reported selection. */
    fun reset(initial: EditorSelection = EditorSelection()) {
        pending.clear()
        batchDepth = 0
        batchDirty = false
        batchSteps.clear()
        adopt(initial)
    }

    // ------------------------------------------------------------------ internals

    private fun regionStart(): Int =
        when {
            compLength > 0 -> if (compStart >= 0) compStart else -1
            else -> selStart
        }

    private fun moveTo(cursor: Int) {
        selStart = cursor
        selEnd = cursor
        cursorMin = cursor
        cursorMax = cursor
    }

    private fun record() {
        if (batchDepth > 0) {
            batchDirty = true
            batchSteps.add(snapshot())
        } else {
            push(emptyList())
        }
    }

    private fun snapshot(intermediates: List<Expectation> = emptyList()) =
        Expectation(cursorMin, cursorMax, compLength, compStart, clock(), intermediates)

    private fun push(steps: List<Expectation>) {
        expire()
        val last = snapshot()
        val distinct = steps.filterNot { it.sameState(last) }
        pending.addLast(snapshot(distinct))
        if (pending.size > MAX_PENDING) pending.removeFirst()
    }

    private fun Expectation.sameState(o: Expectation) =
        cursorMin == o.cursorMin &&
            cursorMax == o.cursorMax &&
            compLength == o.compLength &&
            compStart == o.compStart

    private fun expire() {
        val now = clock()
        while (pending.isNotEmpty() && now - pending.first().at > ttlMs) pending.removeFirst()
    }

    private fun cursorInRange(e: Expectation, cursor: Int): Boolean =
        e.cursorMin < 0 || (cursor >= e.cursorMin && cursor <= e.cursorMax)

    private fun matchesExactly(e: Expectation, u: EditorSelection): Boolean {
        if (u.composingLength != e.compLength) return false
        if (e.compLength > 0 && e.compStart >= 0 && u.composingStart != e.compStart) return false
        return cursorInRange(e, u.selEnd)
    }

    private fun matchesNormalised(e: Expectation, u: EditorSelection): Boolean =
        e.compLength > 0 &&
            u.composingLength > 0 &&
            u.selEnd == u.composingEnd &&
            (e.compStart < 0 || u.composingStart == e.compStart)

    companion object {
        /** Long enough for a callback on a busy main thread, short enough to heal a lost one. */
        const val DEFAULT_TTL_MS = 1_000L
        const val MAX_PENDING = 16

        /** Longest grapheme cluster a backward delete may remove. */
        const val MAX_DELETE = 32
        private const val NONE = -1
        private const val UNKNOWN = -2
    }
}

/**
 * A [TextEditor] that reports every mutation to an [OwnEditTracker] and passes everything on to
 * [delegate] (reads and Enter included). The tracker hears of an edit before the editor does, so
 * that an update the editor reports synchronously while applying it is already explained.
 */
internal class TrackedEditor(
    private val delegate: TextEditor,
    private val tracker: OwnEditTracker,
) : TextEditor by delegate {
    override fun commitText(text: CharSequence, newCursorPosition: Int) {
        tracker.onCommit(text.length, newCursorPosition)
        delegate.commitText(text, newCursorPosition)
    }

    override fun setComposingText(text: CharSequence) {
        tracker.onSetComposing(text.length)
        delegate.setComposingText(text)
    }

    override fun finishComposingText() {
        tracker.onFinishComposing()
        delegate.finishComposingText()
    }

    override fun deleteSurroundingText(before: Int, after: Int) {
        tracker.onDeleteSurrounding(before)
        delegate.deleteSurroundingText(before, after)
    }

    override fun deleteBackward(sensitive: Boolean) {
        tracker.onDeleteBackward()
        delegate.deleteBackward(sensitive)
    }

    override fun <T> batchEdit(block: () -> T): T {
        tracker.beginBatch()
        try {
            return delegate.batchEdit(block)
        } finally {
            tracker.endBatch()
        }
    }
}
