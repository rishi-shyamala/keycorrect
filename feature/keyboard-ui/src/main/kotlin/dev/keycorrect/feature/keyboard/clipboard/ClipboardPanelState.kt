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
