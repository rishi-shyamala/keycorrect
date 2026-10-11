package dev.keycorrect.core.input

/**
 * Text around the selection ([TextEditor.surroundingText]): [text] starts at field offset [offset];
 * the selection is [selectionStart] until [selectionEnd] within [text].
 */
data class Surrounding(
    val text: CharSequence,
    val selectionStart: Int,
    val selectionEnd: Int,
    val offset: Int,
) {
    /** The selection's start and end in the field. */
    val fieldStart: Int
        get() = offset + selectionStart

    val fieldEnd: Int
        get() = offset + selectionEnd

    val collapsed: Boolean
        get() = selectionStart == selectionEnd
}

/** The editor's context-menu actions the keyboard can trigger ([TextEditor.contextMenuAction]). */
enum class EditAction {
    Cut,
    Copy,
    Paste,
    SelectAll,
}

/**
 * The editing operations the keyboard performs on the focused field. It mirrors the parts of
 * `InputConnection` the keyboard uses, so its size follows the platform's.
 */
@Suppress("TooManyFunctions")
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
     * Selects [start] until [end] in the editor's char offsets; a cursor when they are equal. The
     * keyboard's own cursor moves and selections (editing, Phase 4) go through here or [sendKey].
     */
    fun setSelection(start: Int, end: Int)

    /**
     * Sends one key press (down and up) with [metaState] (`KeyEvent.META_*`): arrows, Home/End,
     * Ctrl+Z and the like, and every key in a terminal. The editor handles graphemes, bidi and
     * selection itself.
     */
    fun sendKey(keyCode: Int, metaState: Int = 0)

    /** Cut, copy, paste or select all, as the editor's context menu does. False if refused. */
    fun contextMenuAction(action: EditAction): Boolean

    /**
     * The selected text (at most [maxChars]), or null when nothing is selected, the editor doesn't
     * say, or the field is sensitive. A blocking IPC, like [textBeforeCursor].
     */
    fun selectedText(maxChars: Int): CharSequence?

    /**
     * Up to [beforeLength] chars before the selection, the selection, and up to [afterLength] after
     * it, with where the selection is, read in one call (`getSurroundingText`). Unlike the last
     * `onUpdateSelection`, this is never behind the editor. Null in sensitive fields, when the
     * editor doesn't say, or when it doesn't know the text's position in the field.
     */
    fun surroundingText(beforeLength: Int, afterLength: Int): Surrounding?

    /**
     * The auto-capitalisation mode at the cursor, as `InputConnection.getCursorCapsMode` returns:
     * non-zero when the next character should be upper-case, 0 otherwise.
     */
    fun cursorCapsMode(): Int
}
