package dev.keycorrect.core.data

enum class ClipKind {
    Text,
    Image,
}

/**
 * A stored clipboard entry. [text] is set for [ClipKind.Text]; [mimeType], [imageFile] (a blob name
 * in [EncryptedBlobStore]) and [imageBytes] (plaintext size) for [ClipKind.Image]. [expiresAt] is
 * null for clips that only age out through the history limits; pinned clips ignore it. [sensitive]
 * clips (for example one-time codes) are shown masked. Clip contents are user data and must never
 * be logged.
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
    val expiresAt: Long?,
    val sensitive: Boolean,
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
    val expiresAt: Long?,
    val sensitive: Boolean,
    val pinned: Boolean,
) {
    companion object {
        fun text(
            text: String,
            expiresAt: Long? = null,
            sensitive: Boolean = false,
            pinned: Boolean = false,
        ): NewClip {
            require(text.isNotEmpty()) { "Empty text clip" }
            return NewClip(ClipKind.Text, text, null, null, 0, expiresAt, sensitive, pinned)
        }

        @Suppress("LongParameterList")
        fun image(
            imageFile: String,
            mimeType: String,
            imageBytes: Long,
            expiresAt: Long? = null,
            sensitive: Boolean = false,
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
                expiresAt,
                sensitive,
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

    /**
     * Inserts [clip] and returns its id. A text clip equal to the newest clip's text is not
     * duplicated: the newest clip's creation time (and expiry, unless pinned) is bumped instead and
     * its id returned.
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
