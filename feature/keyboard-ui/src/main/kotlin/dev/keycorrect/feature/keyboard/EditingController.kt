package dev.keycorrect.feature.keyboard

import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.keycorrect.core.input.EditAction
import dev.keycorrect.core.input.TextEditor
import kotlin.math.abs

/**
 * What the buttons of the editing panel do (docs/PHASE4.md, PR A), with their label and description
 * strings. A button either sends a [key] (moves extend the selection while select mode is on), asks
 * the editor's context menu ([menu]), or selects around the cursor from the text ([expand]); the
 * toggle and ABC are handled by [EditingController].
 */
internal enum class EditingAction(
    @StringRes val label: Int,
    @StringRes val description: Int,
    val key: KeyPress? = null,
    val menu: EditAction? = null,
    val expand: ((CharSequence, Int, Int) -> TextSpan?)? = null,
) {
    Left(
        R.string.edit_left,
        R.string.edit_left_description,
        moves(KeyEvent.KEYCODE_DPAD_LEFT),
    ),
    Right(
        R.string.edit_right,
        R.string.edit_right_description,
        moves(KeyEvent.KEYCODE_DPAD_RIGHT),
    ),
    Up(R.string.edit_up, R.string.edit_up_description, moves(KeyEvent.KEYCODE_DPAD_UP)),
    Down(
        R.string.edit_down,
        R.string.edit_down_description,
        moves(KeyEvent.KEYCODE_DPAD_DOWN),
    ),
    WordLeft(
        R.string.edit_word_left,
        R.string.edit_word_left_description,
        moves(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_CTRL_ON),
    ),
    WordRight(
        R.string.edit_word_right,
        R.string.edit_word_right_description,
        moves(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_CTRL_ON),
    ),
    Home(
        R.string.edit_home,
        R.string.edit_home_description,
        moves(KeyEvent.KEYCODE_MOVE_HOME),
    ),
    End(R.string.edit_end, R.string.edit_end_description, moves(KeyEvent.KEYCODE_MOVE_END)),
    SelectToggle(R.string.edit_select_toggle, R.string.edit_select_toggle_description),
    SelectWord(
        R.string.edit_select_word,
        R.string.edit_select_word_description,
        expand = TextUnits::expandWord,
    ),
    SelectSentence(
        R.string.edit_select_sentence,
        R.string.edit_select_sentence_description,
        expand = TextUnits::expandSentence,
    ),
    SelectLine(
        R.string.edit_select_line,
        R.string.edit_select_line_description,
        expand = TextUnits::expandLine,
    ),
    SelectAll(
        R.string.edit_select_all,
        R.string.edit_select_all_description,
        menu = EditAction.SelectAll,
    ),
    Cut(R.string.edit_cut, R.string.edit_cut_description, menu = EditAction.Cut),
    Copy(R.string.edit_copy, R.string.edit_copy_description, menu = EditAction.Copy),
    Paste(R.string.edit_paste, R.string.edit_paste_description, menu = EditAction.Paste),
    Undo(
        R.string.edit_undo,
        R.string.edit_undo_description,
        KeyPress(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON),
    ),
    Redo(
        R.string.edit_redo,
        R.string.edit_redo_description,
        KeyPress(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON),
    ),
    Backspace(
        R.string.edit_backspace,
        R.string.edit_backspace_description,
        KeyPress(KeyEvent.KEYCODE_DEL),
    ),
    Delete(
        R.string.edit_delete,
        R.string.edit_delete_description,
        KeyPress(KeyEvent.KEYCODE_FORWARD_DEL),
    ),
    Close(R.string.edit_close, R.string.edit_close_description),
}

/** A key event to send: [code] with [meta]; a [moves] key extends the selection in select mode. */
internal class KeyPress(val code: Int, val meta: Int = 0, val moves: Boolean = false)

private fun moves(code: Int, meta: Int = 0) = KeyPress(code, meta, moves = true)

/**
 * Editing beyond typing (docs/PHASE4.md, PR A): the spacebar cursor and the backspace word swipe
 * ([KeyGestures]), raw keys, the editing panel's actions and the selection strip's. Kept out of
 * [KeyboardController], which owns typing, layers and shift. Every move ends the composing word
 * first ([endComposing]); the editor's selection update then settles prediction state as for any
 * external move.
 *
 * Moves are key events (arrows, Home/End), which every editor and terminal understands and which
 * leave graphemes and bidi to the editor. Selecting by word, sentence or line, the case change and
 * the backspace swipe need the text around the cursor, so they are unavailable in sensitive fields
 * (nothing is read there) and where the editor does not expose its text. Key events and the
 * context-menu actions work everywhere. [selection] is the latest the platform reported.
 */
@Suppress("TooManyFunctions")
internal class EditingController(
    private val editor: TextEditor,
    private val endComposing: () -> Unit,
    private val showPanel: (KeyboardPanel) -> Unit,
    private val selection: () -> EditorSelection = { EditorSelection() },
    private val sensitive: () -> Boolean = { false },
) : KeyGestures {
    /** While on, the panel's moves extend the selection (shift is added). */
    var selectMode: Boolean by mutableStateOf(false)
        private set

    /** True while a backspace swipe is previewing a selection. */
    var swipeActive: Boolean by mutableStateOf(false)
        private set

    /**
     * The "send selection to the engine" hook (Phase 7). It stays off, and has no UI, until then:
     * nothing calls [sendSelectionToEngine] and it does nothing unless this flag is set.
     */
    var engineHookEnabled = false
    var engineHook: ((String) -> Unit)? = null

    private var swipe: Swipe? = null
    private var swipeTried = false

    /**
     * The backspace swipe's origin: the cursor and the text before it, as they were at the start.
     */
    private class Swipe(val origin: Int, val before: CharSequence) {
        var length = 0
    }

    // ------------------------------------------------------------------ KeyGestures

    override fun onCursorDrag(dx: Int, dy: Int, select: Boolean) {
        if (dx == 0 && dy == 0) return
        endComposing()
        val meta = if (select) KeyEvent.META_SHIFT_ON else 0
        editor.batchEdit {
            val horizontal = if (dx < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            val vertical = if (dy < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
            repeat(abs(dx)) { editor.sendKey(horizontal, meta) }
            repeat(abs(dy)) { editor.sendKey(vertical, meta) }
        }
    }

    override fun onSpaceHold() {
        selectMode = false
        showPanel(KeyboardPanel.Editing)
    }

    override fun onBackspaceSwipe(words: Int) {
        val active = swipe ?: startSwipe() ?: return
        val length = TextUnits.wordsBackLength(active.before, words.coerceAtLeast(0))
        if (length == active.length) return
        active.length = length
        editor.setSelection(active.origin - length, active.origin)
    }

    override fun onBackspaceSwipeEnd(commit: Boolean) {
        val active = swipe
        swipe = null
        swipeTried = false
        swipeActive = false
        if (active == null) return
        if (commit && active.length > 0) {
            editor.deleteBackward(false)
        } else if (active.length > 0) {
            editor.setSelection(active.origin, active.origin)
        }
    }

    /**
     * Another key arrived mid-swipe: put the cursor back now. Until the swipe's own finger lifts
     * ([onBackspaceSwipeEnd]) no new swipe starts, however that finger moves.
     */
    fun cancelSwipe() {
        val active = swipe
        swipe = null
        swipeTried = true
        swipeActive = false
        if (active != null && active.length > 0) editor.setSelection(active.origin, active.origin)
    }

    /** Remembers where the swipe began; null (and nothing more tried) where it cannot work. */
    private fun startSwipe(): Swipe? {
        if (swipeTried) return null
        swipeTried = true
        return readOrigin()?.also {
            swipe = it
            swipeActive = true
        }
    }

    /**
     * Read from the editor itself, not from the last selection callback: the delete-on-press just
     * before the swipe may not have been reported yet.
     */
    private fun readOrigin(): Swipe? {
        if (sensitive()) return null
        endComposing()
        return editor
            .surroundingText(SWIPE_CONTEXT_CHARS, 0)
            ?.takeIf { it.collapsed }
            ?.let {
                Swipe(it.fieldStart, it.text.subSequence(0, it.selectionStart))
            }
    }

    // ------------------------------------------------------------------ editing panel

    /** Sends a raw key event (terminal row, editing panel) once composing has ended. */
    fun sendRawKey(code: Int, metaState: Int = 0) {
        endComposing()
        editor.sendKey(code, metaState)
    }

    /** Whether [action] can be done now (the panel draws the others disabled). */
    fun isAvailable(action: EditingAction): Boolean =
        when (action) {
            EditingAction.SelectWord,
            EditingAction.SelectSentence,
            EditingAction.SelectLine -> !sensitive()
            // Never a password onto the clipboard (where PR C's history would see it).
            EditingAction.Cut,
            EditingAction.Copy -> !sensitive() && selection().let { !it.known || !it.collapsed }
            else -> true
        }

    /** Carries out a panel button. */
    fun perform(action: EditingAction) {
        when (action) {
            EditingAction.SelectToggle -> selectMode = !selectMode
            EditingAction.Close -> {
                selectMode = false
                showPanel(KeyboardPanel.Keys)
            }
            else -> {
                endComposing()
                action.menu?.let { editor.contextMenuAction(it) }
                action.expand?.let { selectAround(it) }
                action.key?.let(::sendKey)
            }
        }
    }

    private fun sendKey(key: KeyPress) {
        val extend = key.moves && selectMode
        editor.sendKey(key.code, if (extend) key.meta or KeyEvent.META_SHIFT_ON else key.meta)
    }

    // ------------------------------------------------------------------ selection strip

    /** The selection grows to the word(s) around it. False where that is not possible. */
    fun expandSelectionToWord(): Boolean = selectAround(TextUnits::expandWord)

    /** The selection grows to the sentence(s) around it. */
    fun expandSelectionToSentence(): Boolean = selectAround(TextUnits::expandSentence)

    /**
     * Changes the case of the selection to the next of the cycle (lower, Title, UPPER), keeping it
     * selected. False where nothing was changed.
     */
    fun cycleCase(): Boolean {
        val selected = readSelected()
        val next = selected?.let { CaseCycle.next(it.text).takeIf { n -> n != it.text } }
        if (selected == null || next == null) return false
        endComposing()
        editor.batchEdit {
            editor.commitText(next, 1)
            editor.setSelection(selected.range.start, selected.range.start + next.length)
        }
        return true
    }

    /** Copies the selection to the clipboard. */
    fun copySelection(): Boolean {
        endComposing()
        return editor.contextMenuAction(EditAction.Copy)
    }

    /** Hands the selected text to the engine hook. Does nothing until Phase 7 switches it on. */
    fun sendSelectionToEngine(): Boolean {
        val hook = engineHook?.takeIf { engineHookEnabled }
        return hook?.let { send -> readSelected()?.text?.also(send) } != null
    }

    /** Forgets what belongs to one field: the select mode and any swipe under way. */
    fun reset() {
        selectMode = false
        swipe = null
        swipeTried = false
        swipeActive = false
    }

    /** The selected range and its text, read from the editor; null where nothing is selected. */
    private class Selected(val range: TextSpan, val text: String)

    private fun readSelected(): Selected? =
        editor
            .takeIf { !sensitive() }
            ?.surroundingText(0, 0)
            ?.takeIf { !it.collapsed && it.selectionEnd - it.selectionStart <= MAX_SELECTION_CHARS }
            ?.let {
                Selected(
                    TextSpan(it.fieldStart, it.fieldEnd),
                    it.text.subSequence(it.selectionStart, it.selectionEnd).toString(),
                )
            }

    /** The text around the selection, and where the selection is in it; null where unreadable. */
    private class Window(val text: String, val base: Int, val start: Int, val end: Int)

    private fun readWindow(): Window? =
        editor
            .takeIf { !sensitive() }
            ?.surroundingText(CONTEXT_CHARS, CONTEXT_CHARS)
            ?.let { Window(it.text.toString(), it.offset, it.selectionStart, it.selectionEnd) }

    /**
     * Selects what [expand] makes of the selection (or the cursor) and the text around it (the text
     * before it, the selected text and the text after it). False where there is nothing to change.
     */
    private fun selectAround(expand: (CharSequence, Int, Int) -> TextSpan?): Boolean {
        val span =
            readWindow()?.let { w ->
                expand(w.text, w.start, w.end)
                    ?.takeIf { it.start != w.start || it.end != w.end }
                    ?.let { TextSpan(it.start + w.base, it.end + w.base) }
            }
        span?.let { editor.setSelection(it.start, it.end) }
        return span != null
    }

    private companion object {
        /** How far before the cursor a backspace swipe looks for word boundaries. */
        const val SWIPE_CONTEXT_CHARS = 256

        /** How far around the selection words, sentences and lines are looked for. */
        const val CONTEXT_CHARS = 1000
        /** Longer selections are left alone by the case change and the engine hook. */
        const val MAX_SELECTION_CHARS = 4000
    }
}
