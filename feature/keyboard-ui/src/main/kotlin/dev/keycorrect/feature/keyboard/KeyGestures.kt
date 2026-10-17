package dev.keycorrect.feature.keyboard

/**
 * Gestures on keys beyond a tap (docs/PHASE4.md, PR A), recognised by [TouchStateMachine] and
 * carried out by [KeyboardController]: the spacebar cursor and the backspace word swipe.
 */
interface KeyGestures {
    /**
     * Space was dragged: move the cursor [dx] steps (chars; negative is left) and [dy] lines
     * (negative is up). With [select] (the drag started on the left half of the space bar) the
     * moves extend the selection from where the cursor was.
     */
    fun onCursorDrag(dx: Int, dy: Int, select: Boolean)

    /**
     * Backspace was swiped left: the [words] whole words before the cursor are selected as a
     * preview of what release deletes (0: back where the swipe started, nothing selected).
     */
    fun onBackspaceSwipe(words: Int)

    /** The backspace swipe ended: delete what it selected ([commit]), or put the cursor back. */
    fun onBackspaceSwipeEnd(commit: Boolean)

    /**
     * A press on a letter key turned into a swipe (gesture typing, docs/PHASE5.md). [path] is the
     * touch layer's live path: it keeps growing until [onSwipeEnd] or [onSwipeCancel] and is
     * cleared after them, so keep no reference beyond those calls.
     */
    fun onSwipeStart(path: SwipePath) = Unit

    /** The swipe's [path] grew (at most once per touch event). */
    fun onSwipeMove(path: SwipePath) = Unit

    /** The finger lifted: decode [path] and commit the word. */
    fun onSwipeEnd(path: SwipePath) = Unit

    /** The swipe was abandoned (the touch was lost): nothing is typed. */
    fun onSwipeCancel() = Unit

    companion object {
        val None =
            object : KeyGestures {
                override fun onCursorDrag(dx: Int, dy: Int, select: Boolean) = Unit

                override fun onBackspaceSwipe(words: Int) = Unit

                override fun onBackspaceSwipeEnd(commit: Boolean) = Unit
            }
    }
}

/** What fills the key area (docs/PHASE4.md): the keys, or one of the panels at the same height. */
enum class KeyboardPanel {
    Keys,
    Editing,
    Emoji,
    Clipboard,
}
