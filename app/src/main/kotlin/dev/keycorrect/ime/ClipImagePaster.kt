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
    override fun paste(clip: StoredClip): Boolean {
        val name = clip.imageFile
        val mime = clip.mimeType
        if (name == null || mime == null) return false
        val uri = ClipImageProvider.uriFor(context, name)
        return commitToField(uri, mime) || putOnClipboard(uri)
    }

    private fun commitToField(uri: Uri, mime: String): Boolean {
        val t = target()
        val info = t.editorInfo
        val connection = t.connection
        if (connection == null || info == null || !accepts(info, mime)) return false
        val description = ClipDescription(CLIPBOARD_LABEL, arrayOf(mime))
        return InputConnectionCompat.commitContent(
            connection,
            info,
            InputContentInfoCompat(uri, description, null),
            InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
            null,
        )
    }

    private fun accepts(info: EditorInfo, mime: String): Boolean =
        EditorInfoCompat.getContentMimeTypes(info).any {
            ClipDescription.compareMimeTypes(mime, it)
        }

    private fun putOnClipboard(uri: Uri): Boolean {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return false
        return try {
            manager.setPrimaryClip(ClipData.newUri(context.contentResolver, CLIPBOARD_LABEL, uri))
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
