package dev.keycorrect.feature.keyboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.feature.keyboard.clipboard.ClipItem
import dev.keycorrect.feature.keyboard.clipboard.ClipboardEnvironment
import dev.keycorrect.feature.keyboard.clipboard.ClipboardPanelState
import dev.keycorrect.feature.keyboard.prediction.PredictiveInput

/**
 * The clipboard history's actions: the strip chip (shown, dismissed, pasted) and the panel's paste,
 * pin, delete, clean and clear. Panel switching and the typing state stay with
 * [KeyboardController].
 */
internal class ClipboardController(
    private val clipboard: ClipboardEnvironment,
    private val panelState: ClipboardPanelState,
    private val input: PredictiveInput,
    private val tracked: TrackedEditor,
    private val profile: () -> FieldProfile,
    private val panel: () -> KeyboardPanel,
    private val composing: () -> Boolean,
    private val stripEmpty: () -> Boolean,
    private val selectionStripVisible: () -> Boolean,
    private val clearComposing: () -> Unit,
    private val showKeysKeepingLayer: () -> Unit,
    private val updateAutoCapitalize: () -> Unit,
) {
    /** A clip tapped on the strip chip is not offered again; set here so the chip hides. */
    private var chipDismissedFor by mutableStateOf<Long?>(null)

    /**
     * The clipboard panel is offered when history is on and the field is not sensitive (a password
     * field has no emoji key, so there is nothing to long-press). The chip, [stripClip], is shown
     * in any field, password fields included: pasting there is a normal use.
     */
    val available: Boolean
        get() = clipboard.enabled && !profile().sensitive

    /**
     * The clip shown as a chip in the strip: only when the strip would otherwise be empty (keys
     * showing, no word being composed, no suggestions, no selection strip), history is on, the host
     * has a fresh clip and the user hasn't dismissed or pasted it. Autofill chips outrank it in the
     * UI. Reads observable state only, so a keystroke recomposes just the chip. The host must back
     * [ClipboardEnvironment.freshClip] with state (and age it out), as there is no timer here.
     */
    val stripClip: ClipItem?
        get() {
            if (!clipboard.enabled || panel() != KeyboardPanel.Keys) return null
            if (composing() || !stripEmpty() || selectionStripVisible()) return null
            return clipboard.freshClip()?.takeIf { it.id != chipDismissedFor }
        }

    /** The chip's ✕: hides it for [id] (the clip stays in the history). */
    fun dismissClipChip(id: Long) {
        chipDismissedFor = id
    }

    /**
     * Clip [id] was tapped in the panel or on the chip: pastes it into the field.
     *
     * Settling is the same as for an emoji ([EmojiPanelController.onEmojiPicked]): the composing
     * word is finished as typed and abandoned, not corrected or learned, pending suggestions are
     * dropped, and the text is then committed through [tracked] so [OwnEditTracker] expects its
     * selection update. Pasted text is never a word to learn from and no learning event is
     * produced. Allowed in a sensitive field (that is how a copied code reaches a password field);
     * the text is never read back. Images are committed by the host. The panel then returns to the
     * keys, unless the paste failed.
     */
    fun onClipPicked(id: Long) {
        input.endComposingForMove()
        val pasted =
            clipboard.paste(id) { text -> if (text.isNotEmpty()) tracked.commitText(text, 1) }
        clearComposing()
        // A failed paste (an image the field refused and the clipboard could not take) leaves the
        // panel open, so the user sees nothing happened and can pick again.
        if (!pasted) return
        chipDismissedFor = id
        showKeysKeepingLayer()
        updateAutoCapitalize()
    }

    fun pinClip(id: Long, pinned: Boolean) = clipboard.setPinned(id, pinned)

    fun deleteClip(id: Long) {
        clipboard.delete(id)
        panelState.closeMenu()
    }

    fun cleanClipUrl(id: Long) {
        clipboard.cleanUrl(id)
        panelState.closeMenu()
    }

    /** "Clear" confirmed: deletes every unpinned clip. */
    fun clearClips() {
        clipboard.clearUnpinned()
        panelState.reset()
    }
}
