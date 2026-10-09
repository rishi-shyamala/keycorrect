package dev.keycorrect.core.input

/** The editing operations the keyboard performs on the focused field. */
interface TextEditor {
    fun commitText(text: CharSequence)

    fun deleteBackward()

    fun performEnter()

    /**
     * The auto-capitalisation mode at the cursor, as `InputConnection.getCursorCapsMode` returns:
     * non-zero when the next character should be upper-case, 0 otherwise.
     */
    fun cursorCapsMode(): Int
}
