package dev.keycorrect.ime

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import dev.keycorrect.appContainer
import dev.keycorrect.core.data.EncryptedBlobStore
import dev.keycorrect.core.data.StoredClip
import java.io.FileNotFoundException
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

/**
 * Serves image clips to the app a clip is pasted into: `content://<applicationId>.clips/<blob>`.
 * Not exported, so another app can read a URI only through a per-URI grant (the editor's
 * `commitContent` grant, or the system clipboard's grant for the fallback); nothing else can open
 * or even list it. Image bytes are decrypted into a pipe as they are read; no plaintext file is
 * ever written. Names are validated ([EncryptedBlobStore.isValidName]) and must belong to a stored
 * image clip, so a path can never reach another file.
 */
@Suppress("TooGenericExceptionCaught") // Any failure means "nothing to serve".
class ClipImageProvider : ContentProvider() {
    // The Application may not exist yet in onCreate: the container is used lazily.
    override fun onCreate() = true

    override fun getType(uri: Uri): String? = clipFor(uri)?.mimeType

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val name = clipFor(uri)?.imageFile
        if (mode != "r" || name == null) throw FileNotFoundException("No such clip")
        val blobs = requireContext().appContainer.clipboardBlobs
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        EXECUTOR.execute {
            try {
                blobs.openDecrypted(name).use { input ->
                    val out = ParcelFileDescriptor.AutoCloseOutputStream(writeSide)
                    input.copyTo(out)
                    out.close()
                }
            } catch (e: Exception) {
                // A lost key or damaged file: the reader sees an error, not truncated data.
                runCatching { writeSide.closeWithError(e.javaClass.simpleName) }
            }
        }
        return readSide
    }

    /** Name and size, for apps that ask (`OpenableColumns`). */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val clip = clipFor(uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns, 1)
        cursor.addRow(
            Array<Any?>(columns.size) {
                when (columns[it]) {
                    OpenableColumns.DISPLAY_NAME -> displayName(clip)
                    OpenableColumns.SIZE -> clip.imageBytes
                    else -> null
                }
            }
        )
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Read only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    private fun clipFor(uri: Uri): StoredClip? {
        val context = context
        val name = context?.let { blobName(it, uri) }
        return if (context == null || name == null) {
            null
        } else {
            try {
                // One row by blob name, never the whole history. Binder threads only: the keyboard
                // itself never calls this on the main thread (see ClipImagePaster).
                runBlocking { context.appContainer.clipboardRepository.imageClip(name) }
            } catch (_: Exception) {
                null // database unavailable: nothing to serve
            }
        }
    }

    private fun displayName(clip: StoredClip): String =
        "clip." + (clip.mimeType?.substringAfter('/')?.substringBefore('+') ?: "img")

    companion object {
        private val EXECUTOR = Executors.newCachedThreadPool { task ->
            Thread(task, "keycorrect-clip-pipe").apply { isDaemon = true }
        }

        /** `content://<applicationId>.clips/<blobName>`. */
        fun uriFor(context: Context, blobName: String): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(blobName)
                .build()

        fun authority(context: Context): String = context.packageName + ".clips"

        /** The blob name in [uri], or null for any other authority, depth or name shape. */
        internal fun blobName(context: Context, uri: Uri): String? {
            val ours =
                uri.scheme == "content" &&
                    uri.authority == authority(context) &&
                    uri.query == null &&
                    uri.fragment == null
            return if (ours)
                uri.pathSegments.singleOrNull()?.takeIf(EncryptedBlobStore::isValidName)
            else null
        }
    }
}
