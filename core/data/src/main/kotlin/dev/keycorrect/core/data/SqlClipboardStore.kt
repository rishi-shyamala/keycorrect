package dev.keycorrect.core.data

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** [ClipboardStore] on [KeyCorrectDatabase] (`clips`; schema v5). */
class SqlClipboardStore(
    private val database: KeyCorrectDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : ClipboardStore {
    private val writeLock = Mutex()

    @Suppress("MagicNumber") // Column positions of COLUMNS.
    override suspend fun clips(): List<StoredClip> = database.read { db ->
        db.query("SELECT $COLUMNS FROM clips ORDER BY created_at DESC, id DESC").use { c ->
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
                            expiresAt = if (c.isNull(8)) null else c.getLong(8),
                            sensitive = c.getInt(9) != 0,
                        )
                    )
                }
            }
        }
    }

    override suspend fun insert(clip: NewClip): Long {
        val now = clock()
        return writeLock.withLock {
            database.transaction { db ->
                val newest = newest(db)
                // Strictly increasing so "newest first" holds even within one millisecond.
                val stamp = maxOf(now, (newest?.createdAt ?: Long.MIN_VALUE / 2) + 1)
                if (newest != null && newest.duplicates(clip)) {
                    db.execSQL(
                        "UPDATE clips SET created_at = ?, sensitive = ?, " +
                            "expires_at = CASE WHEN pinned = 1 THEN expires_at ELSE ? END " +
                            "WHERE id = ?",
                        arrayOf<Any?>(stamp, clip.sensitive.toInt(), clip.expiresAt, newest.id),
                    )
                    return@transaction newest.id
                }
                db.execSQL(
                    "INSERT INTO clips (kind, text, mime_type, image_file, image_bytes, " +
                        "created_at, pinned, expires_at, sensitive) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        clip.kind.name,
                        clip.text,
                        clip.mimeType,
                        clip.imageFile,
                        clip.imageBytes,
                        stamp,
                        clip.pinned.toInt(),
                        clip.expiresAt,
                        clip.sensitive.toInt(),
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

    private class Newest(
        val id: Long,
        val createdAt: Long,
        val isText: Boolean,
        val text: String?,
    ) {
        fun duplicates(clip: NewClip) = isText && clip.kind == ClipKind.Text && text == clip.text
    }

    @Suppress("MagicNumber")
    private fun newest(db: SupportSQLiteDatabase): Newest? =
        db.query(
                "SELECT id, created_at, kind, text FROM clips ORDER BY created_at DESC, id DESC LIMIT 1"
            )
            .use { c ->
                if (!c.moveToFirst()) {
                    null
                } else {
                    Newest(
                        c.getLong(0),
                        c.getLong(1),
                        c.getString(2) == ClipKind.Text.name,
                        if (c.isNull(3)) null else c.getString(3),
                    )
                }
            }

    private fun Boolean.toInt(): Int = if (this) 1 else 0

    private companion object {
        const val COLUMNS =
            "id, kind, text, mime_type, image_file, image_bytes, created_at, pinned, " +
                "expires_at, sensitive"
        const val DELETE_CHUNK = 500
    }
}
