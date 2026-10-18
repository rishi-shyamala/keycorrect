package dev.keycorrect.feature.keyboard

import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.keycorrect.core.input.EditAction
import dev.keycorrect.core.input.Graphemes
import dev.keycorrect.core.input.Surrounding
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
 *
 * Some editors report no offset of the text they return (-1, "unknown"). The field position is then
 * the keyboard's tracked selection ([settledSelection]), trusted only while none of its own edits
 * is still in flight; until then the action is retried ([onEditsSettled]) rather than given up.
 */
@Suppress("TooManyFunctions")
internal class EditingController
@Suppress("LongParameterList") // the editor, then read-only views of the controller's state
constructor(
    private val editor: TextEditor,
    private val endComposing: () -> Unit,
    private val showPanel: (KeyboardPanel) -> Unit,
    private val selection: () -> EditorSelection = { EditorSelection() },
    private val sensitive: () -> Boolean = { false },
    /** The tracked selection while no own edit is in flight (null otherwise). */
    private val settledSelection: () -> EditorSelection? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
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

    /** The joystick drag under way (see [moveBySelection]), and when it last moved. */
    private var drag: Drag? = null
    private var dragAt = 0L

    /** The drag found it cannot move by selection (RTL text, no offset): keys until it ends. */
    private var dragUnsupported = false

    /** Set by [readOrigin] when only an unsettled tracker kept it from the origin: try again. */
    private var originUnsettled = false

    /** The swipe's origin was unsettled: no new read until [onEditsSettled] (one read per wait). */
    private var awaitingSettle = false

    /** A selection action waiting for the tracker to settle, and when it was asked for. */
    private var deferred: ((CharSequence, Int, Int) -> TextSpan?)? = null
    private var deferredAt = 0L

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
        if (dy == 0 && moveBySelection(dx, select)) return
        if (dy != 0) drag = null // the arrow keys move the cursor on their own: the window is stale
        val meta = if (select) KeyEvent.META_SHIFT_ON else 0
        editor.batchEdit {
            val horizontal = if (dx < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            val vertical = if (dy < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
            repeat(abs(dx)) { editor.sendKey(horizontal, meta) }
            repeat(abs(dy)) { editor.sendKey(vertical, meta) }
        }
    }

    /**
     * A horizontal joystick tick as one [TextEditor.setSelection] instead of 2 to 4 key events (a
     * tick runs about 20 times a second). The cursor is stepped by grapheme through a window of
     * text read once at the start of the drag, then kept locally. False, with nothing done, where
     * the keys must do it: a sensitive field, a selection to start with, an editor that reports no
     * text offset, right-to-left text (where Left is visual), a window that has run out, or an own
     * edit in flight (its update could be coalesced with ours, so the tick uses keys and the next
     * one reads a fresh window).
     */
    @Suppress("ReturnCount") // guard clauses: each is one reason to leave it to the keys
    private fun moveBySelection(dx: Int, select: Boolean): Boolean {
        if (sensitive()) return false
        val now = clock()
        if (now - dragAt > DRAG_GAP_MS) {
            drag = null
            dragUnsupported = false
        }
        dragAt = now
        if (dragUnsupported) return false
        val open = drag?.takeIf { it.select == select }
        if (open != null && open.editInFlight()) {
            drag = null // an own edit may be coalesced with our selection: keys for this tick
            return false
        }
        var window = open?.takeIf { it.consistent() }
        if (window == null) {
            window = openDrag(select)
            drag = window
            if (window == null) {
                dragUnsupported = true
                return false
            }
        }
        val target = window.stepped(dx)
        if (target == null) {
            drag = null // out of text: the keys do this tick, the next one reads a fresh window
            return false
        }
        if (target != window.cursor) {
            window.cursor = target
            window.remember(target)
            val anchor = if (select) window.anchor else target
            editor.setSelection(window.base + anchor, window.base + target)
        }
        return true
    }

    private fun openDrag(select: Boolean): Drag? {
        val read = editor.surroundingText(DRAG_CONTEXT, DRAG_CONTEXT)?.takeIf { it.collapsed }
        // Only the editor's own offset places the window: a position inferred from the tracked
        // selection is not trusted for a write.
        val base = read?.offset?.takeIf { it >= 0 }
        val text = read?.text?.toString()?.takeUnless { it.any(::isRightToLeft) }
        if (read == null || base == null || text == null) return null
        return Drag(
            text = text,
            base = base,
            select = select,
            anchor = read.selectionStart,
            atStart = base == 0,
            atEnd = text.length - read.selectionEnd < DRAG_CONTEXT,
            settled = settledSelection,
        )
    }

    private fun isRightToLeft(c: Char): Boolean =
        when (Character.getDirectionality(c)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE -> true
            else -> false
        }

    /**
     * The text around the cursor at the start of a joystick drag, and where the cursor is in it
     * ([cursor], moved as ticks arrive). [anchor] is the fixed end of a selecting drag.
     */
    @Suppress("LongParameterList") // the window's text and where it is, then the drag's mode
    private class Drag(
        val text: String,
        /** Field offset of the window's first char. */
        val base: Int,
        val select: Boolean,
        val anchor: Int,
        /** The window starts / ends where the field does (nothing lies beyond it). */
        val atStart: Boolean,
        val atEnd: Boolean,
        private val settled: () -> EditorSelection?,
    ) {
        var cursor = anchor
        private val sent = IntArray(SENT_KEPT)
        private var sentCount = 0

        init {
            remember(anchor)
        }

        fun remember(position: Int) {
            sent[sentCount++ % SENT_KEPT] = position
        }

        /**
         * The cursor after [dx] grapheme steps, clamped to the field; null where the window does
         * not cover them (a step lands near its edge while the field goes on).
         */
        @Suppress("ReturnCount") // out-of-window exits
        fun stepped(dx: Int): Int? {
            var at = cursor
            repeat(abs(dx)) {
                if (dx < 0) {
                    if (at == 0 && atStart) return@repeat
                    if (at < EDGE_MARGIN && !atStart) return null
                    at -= Graphemes.lastClusterLength(text.substring(0, at))
                } else {
                    val left = text.length - at
                    if (left == 0 && atEnd) return@repeat
                    if (left < EDGE_MARGIN && !atEnd) return null
                    at += Graphemes.clusterLengthAt(text, at)
                }
            }
            return at
        }

        /** An own edit is in flight (or the tracked selection unknown): nothing can be told. */
        fun editInFlight(): Boolean = settled() == null

        /**
         * The editor still shows what the drag put there. The tracked selection says so unless it
         * is none of the positions sent lately (another finger typed, the app moved the cursor).
         */
        fun consistent(): Boolean {
            val live = settled()?.normalised() ?: return false
            return (0 until minOf(sentCount, SENT_KEPT)).any { i ->
                val at = sent[i]
                val start = base + if (select) minOf(anchor, at) else at
                val end = base + if (select) maxOf(anchor, at) else at
                live.selStart == start && live.selEnd == end
            }
        }
    }

    /** Opens the editing panel (the strip's edit button), with select mode off. */
    fun openPanel() {
        selectMode = false
        deferred = null
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
        awaitingSettle = false
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
        if (swipeTried || awaitingSettle) return null
        originUnsettled = false
        val origin = readOrigin()
        // An unknown field offset with an own edit in flight is not final: the first move after
        // the tracker settles retries (not every move, each of which would read the editor).
        if (origin == null) {
            if (originUnsettled) awaitingSettle = true else swipeTried = true
        }
        return origin?.also {
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
        val read = editor.surroundingText(SWIPE_CONTEXT_CHARS, 0)?.takeIf { it.collapsed }
        val base = read?.let(::fieldBase) ?: -1
        if (read != null && base < 0) originUnsettled = true
        return if (read != null && base >= 0) {
            Swipe(base + read.selectionStart, read.text.subSequence(0, read.selectionStart))
        } else {
            null
        }
    }

    /**
     * Where [read]'s text begins in the field: its offset, or where the tracked selection puts it
     * when the editor reports none. -1 while that cannot be told (an own edit is in flight, or the
     * tracker disagrees with the text).
     */
    private fun fieldBase(read: Surrounding): Int {
        if (read.offset >= 0) return read.offset
        val tracked =
            settledSelection()?.takeIf {
                it.known && it.selEnd - it.selStart == read.selectionEnd - read.selectionStart
            }
        return tracked?.let { (it.selStart - read.selectionStart).coerceAtLeast(-1) } ?: -1
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
                deferred = null
                showPanel(KeyboardPanel.Keys)
            }
            else -> {
                deferred = null
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

    /** The selection grows to the line(s) around it. */
    fun expandSelectionToLine(): Boolean = selectAround(TextUnits::expandLine)

    /** The selection grows to the whole [unit]s around it (the strip's Word, Sentence, Line). */
    fun expandSelection(unit: SelectionUnit): Boolean =
        selectAround(expand = { text, start, end -> TextUnits.expand(unit, text, start, end) })

    /**
     * Moves one [edge] of the selection a [unit] back or [forward], snapped to unit boundaries (see
     * [UnitEdges.move]). The selection never inverts; it can shrink to a caret. False where nothing
     * was changed.
     */
    fun moveSelectionEdge(unit: SelectionUnit, edge: SelectionEdge, forward: Boolean): Boolean =
        selectAround(
            expand = { text, start, end ->
                UnitEdges.move(unit, text, TextSpan(start, end), edge, forward)
            }
        )

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
        awaitingSettle = false
        swipeActive = false
        deferred = null
        drag = null
        dragUnsupported = false
    }

    /** The selected range and its text, read from the editor; null where nothing is selected. */
    private class Selected(val range: TextSpan, val text: String)

    private fun readSelected(): Selected? =
        editor
            .takeIf { !sensitive() }
            ?.surroundingText(0, 0)
            ?.takeIf { !it.collapsed && it.selectionEnd - it.selectionStart <= MAX_SELECTION_CHARS }
            ?.let { read ->
                val base = fieldBase(read).takeIf { it >= 0 } ?: return@let null
                Selected(
                    TextSpan(base + read.selectionStart, base + read.selectionEnd),
                    read.text.subSequence(read.selectionStart, read.selectionEnd).toString(),
                )
            }

    /**
     * The text around the selection, and where the selection is in it; null where unreadable. A
     * [base] below 0 means the field position is not known yet (see [fieldBase]).
     */
    private class Window(val text: String, val base: Int, val start: Int, val end: Int)

    private fun readWindow(): Window? =
        editor
            .takeIf { !sensitive() }
            ?.surroundingText(CONTEXT_CHARS, CONTEXT_CHARS)
            ?.let { Window(it.text.toString(), fieldBase(it), it.selectionStart, it.selectionEnd) }

    /**
     * Selects what [expand] makes of the selection (or the cursor) and the text around it (the text
     * before it, the selected text and the text after it). False where there is nothing to change.
     */
    private fun selectAround(
        expand: (CharSequence, Int, Int) -> TextSpan?,
        askedAt: Long = clock(),
    ): Boolean {
        deferred = null
        val window = readWindow()
        if (window != null && window.base < 0) {
            // The editor gave no offset and an own edit is in flight: ask again once it settles,
            // still timed from when the user asked.
            deferred = expand
            deferredAt = askedAt
            return false
        }
        val span = window?.let { w ->
            expand(w.text, w.start, w.end)
                ?.takeIf { it.start != w.start || it.end != w.end }
                ?.let { TextSpan(it.start + w.base, it.end + w.base) }
        }
        span?.let { editor.setSelection(it.start, it.end) }
        return span != null
    }

    /**
     * The tracker may have settled (the platform reported the keyboard's own edits): carries out a
     * selection action that was waiting for it, unless it has waited too long to be what the user
     * still wants.
     */
    fun onEditsSettled() {
        awaitingSettle = false
        val waiting = deferred ?: return
        if (clock() - deferredAt > DEFERRED_MAX_MS) {
            deferred = null
            return
        }
        if (settledSelection() != null) selectAround(waiting, deferredAt)
    }

    /**
     * Drops a selection action still waiting for the tracker: the user moved the cursor themselves
     * or left the panel, so it would land somewhere they no longer mean.
     */
    fun dropDeferred() {
        deferred = null
    }

    private companion object {
        /** Text read each side of the cursor when a joystick drag starts. */
        const val DRAG_CONTEXT = 256

        /** Ticks further apart than this are separate drags. */
        const val DRAG_GAP_MS = 600L

        /** A step this close to the window's edge may cut a grapheme short: the keys do it. */
        const val EDGE_MARGIN = 32

        /** How many of the positions a drag set are remembered. */
        const val SENT_KEPT = 8

        /** How far before the cursor a backspace swipe looks for word boundaries. */
        const val SWIPE_CONTEXT_CHARS = 256

        /** How far around the selection words, sentences and lines are looked for. */
        const val CONTEXT_CHARS = 1000
        /** Longer selections are left alone by the case change and the engine hook. */
        const val MAX_SELECTION_CHARS = 4000

        /** How long a selection action waits for the tracker to settle. */
        const val DEFERRED_MAX_MS = 1500L
    }
}
