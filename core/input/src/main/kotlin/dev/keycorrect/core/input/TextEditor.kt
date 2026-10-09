package dev.keycorrect.core.input

/** The editing operations the keyboard performs on the focused field. */
interface TextEditor {
    fun commitText(text: CharSequence)

    fun deleteBackward()

    fun performEnter()
}
