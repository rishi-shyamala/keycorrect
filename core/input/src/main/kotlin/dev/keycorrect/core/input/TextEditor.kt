package dev.keycorrect.core.input

/** The editing operations the keyboard performs on the focused field. */
interface TextEditor {
    fun commitText(text: CharSequence)

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
