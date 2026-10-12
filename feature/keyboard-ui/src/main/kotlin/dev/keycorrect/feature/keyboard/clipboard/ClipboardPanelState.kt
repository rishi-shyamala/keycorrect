package dev.keycorrect.feature.keyboard.clipboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What the clipboard panel remembers while it is open: the card whose actions are showing, and
 * whether "Clear all?" is waiting for an answer. Both are inline (the IME has no window for a menu
 * or a dialog) and both are dropped when the panel closes.
 */
internal class ClipboardPanelState {
    /** The clip whose Pin/Delete/Clean-link actions replace its preview, if any. */
    var menuFor: Long? by mutableStateOf(null)
        private set

    /** The title row asks "Clear all? Yes / Cancel". */
    var confirmingClear: Boolean by mutableStateOf(false)
        private set

    fun openMenu(id: Long) {
        menuFor = id
        confirmingClear = false
    }

    fun closeMenu() {
        menuFor = null
    }

    fun askToClear() {
        confirmingClear = true
        menuFor = null
    }

    fun cancelClear() {
        confirmingClear = false
    }

    fun reset() {
        menuFor = null
        confirmingClear = false
    }
}

/** What an unflagged clip shows in a sensitive field: no text, not even its length. */
internal const val FIELD_MASK = "••••••"

/**
 * The text a clip is shown as on the strip chip, or null for an image (the caller words that). In a
 * sensitive field (a password field) only a mask is shown: the host's own masked preview for a clip
 * it flagged sensitive, else a fixed run of dots, never the clip's text.
 */
internal fun chipText(clip: ClipItem, fieldSensitive: Boolean): String? =
    when {
        clip.kind == ClipItem.Kind.Image -> null
        clip.sensitive -> clip.preview
        fieldSensitive -> FIELD_MASK
        else -> clip.preview
    }
