package dev.keycorrect.ime

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Toast
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import dev.keycorrect.R
import dev.keycorrect.core.data.StoredClip

/** The focused field's connection and editor info, as the keyboard service sees them now. */
internal class PasteTarget(val connection: InputConnection?, val editorInfo: EditorInfo?)

/**
 * Pastes an image clip: committed to the field with `commitContent` (a per-URI read grant on our
 * non-exported [ClipImageProvider]) when the field accepts the image's type, else put on the system
 * clipboard as a URI so the user can paste it with the app's own paste. That clipboard entry
 * carries [CLIPBOARD_LABEL] so [ClipboardCapture] does not take it into the history again.
 */
internal class ClipImagePaster(
    private val context: Context,
    private val target: () -> PasteTarget,
) : ImagePaster {
    /** The last image committed to a field, whose read grant is released at the next one. */
    private var granted: InputContentInfoCompat? = null

    /**
     * Releases the read grant of the last commit. Called only when the next image is pasted, not
     * when the field closes: an app may read a pasted image later (a chat upload, a preview).
     */
    fun release() {
        granted?.let { runCatching { it.releasePermission() } }
        granted = null
    }

    override fun paste(clip: StoredClip): Boolean {
        val name = clip.imageFile
        val mime = clip.mimeType
        if (name == null || mime == null) return false
        release() // one grant at a time
        val uri = ClipImageProvider.uriFor(context, name)
        return commitToField(uri, mime) || putOnClipboard(uri, mime)
    }

    private fun commitToField(uri: Uri, mime: String): Boolean {
        val t = target()
        val info = t.editorInfo
        val connection = t.connection
        if (connection == null || info == null || !accepts(info, mime)) return false
        val description = ClipDescription(CLIPBOARD_LABEL, arrayOf(mime))
        val content = InputContentInfoCompat(uri, description, null)
        val committed =
            InputConnectionCompat.commitContent(
                connection,
                info,
                content,
                InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                null,
            )
        if (committed) granted = content
        return committed
    }

    private fun accepts(info: EditorInfo, mime: String): Boolean =
        EditorInfoCompat.getContentMimeTypes(info).any {
            ClipDescription.compareMimeTypes(mime, it)
        }

    private fun putOnClipboard(uri: Uri, mime: String): Boolean {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return false
        return try {
            // The description is given, not derived: ClipData.newUri would ask the provider for
            // the type (getType) on this, the main, thread.
            val description = ClipDescription(CLIPBOARD_LABEL, arrayOf(mime))
            manager.setPrimaryClip(ClipData(description, ClipData.Item(uri)))
            // Android 13+ confirms a copy itself.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(context, R.string.clipboard_image_copied, Toast.LENGTH_SHORT).show()
            }
            true
        } catch (_: SecurityException) {
            false
        }
    }

    companion object {
        /** Marks clipboard content we put there ourselves. */
        const val CLIPBOARD_LABEL = "dev.keycorrect.clip"
    }
}
