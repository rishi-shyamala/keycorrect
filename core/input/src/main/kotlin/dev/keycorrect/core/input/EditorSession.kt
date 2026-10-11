package dev.keycorrect.core.input

import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Thin, null-safe wrapper over the current [InputConnection]. All text mutations from the keyboard
 * go through here so later phases (composing text, autocorrect, undo) have one place to hook in.
 *
 * Each [InputConnection] call that returns data is a blocking IPC to the app, so the per-keystroke
 * paths keep those to a minimum.
 */
class EditorSession(
    private val connection: () -> InputConnection?,
    private val editorInfo: () -> EditorInfo?,
    private val newKeyEvent: (action: Int, keyCode: Int, metaState: Int) -> KeyEvent =
        { action, code, meta ->
            val now = SystemClock.uptimeMillis()
            KeyEvent(now, now, action, code, 0, meta)
        },
) : TextEditor {
    override fun commitText(text: CharSequence, newCursorPosition: Int) {
        connection()?.commitText(text, newCursorPosition)
    }

    override fun setComposingText(text: CharSequence) {
        connection()?.setComposingText(text, 1)
    }

    override fun finishComposingText() {
        connection()?.finishComposingText()
    }

    override fun deleteSurroundingText(before: Int, after: Int) {
        connection()?.deleteSurroundingText(before, after)
    }

    /**
     * Reads are refused (null) in a sensitive field, whatever the caller does: the keyboard's
     * prediction layer already never asks, this keeps a password out of this process regardless.
     */
    override fun textBeforeCursor(maxChars: Int): CharSequence? =
        if (readsAllowed()) connection()?.getTextBeforeCursor(maxChars, 0) else null

    override fun textAfterCursor(maxChars: Int): CharSequence? =
        if (readsAllowed()) connection()?.getTextAfterCursor(maxChars, 0) else null

    override fun <T> batchEdit(block: () -> T): T {
        val ic = connection() ?: return block()
        ic.beginBatchEdit()
        try {
            return block()
        } finally {
            ic.endBatchEdit()
        }
    }

    private fun readsAllowed(): Boolean =
        editorInfo()?.let { !FieldProfile.from(it).sensitive } ?: false

    /**
     * Deletes the selection if there is one, otherwise the grapheme before the cursor. In a
     * [sensitive] field nothing is read back (keeping the password out of this process); the editor
     * handles a delete key event itself, selection and grapheme boundaries included.
     */
    override fun deleteBackward(sensitive: Boolean) {
        val ic = connection() ?: return
        if (sensitive) {
            sendKey(ic, KeyEvent.KEYCODE_DEL)
            return
        }
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
            return
        }
        val before = ic.getTextBeforeCursor(MAX_GRAPHEME_LOOKBEHIND, 0)
        if (before == null) {
            // Editors that don't expose text (e.g. some terminals) still understand key events.
            sendKey(ic, KeyEvent.KEYCODE_DEL)
            return
        }
        val length = Graphemes.lastClusterLength(before)
        if (length > 0) ic.deleteSurroundingText(length, 0)
    }

    /**
     * Performs the field's IME action if it has one. Otherwise a multi-line field gets a line break
     * and anything else (single-line, or no input type at all) gets an Enter key event, which is
     * what apps listening for Enter expect.
     */
    override fun performEnter() {
        val ic = connection() ?: return
        val info = editorInfo()
        if (info == null || info.inputType == EditorInfo.TYPE_NULL) {
            sendKey(ic, KeyEvent.KEYCODE_ENTER)
            return
        }
        when (val action = EnterAction.from(info.imeOptions)) {
            EnterAction.NewLine ->
                if (FieldProfile.isMultiLine(info.inputType)) {
                    ic.commitText("\n", 1)
                } else {
                    sendKey(ic, KeyEvent.KEYCODE_ENTER)
                }
            is EnterAction.Editor -> ic.performEditorAction(action.actionId)
        }
    }

    /** 0 when there is no connection or editor. */
    override fun cursorCapsMode(): Int {
        val ic = connection() ?: return 0
        val info = editorInfo() ?: return 0
        return ic.getCursorCapsMode(info.inputType)
    }

    override fun setSelection(start: Int, end: Int) {
        connection()?.setSelection(start, end)
    }

    override fun sendKey(keyCode: Int, metaState: Int) {
        val ic = connection() ?: return
        sendKey(ic, keyCode, metaState)
    }

    override fun contextMenuAction(action: EditAction): Boolean {
        val ic = connection() ?: return false
        val id =
            when (action) {
                EditAction.Cut -> android.R.id.cut
                EditAction.Copy -> android.R.id.copy
                EditAction.Paste -> android.R.id.paste
                EditAction.SelectAll -> android.R.id.selectAll
            }
        return ic.performContextMenuAction(id)
    }

    override fun selectedText(maxChars: Int): CharSequence? {
        if (!readsAllowed()) return null
        return connection()?.getSelectedText(0)?.let {
            if (it.length > maxChars) it.subSequence(0, maxChars) else it
        }
    }

    private fun sendKey(ic: InputConnection, keyCode: Int, metaState: Int = 0) {
        ic.sendKeyEvent(newKeyEvent(KeyEvent.ACTION_DOWN, keyCode, metaState))
        ic.sendKeyEvent(newKeyEvent(KeyEvent.ACTION_UP, keyCode, metaState))
    }

    private companion object {
        /** Longest grapheme cluster we expect (long ZWJ emoji sequences are ~11 code units+). */
        const val MAX_GRAPHEME_LOOKBEHIND = 32
    }
}
