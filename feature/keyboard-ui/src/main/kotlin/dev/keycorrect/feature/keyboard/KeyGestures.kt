package dev.keycorrect.feature.keyboard

/**
 * Gestures on keys beyond a tap (docs/PHASE4.md, PR A), recognised by [TouchStateMachine] and
 * carried out by [KeyboardController]: the spacebar cursor and the backspace word swipe.
 */
interface KeyGestures {
    /**
     * Space was dragged: move the cursor [dx] steps (chars; negative is left) and [dy] lines
     * (negative is up). With [select] (the drag started with a hold) the moves extend the selection
     * from where the cursor was.
     */
    fun onCursorDrag(dx: Int, dy: Int, select: Boolean)

    /** Space was held and released without moving: open the editing panel. */
    fun onSpaceHold()

    /**
     * Backspace was swiped left: the [words] whole words before the cursor are selected as a
     * preview of what release deletes (0: back where the swipe started, nothing selected).
     */
    fun onBackspaceSwipe(words: Int)

    /** The backspace swipe ended: delete what it selected ([commit]), or put the cursor back. */
    fun onBackspaceSwipeEnd(commit: Boolean)

    companion object {
        val None =
            object : KeyGestures {
                override fun onCursorDrag(dx: Int, dy: Int, select: Boolean) = Unit

                override fun onSpaceHold() = Unit

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
