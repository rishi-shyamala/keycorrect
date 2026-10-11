package dev.keycorrect.feature.keyboard

import dev.keycorrect.core.input.TextEditor

/**
 * Editing beyond typing (docs/PHASE4.md, PR A): the spacebar cursor and the backspace word swipe
 * ([KeyGestures]), raw keys, and the editing panel's actions. Kept out of [KeyboardController],
 * which owns typing, layers and shift. Every move ends the composing word first ([endComposing]);
 * the editor's selection update then settles prediction state as for any external move.
 */
internal class EditingController(
    private val editor: TextEditor,
    private val endComposing: () -> Unit,
    private val showPanel: (KeyboardPanel) -> Unit,
) : KeyGestures {
    override fun onCursorDrag(dx: Int, dy: Int, select: Boolean) = Unit // PR A

    override fun onSpaceHold() = showPanel(KeyboardPanel.Editing)

    override fun onBackspaceSwipe(words: Int) = Unit // PR A

    override fun onBackspaceSwipeEnd(commit: Boolean) = Unit // PR A

    /** Sends a raw key event (terminal row, editing panel) once composing has ended. */
    fun sendRawKey(code: Int, metaState: Int = 0) {
        endComposing()
        editor.sendKey(code, metaState)
    }
}
