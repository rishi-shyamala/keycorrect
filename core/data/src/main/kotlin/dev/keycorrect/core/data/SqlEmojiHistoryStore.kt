package dev.keycorrect.core.data

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** [EmojiHistoryStore] on [KeyCorrectDatabase] (`emoji_recents`, `emoji_variants`; schema v4). */
class SqlEmojiHistoryStore(
    private val database: KeyCorrectDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : EmojiHistoryStore {
    private val writeLock = Mutex()

    override suspend fun recents(limit: Int): List<String> {
        val n = limit.coerceIn(0, EmojiHistoryStore.MAX_RECENTS)
        if (n == 0) return emptyList()
        return database.read { db ->
            db.query(
                    "SELECT emoji FROM emoji_recents ORDER BY last_used DESC LIMIT ?",
                    arrayOf<Any?>(n),
                )
                .use { c -> buildList(c.count) { while (c.moveToNext()) add(c.getString(0)) } }
        }
    }

    override suspend fun recordUse(emoji: String, base: String) {
        if (!EmojiHistoryStore.isValid(emoji) || !EmojiHistoryStore.isValid(base)) return
        val now = clock()
        writeLock.withLock {
            database.transaction { db ->
                // Strictly increasing so "most recent first" holds even within one millisecond.
                val stamp = maxOf(now, maxLastUsed(db) + 1)
                db.execSQL(
                    "INSERT INTO emoji_recents (emoji, base, last_used, use_count) " +
                        "VALUES (?, ?, ?, 1) ON CONFLICT(emoji) DO UPDATE SET " +
                        "base = excluded.base, last_used = excluded.last_used, " +
                        "use_count = use_count + 1",
                    arrayOf<Any?>(emoji, base, stamp),
                )
                db.execSQL(
                    "DELETE FROM emoji_recents WHERE emoji NOT IN " +
                        "(SELECT emoji FROM emoji_recents ORDER BY last_used DESC LIMIT ?)",
                    arrayOf<Any?>(EmojiHistoryStore.MAX_RECENTS),
                )
            }
        }
    }

    override suspend fun preferredVariant(base: String): String? = database.read { db ->
        db.query("SELECT variant FROM emoji_variants WHERE base = ?", arrayOf<Any?>(base)).use { c
            ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    override suspend fun preferredVariants(): Map<String, String> = database.read { db ->
        db.query("SELECT base, variant FROM emoji_variants", arrayOf<Any?>()).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }
    }

    override suspend fun setPreferredVariant(base: String, variant: String?) {
        if (!EmojiHistoryStore.isValid(base)) return
        if (variant != null && !EmojiHistoryStore.isValid(variant)) return
        val now = clock()
        writeLock.withLock {
            database.transaction { db ->
                if (variant == null) {
                    db.execSQL("DELETE FROM emoji_variants WHERE base = ?", arrayOf<Any?>(base))
                } else {
                    db.execSQL(
                        "INSERT INTO emoji_variants (base, variant, updated_at) VALUES (?, ?, ?) " +
                            "ON CONFLICT(base) DO UPDATE SET variant = excluded.variant, " +
                            "updated_at = excluded.updated_at",
                        arrayOf<Any?>(base, variant, now),
                    )
                }
            }
        }
    }

    override suspend fun dump(): EmojiHistoryDump = database.read { db -> readDump(db) }

    override suspend fun clearSince(sinceMillis: Long) {
        writeLock.withLock {
            database.transaction { db ->
                db.execSQL(
                    "DELETE FROM emoji_recents WHERE last_used >= ?",
                    arrayOf<Any?>(sinceMillis),
                )
                db.execSQL(
                    "DELETE FROM emoji_variants WHERE updated_at >= ?",
                    arrayOf<Any?>(sinceMillis),
                )
            }
        }
    }

    override suspend fun clear() {
        writeLock.withLock {
            database.transaction { db ->
                Schema.EMOJI_TABLES.forEach { db.execSQL("DELETE FROM $it") }
            }
        }
    }

    private fun maxLastUsed(db: SupportSQLiteDatabase): Long =
        db.query("SELECT MAX(last_used) FROM emoji_recents").use { c ->
            // MAX over no rows is NULL (getLong then returns 0, which is fine as a floor).
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else Long.MIN_VALUE / 2
        }

    internal companion object {
        @Suppress("MagicNumber") // Column positions of the two queries.
        fun readDump(db: SupportSQLiteDatabase): EmojiHistoryDump =
            EmojiHistoryDump(
                recents =
                    db.query(
                            "SELECT emoji, base, last_used, use_count FROM emoji_recents " +
                                "ORDER BY last_used DESC"
                        )
                        .use { c ->
                            buildList(c.count) {
                                while (c.moveToNext()) {
                                    add(
                                        EmojiRecentRow(
                                            c.getString(0),
                                            c.getString(1),
                                            c.getLong(2),
                                            c.getInt(3),
                                        )
                                    )
                                }
                            }
                        },
                variants =
                    db.query("SELECT base, variant, updated_at FROM emoji_variants ORDER BY base")
                        .use { c ->
                            buildList(c.count) {
                                while (c.moveToNext()) {
                                    add(
                                        EmojiVariantRow(
                                            c.getString(0),
                                            c.getString(1),
                                            c.getLong(2),
                                        )
                                    )
                                }
                            }
                        },
            )
    }
}
