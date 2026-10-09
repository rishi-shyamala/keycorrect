package dev.keycorrect.core.input

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Thin, null-safe wrapper over the current [InputConnection]. All text mutations from the keyboard
 * go through here so later phases (composing text, autocorrect, undo) have one place to hook in.
 */
class EditorSession(
    private val connection: () -> InputConnection?,
    private val editorInfo: () -> EditorInfo?,
) : TextEditor {
    override fun commitText(text: CharSequence) {
        connection()?.commitText(text, 1)
    }

    /** Deletes the selection if there is one, otherwise the grapheme before the cursor. */
    override fun deleteBackward() {
        val ic = connection() ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
            return
        }
        val before = ic.getTextBeforeCursor(MAX_GRAPHEME_LOOKBEHIND, 0)
        if (before == null) {
            // Editors that don't expose text (e.g. some terminals) still understand key events.
            sendKey(KeyEvent.KEYCODE_DEL)
            return
        }
        val length = Graphemes.lastClusterLength(before)
        if (length > 0) ic.deleteSurroundingText(length, 0)
    }

    override fun performEnter() {
        val ic = connection() ?: return
        val info = editorInfo()
        if (info == null || info.inputType == EditorInfo.TYPE_NULL) {
            sendKey(KeyEvent.KEYCODE_ENTER)
            return
        }
        when (val action = EnterAction.from(info.imeOptions)) {
            EnterAction.NewLine -> ic.commitText("\n", 1)
            is EnterAction.Editor -> ic.performEditorAction(action.actionId)
        }
    }

    /** 0 when there is no connection or editor. */
    override fun cursorCapsMode(): Int {
        val ic = connection() ?: return 0
        val info = editorInfo() ?: return 0
        return ic.getCursorCapsMode(info.inputType)
    }

    private fun sendKey(keyCode: Int) {
        val ic = connection() ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private companion object {
        /** Longest grapheme cluster we expect (long ZWJ emoji sequences are ~11 code units+). */
        const val MAX_GRAPHEME_LOOKBEHIND = 32
    }
}
