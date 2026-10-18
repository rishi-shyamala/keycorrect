package dev.keycorrect.core.data

import dev.keycorrect.core.learning.model.JournalEntry
import dev.keycorrect.core.learning.model.PersistedLearning

/** Everything a backup restore writes to the encrypted database, as plain rows. */
class PersonalData(
    val words: List<UserWord>,
    val shortcuts: List<Shortcut>,
    val emoji: EmojiHistoryDump,
    val learning: PersistedLearning,
    val journal: List<JournalEntry>,
)

/**
 * Replaces the user dictionary, shortcuts, emoji history and learning state (with its journal) in
 * ONE transaction: after an exception nothing has changed. Used by the backup import; the caller
 * then reloads the in-memory snapshots of the stores.
 */
fun interface PersonalDataWriter {
    suspend fun replaceAll(data: PersonalData)
}

/** [PersonalDataWriter] on [KeyCorrectDatabase]. */
class SqlPersonalDataWriter(
    private val database: KeyCorrectDatabase,
    private val learning: SqlLearningStore,
) : PersonalDataWriter {
    override suspend fun replaceAll(data: PersonalData) {
        learning.withWriteLock {
            database.transaction(DbTable.USER_WORDS, DbTable.SHORTCUTS) { db ->
                db.execSQL("DELETE FROM user_words")
                for (w in data.words) {
                    db.execSQL(
                        "INSERT INTO user_words (word, frequency, locale, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?)",
                        arrayOf<Any?>(w.word, w.frequency, w.locale, w.createdAt, w.updatedAt),
                    )
                }
                db.execSQL("DELETE FROM shortcuts")
                for (s in data.shortcuts) {
                    db.execSQL(
                        "INSERT INTO shortcuts (\"trigger\", expansion, mode, created_at, " +
                            "updated_at) VALUES (?, ?, ?, ?, ?)",
                        arrayOf<Any?>(
                            s.trigger,
                            s.expansion,
                            s.mode.name,
                            s.createdAt,
                            s.updatedAt,
                        ),
                    )
                }
                Schema.EMOJI_TABLES.forEach { db.execSQL("DELETE FROM $it") }
                for (r in data.emoji.recents) {
                    db.execSQL(
                        "INSERT INTO emoji_recents (emoji, base, last_used, use_count) " +
                            "VALUES (?, ?, ?, ?)",
                        arrayOf<Any?>(r.emoji, r.base, r.lastUsed, r.useCount),
                    )
                }
                for (v in data.emoji.variants) {
                    db.execSQL(
                        "INSERT INTO emoji_variants (base, variant, updated_at) VALUES (?, ?, ?)",
                        arrayOf<Any?>(v.base, v.variant, v.updatedAt),
                    )
                }
                learning.replaceAllIn(db, data.learning, data.journal)
            }
        }
    }
}
