package dev.keycorrect.core.data

enum class ClipKind {
    Text,
    Image,
}

/**
 * A stored clipboard entry. [text] is set for [ClipKind.Text]; [mimeType], [imageFile] (a blob name
 * in [EncryptedBlobStore]) and [imageBytes] (plaintext size) for [ClipKind.Image]. Clip contents
 * are user data and must never be logged.
 */
data class StoredClip(
    val id: Long,
    val kind: ClipKind,
    val text: String?,
    val mimeType: String?,
    val imageFile: String?,
    val imageBytes: Long,
    val createdAt: Long,
    val pinned: Boolean,
)

/** A clip to insert; the store assigns the id and the creation time. */
@Suppress("LongParameterList") // A flat value type; built through the two factories.
class NewClip
private constructor(
    val kind: ClipKind,
    val text: String?,
    val mimeType: String?,
    val imageFile: String?,
    val imageBytes: Long,
    val pinned: Boolean,
) {
    companion object {
        fun text(
            text: String,
            pinned: Boolean = false,
        ): NewClip {
            require(text.isNotEmpty()) { "Empty text clip" }
            return NewClip(ClipKind.Text, text, null, null, 0, pinned)
        }

        @Suppress("LongParameterList")
        fun image(
            imageFile: String,
            mimeType: String,
            imageBytes: Long,
            pinned: Boolean = false,
        ): NewClip {
            require(imageFile.isNotEmpty() && mimeType.isNotEmpty()) { "Incomplete image clip" }
            require(imageBytes >= 0) { "Negative image size" }
            return NewClip(
                ClipKind.Image,
                null,
                mimeType,
                imageFile,
                imageBytes,
                pinned,
            )
        }
    }
}

/**
 * Clipboard history in the encrypted database. This is only the row store: image bytes live in
 * [EncryptedBlobStore], and [ClipboardRepository] keeps the two in step (deleting a row here does
 * not delete its blob). All calls are suspend functions that run off the main thread.
 */
interface ClipboardStore {
    /** Every clip, newest first (pinned clips are interleaved by time; the UI groups them). */
    suspend fun clips(): List<StoredClip>

    /** The image clip stored under blob [fileName], or null. A single-row lookup. */
    suspend fun imageClip(fileName: String): StoredClip?

    /**
     * Inserts [clip] and returns its id. A text clip equal to ANY existing text clip is not
     * duplicated: that clip (the most recent such one) moves to the top (its creation time is
     * bumped) and its id is returned. Images are never deduplicated: comparing them would mean
     * decrypting and hashing every stored blob.
     */
    suspend fun insert(clip: NewClip): Long

    suspend fun setPinned(id: Long, pinned: Boolean)

    suspend fun delete(ids: Collection<Long>)

    /** Deletes every clip, or every unpinned clip when [keepPinned]. */
    suspend fun clear(keepPinned: Boolean)

    /** Deletes clips created or last re-copied at or after [sinceMillis], pinned ones too. */
    suspend fun clearSince(sinceMillis: Long)

    /** Replaces the text of a text clip (the URL-clean action). Other clips are unaffected. */
    suspend fun updateText(id: Long, text: String)
}
