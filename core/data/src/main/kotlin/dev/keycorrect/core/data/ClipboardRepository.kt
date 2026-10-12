package dev.keycorrect.core.data

import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** History limits applied by [ClipboardRepository.prune]. */
data class ClipboardLimits(
    /** Unpinned text clips kept; the oldest beyond this are deleted. */
    val maxTextClips: Int = DEFAULT_MAX_TEXT_CLIPS,
    /** Total plaintext bytes of image clips kept; the oldest unpinned images go first. */
    val maxImageBytes: Long = EncryptedBlobStore.DEFAULT_MAX_BYTES,
) {
    companion object {
        const val DEFAULT_MAX_TEXT_CLIPS = 30
    }
}

/**
 * Owns the clip rows ([ClipboardStore]) and the encrypted image files ([EncryptedBlobStore]) and
 * keeps them consistent, so a deleted clip never leaves its image behind:
 * - inserting an image writes the blob first, then the row (and removes the blob again if the row
 *   cannot be written);
 * - deleting, pruning and clearing remove rows and then their blobs;
 * - [orphanSweep] (run at start) removes blobs that have no row, left by a crash between steps.
 *
 * Mutations are serialised so the sweep cannot remove a blob whose row is about to be inserted.
 * Blocking disk and Keystore work runs on [io]. Clip contents are never logged.
 */
@Suppress("TooManyFunctions") // One method per user action; each is short.
class ClipboardRepository(
    private val store: ClipboardStore,
    private val blobs: EncryptedBlobStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()

    suspend fun clips(): List<StoredClip> = store.clips()

    /** Inserts a text clip (see [ClipboardStore.insert] for the duplicate rule). */
    suspend fun insertText(
        text: String,
        expiresAt: Long? = null,
        sensitive: Boolean = false,
    ): Long = lock.withLock { store.insert(NewClip.text(text, expiresAt, sensitive)) }

    /**
     * Encrypts [input] (read to its end, not closed) into a blob and inserts an image clip.
     *
     * @throws BlobTooLargeException when the image exceeds [maxBytes].
     */
    suspend fun insertImage(
        input: InputStream,
        mimeType: String,
        maxBytes: Long = EncryptedBlobStore.DEFAULT_MAX_BYTES,
        expiresAt: Long? = null,
        sensitive: Boolean = false,
    ): Long = lock.withLock {
        insertBlob(mimeType, expiresAt, sensitive) { blobs.write(input, maxBytes) }
    }

    /** As above for an image already in memory. */
    suspend fun insertImage(
        bytes: ByteArray,
        mimeType: String,
        maxBytes: Long = EncryptedBlobStore.DEFAULT_MAX_BYTES,
        expiresAt: Long? = null,
        sensitive: Boolean = false,
    ): Long = lock.withLock {
        insertBlob(mimeType, expiresAt, sensitive) { blobs.write(bytes, maxBytes) }
    }

    private suspend fun insertBlob(
        mimeType: String,
        expiresAt: Long?,
        sensitive: Boolean,
        write: () -> String,
    ): Long {
        val name = withContext(io) { write() }
        var stored = false
        try {
            val size = withContext(io) { blobs.sizeOf(name) } ?: throw IOException("Blob vanished")
            return store.insert(NewClip.image(name, mimeType, size, expiresAt, sensitive)).also {
                stored = true
            }
        } finally {
            if (!stored) withContext(NonCancellable + io) { blobs.delete(name) }
        }
    }

    /** A stream of the decrypted image of [clip]; the caller closes it. */
    suspend fun openImage(clip: StoredClip): InputStream {
        val name = clip.imageFile ?: throw IOException("Not an image clip")
        return withContext(io) { blobs.openDecrypted(name) }
    }

    suspend fun setPinned(id: Long, pinned: Boolean) = lock.withLock { store.setPinned(id, pinned) }

    suspend fun updateText(id: Long, text: String) = lock.withLock { store.updateText(id, text) }

    suspend fun delete(ids: Collection<Long>) = lock.withLock {
        val doomed = ids.toSet()
        removeRows(store.clips().filter { it.id in doomed }) { store.delete(doomed) }
    }

    suspend fun clear(keepPinned: Boolean) = lock.withLock {
        removeRows(store.clips().filter { !keepPinned || !it.pinned }) {
            store.clear(keepPinned)
        }
    }

    /** Forgets clips created at or after [sinceMillis], pinned ones too, with their images. */
    suspend fun clearSince(sinceMillis: Long) = lock.withLock {
        removeRows(store.clips().filter { it.createdAt >= sinceMillis }) {
            store.clearSince(sinceMillis)
        }
    }

    /**
     * Applies the history rules and returns how many clips were deleted: unpinned expired clips
     * first, then unpinned text clips beyond [ClipboardLimits.maxTextClips], then the oldest
     * unpinned images until the images fit [ClipboardLimits.maxImageBytes] (pinned images count
     * towards the total but are never removed).
     */
    suspend fun prune(
        limits: ClipboardLimits = ClipboardLimits(),
        nowMillis: Long = clock(),
    ): Int = lock.withLock {
        val newestFirst = store.clips()
        val doomed = LinkedHashSet<Long>()
        newestFirst
            .filter { !it.pinned && it.expiresAt?.let { t -> t <= nowMillis } == true }
            .forEach { doomed += it.id }
        newestFirst
            .filter { it.kind == ClipKind.Text && !it.pinned && it.id !in doomed }
            .drop(limits.maxTextClips.coerceAtLeast(0))
            .forEach { doomed += it.id }
        var imageTotal =
            newestFirst
                .filter { it.kind == ClipKind.Image && it.id !in doomed }
                .sumOf { it.imageBytes }
        newestFirst
            .filter { it.kind == ClipKind.Image && !it.pinned && it.id !in doomed }
            .asReversed() // oldest first
            .forEach {
                if (imageTotal > limits.maxImageBytes) {
                    imageTotal -= it.imageBytes
                    doomed += it.id
                }
            }
        if (doomed.isNotEmpty()) {
            removeRows(newestFirst.filter { it.id in doomed }) { store.delete(doomed) }
        }
        doomed.size
    }

    /**
     * Run once at start. Deletes blob files that no clip refers to (including half-written ones)
     * and clip rows whose blob is gone. Returns the number of files and rows removed.
     */
    suspend fun orphanSweep(): Int = lock.withLock {
        val clips = store.clips()
        val referenced = clips.mapNotNullTo(HashSet()) { it.imageFile }
        val files = withContext(io) { blobs.list() }
        val orphans = files.filter {
            it.removeSuffix(EncryptedBlobStore.TEMP_SUFFIX) !in referenced
        }
        withContext(io) { orphans.forEach { blobs.delete(it) } }
        val present = files.toHashSet()
        val dangling =
            clips.filter { it.kind == ClipKind.Image && it.imageFile !in present }.map { it.id }
        if (dangling.isNotEmpty()) store.delete(dangling)
        orphans.size + dangling.size
    }

    // Rows first, then blobs: a crash in between leaves orphan blobs (swept at start), never a
    // row pointing at a missing image.
    private suspend fun removeRows(rows: List<StoredClip>, deleteRows: suspend () -> Unit) {
        deleteRows()
        val names = rows.mapNotNull { it.imageFile }
        if (names.isNotEmpty()) withContext(io) { names.forEach { blobs.delete(it) } }
    }
}
