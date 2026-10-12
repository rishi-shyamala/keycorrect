package dev.keycorrect.feature.keyboard.clipboard

import androidx.compose.ui.graphics.ImageBitmap

/** One clipboard history entry as the keyboard shows it (docs/PHASE4.md, PR C). */
data class ClipItem(
    val id: Long,
    val kind: Kind,
    /** One-line preview of a text clip. Never the image. */
    val preview: String,
    val createdAt: Long,
    val pinned: Boolean,
    /**
     * A single http(s) URL whose tracking parameters can be removed
     * ([ClipboardEnvironment.cleanUrl]).
     */
    val cleanableUrl: Boolean = false,
) {
    enum class Kind {
        Text,
        Image,
    }
}

/**
 * Clipboard history for the keyboard, provided by the app: capture (a primary-clip listener while
 * we are the keyboard, everything copied is kept), encrypted storage and pasting. Every member is
 * called on the main thread and must not block: the host keeps [clips] in memory and does its I/O
 * in the background. Clip contents are user data and are never logged.
 */
interface ClipboardEnvironment {
    /** History on (settings) and allowed now (not incognito). */
    val enabled: Boolean

    /** Pinned first, then newest first. */
    val clips: List<ClipItem>

    /** The newest clip if it was copied within the last minute (the strip chip), else null. */
    fun freshClip(): ClipItem?

    /** A decoded thumbnail of image clip [id], or null while it loads (the host loads it). */
    fun thumbnail(id: Long): ImageBitmap?

    /**
     * Pastes clip [id] into the focused field: text is committed through [commitText]; an image is
     * committed by the host (`commitContent`), or put back on the system clipboard if the field
     * takes no images. Returns false if the clip is gone.
     */
    fun paste(id: Long, commitText: (String) -> Unit): Boolean

    fun setPinned(id: Long, pinned: Boolean)

    fun delete(id: Long)

    /** Deletes every unpinned clip. */
    fun clearUnpinned()

    /** Removes tracking parameters from URL clip [id]. */
    fun cleanUrl(id: Long)

    companion object {
        /** No clipboard history. */
        val None =
            object : ClipboardEnvironment {
                override val enabled = false
                override val clips: List<ClipItem> = emptyList()

                override fun freshClip(): ClipItem? = null

                override fun thumbnail(id: Long): ImageBitmap? = null

                override fun paste(id: Long, commitText: (String) -> Unit) = false

                override fun setPinned(id: Long, pinned: Boolean) = Unit

                override fun delete(id: Long) = Unit

                override fun clearUnpinned() = Unit

                override fun cleanUrl(id: Long) = Unit
            }
    }
}
