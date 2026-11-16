package dev.keycorrect.feature.keyboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether the field has no text at all (nothing before or after the cursor), which is when the
 * strip's tools spread out. Known from the selection updates: a cursor past 0 or a range means
 * text. A cursor at 0 is empty as long as no text has been seen in the field (`sawText`: this costs
 * no editor read, so opening a field is as cheap as before); once text has been there (deleted back
 * to the start, cleared by the app) the editor is asked for one char after the cursor. Sensitive
 * fields are never read, and where the editor can't say, the field counts as having text.
 */
internal class FieldEmptyTracker(
    private val selection: () -> EditorSelection,
    private val sensitive: () -> Boolean,
    private val textAfterCursor: () -> CharSequence?,
) {
    /** Observable: true while the field is known to hold no text. */
    var empty by mutableStateOf(true)
        private set

    private var sawText = false

    /** The editor was asked about the text after a cursor at 0, and has not moved since. */
    private var zeroChecked = false

    fun refresh(newField: Boolean = false, externalChange: Boolean = false) {
        if (newField) sawText = false
        if (newField || externalChange) zeroChecked = false
        val sel = selection()
        val hasText = sel.known && (sel.selStart > 0 || !sel.collapsed)
        if (hasText) sawText = true
        val atZero = sel.known && sel.collapsed && sel.selStart == 0 && canAskEditor()
        val nowEmpty = !hasText && (if (atZero && zeroChecked) empty else askFieldEmpty(sel))
        zeroChecked = atZero
        if (empty != nowEmpty) empty = nowEmpty
    }

    /** Text was typed (or dictated) in the field, so it is no longer empty. */
    fun noteTextTyped() {
        sawText = true
        zeroChecked = false
        if (empty) empty = false
    }

    private fun canAskEditor() = sawText && !sensitive()

    /** Empty by the editor when it can be asked, else by whether text was ever seen. */
    private fun askFieldEmpty(sel: EditorSelection): Boolean =
        if (sel.known && canAskEditor()) textAfterCursor()?.isEmpty() ?: false else !sawText
}
