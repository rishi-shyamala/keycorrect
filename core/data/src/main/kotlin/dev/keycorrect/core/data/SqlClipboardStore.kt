package dev.keycorrect.core.data

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** [ClipboardStore] on [KeyCorrectDatabase] (`clips`; schema v5). */
@Suppress("TooManyFunctions") // One member per store operation plus three small query helpers.
class SqlClipboardStore(
    private val database: KeyCorrectDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : ClipboardStore {
    private val writeLock = Mutex()

    override suspend fun clips(): List<StoredClip> = database.read { db ->
        db.query("SELECT $COLUMNS FROM clips ORDER BY created_at DESC, id DESC").use(::rows)
    }

    override suspend fun imageClip(fileName: String): StoredClip? = database.read { db ->
        db.query(
                "SELECT $COLUMNS FROM clips WHERE kind = 'Image' AND image_file = ? LIMIT 1",
                arrayOf<Any?>(fileName),
            )
            .use(::rows)
            .firstOrNull()
    }

    @Suppress("MagicNumber") // Column positions of COLUMNS.
    private fun rows(c: Cursor): List<StoredClip> =
        buildList(c.count) {
            while (c.moveToNext()) {
                add(
                    StoredClip(
                        id = c.getLong(0),
                        kind = ClipKind.valueOf(c.getString(1)),
                        text = if (c.isNull(2)) null else c.getString(2),
                        mimeType = if (c.isNull(3)) null else c.getString(3),
                        imageFile = if (c.isNull(4)) null else c.getString(4),
                        imageBytes = c.getLong(5),
                        createdAt = c.getLong(6),
                        pinned = c.getInt(7) != 0,
                    )
                )
            }
        }

    override suspend fun insert(clip: NewClip): Long {
        val now = clock()
        return writeLock.withLock {
            database.transaction { db ->
                // Strictly increasing so "newest first" holds even within one millisecond.
                val stamp = maxOf(now, (newestStamp(db) ?: Long.MIN_VALUE / 2) + 1)
                val existing =
                    if (clip.kind == ClipKind.Text) newestWithText(db, clip.text) else null
                if (existing != null) {
                    db.execSQL(
                        "UPDATE clips SET created_at = ? WHERE id = ?",
                        arrayOf<Any?>(stamp, existing),
                    )
                    return@transaction existing
                }
                db.execSQL(
                    "INSERT INTO clips (kind, text, mime_type, image_file, image_bytes, " +
                        "created_at, pinned) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        clip.kind.name,
                        clip.text,
                        clip.mimeType,
                        clip.imageFile,
                        clip.imageBytes,
                        stamp,
                        clip.pinned.toInt(),
                    ),
                )
                db.query("SELECT last_insert_rowid()").use { c ->
                    c.moveToFirst()
                    c.getLong(0)
                }
            }
        }
    }

    override suspend fun setPinned(id: Long, pinned: Boolean) {
        writeLock.withLock {
            database.transaction { db ->
                db.execSQL(
                    "UPDATE clips SET pinned = ? WHERE id = ?",
                    arrayOf<Any?>(pinned.toInt(), id),
                )
            }
        }
    }

    override suspend fun delete(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        writeLock.withLock {
            database.transaction { db ->
                // Chunked to stay far below SQLite's bound-variable limit.
                ids.distinct().chunked(DELETE_CHUNK).forEach { chunk ->
                    db.execSQL(
                        "DELETE FROM clips WHERE id IN (${chunk.joinToString { "?" }})",
                        chunk.toTypedArray<Any?>(),
                    )
                }
            }
        }
    }

    override suspend fun clear(keepPinned: Boolean) {
        writeLock.withLock {
            database.transaction { db ->
                db.execSQL(
                    if (keepPinned) "DELETE FROM clips WHERE pinned = 0" else "DELETE FROM clips"
                )
            }
        }
    }

    override suspend fun clearSince(sinceMillis: Long) {
        writeLock.withLock {
            database.transaction { db ->
                db.execSQL("DELETE FROM clips WHERE created_at >= ?", arrayOf<Any?>(sinceMillis))
            }
        }
    }

    override suspend fun updateText(id: Long, text: String) {
        writeLock.withLock {
            database.transaction { db ->
                db.execSQL(
                    "UPDATE clips SET text = ? WHERE id = ? AND kind = 'Text'",
                    arrayOf<Any?>(text, id),
                )
            }
        }
    }

    private fun newestStamp(db: SupportSQLiteDatabase): Long? =
        db.query("SELECT MAX(created_at) FROM clips").use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    /** The id of the most recent text clip equal to [text] (any position in the history). */
    private fun newestWithText(db: SupportSQLiteDatabase, text: String?): Long? =
        db.query(
                "SELECT id FROM clips WHERE kind = 'Text' AND text = ? " +
                    "ORDER BY created_at DESC, id DESC LIMIT 1",
                arrayOf<Any?>(text),
            )
            .use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    private fun Boolean.toInt(): Int = if (this) 1 else 0

    private companion object {
        const val COLUMNS = "id, kind, text, mime_type, image_file, image_bytes, created_at, pinned"
        const val DELETE_CHUNK = 500
    }
}
