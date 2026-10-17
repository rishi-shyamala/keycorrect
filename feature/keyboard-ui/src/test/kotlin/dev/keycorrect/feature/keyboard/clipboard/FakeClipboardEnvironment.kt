package dev.keycorrect.feature.keyboard.clipboard

import androidx.compose.ui.graphics.ImageBitmap

/** An in-memory [ClipboardEnvironment] that records every call the keyboard makes. */
internal class FakeClipboardEnvironment(
    override var enabled: Boolean = true,
    override var clips: List<ClipItem> = emptyList(),
) : ClipboardEnvironment {
    /** The text pasting each clip commits. */
    val texts = mutableMapOf<Long, String>()
    var fresh: ClipItem? = null
    val pasted = mutableListOf<Long>()
    val pins = mutableListOf<Pair<Long, Boolean>>()
    val deleted = mutableListOf<Long>()
    val cleaned = mutableListOf<Long>()
    var clears = 0

    override fun freshClip(): ClipItem? = fresh

    override fun thumbnail(id: Long): ImageBitmap? = null

    override fun paste(id: Long, commitText: (String) -> Unit): Boolean {
        pasted += id
        val text = texts[id] ?: return false
        commitText(text)
        return true
    }

    override fun setPinned(id: Long, pinned: Boolean) {
        pins += id to pinned
    }

    override fun delete(id: Long) {
        deleted += id
    }

    override fun clearUnpinned() {
        clears++
    }

    override fun cleanUrl(id: Long) {
        cleaned += id
    }

    companion object {
        fun clip(
            id: Long,
            preview: String,
            pinned: Boolean = false,
            kind: ClipItem.Kind = ClipItem.Kind.Text,
        ) = ClipItem(id, kind, preview, createdAt = id, pinned = pinned)
    }
}
