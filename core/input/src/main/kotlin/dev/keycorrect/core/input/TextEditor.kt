package dev.keycorrect.core.input

/** The editing operations the keyboard performs on the focused field. */
interface TextEditor {
    /**
     * Inserts [text] at the cursor, replacing the selection or the composing text, and ends
     * composing. [newCursorPosition] follows `InputConnection.commitText`: 1 puts the cursor after
     * the text, 0 before it (used to leave the cursor inside a shortcut expansion).
     */
    fun commitText(text: CharSequence, newCursorPosition: Int = 1)

    /** Replaces the composing text (or starts it at the cursor) with [text], cursor after it. */
    fun setComposingText(text: CharSequence)

    /** Ends composing, keeping the text as it is. A no-op when nothing is composing. */
    fun finishComposingText()

    /** Deletes [before] chars before the cursor and [after] chars after it. */
    fun deleteSurroundingText(before: Int, after: Int)

    /**
     * Up to [maxChars] chars before the cursor, or null when the editor doesn't expose its text.
     * Each call is a blocking IPC; callers cache. Never call this for a sensitive field.
     */
    fun textBeforeCursor(maxChars: Int): CharSequence?

    /** Like [textBeforeCursor], for the text after the cursor. */
    fun textAfterCursor(maxChars: Int): CharSequence?

    /**
     * Runs [block] as one atomic edit so the editor reports a single selection change for it. The
     * default just runs [block].
     */
    fun <T> batchEdit(block: () -> T): T = block()

    /**
     * Deletes the selection, or the grapheme before the cursor. When [sensitive] (a password field)
     * the field's text is never read: a delete key event is sent instead.
     */
    fun deleteBackward(sensitive: Boolean)

    fun performEnter()

    /**
     * The auto-capitalisation mode at the cursor, as `InputConnection.getCursorCapsMode` returns:
     * non-zero when the next character should be upper-case, 0 otherwise.
     */
    fun cursorCapsMode(): Int
}
