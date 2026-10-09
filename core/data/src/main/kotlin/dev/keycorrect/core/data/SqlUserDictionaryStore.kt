package dev.keycorrect.core.data

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [UserDictionaryStore] on [KeyCorrectDatabase]. Writes are serialised and refresh [snapshot]
 * before returning, so a decoder that reads [snapshot] sees a completed write immediately.
 */
class SqlUserDictionaryStore(
    private val database: KeyCorrectDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : UserDictionaryStore {
    private val writeLock = Mutex()
    @Volatile
    override var snapshot: UserWordSnapshot = UserWordSnapshot.EMPTY
        private set

    override suspend fun load() {
        writeLock.withLock { snapshot = readSnapshot() }
    }

    override suspend fun add(word: String, frequency: Int, locale: String?): UserWord {
        Validation.requireValidWord(word, frequency)
        val key = Validation.normalizeWord(word)
        val loc = Validation.normalizeLocale(locale)
        val now = clock()
        return writeLock.withLock {
            val stored =
                database.transaction(DbTable.USER_WORDS) { db ->
                    db.execSQL(
                        "INSERT INTO user_words (word, frequency, locale, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?) ON CONFLICT(word) DO UPDATE SET " +
                            "frequency = excluded.frequency, locale = excluded.locale, " +
                            "updated_at = excluded.updated_at",
                        arrayOf<Any?>(key, frequency, loc, now, now),
                    )
                    queryOne(db, key)!!
                }
            snapshot = readSnapshot()
            stored
        }
    }

    override suspend fun update(word: String, frequency: Int, locale: String?): Boolean {
        Validation.requireValidWord(word, frequency)
        val key = Validation.normalizeWord(word)
        val loc = Validation.normalizeLocale(locale)
        val now = clock()
        return writeLock.withLock {
            val changed =
                database.transaction(DbTable.USER_WORDS) { db ->
                    db.compileStatement(
                            "UPDATE user_words SET frequency = ?, locale = ?, updated_at = ? " +
                                "WHERE word = ?"
                        )
                        .use { st ->
                            st.bindLong(1, frequency.toLong())
                            if (loc == null) st.bindNull(2) else st.bindString(2, loc)
                            st.bindLong(3, now)
                            st.bindString(4, key)
                            st.executeUpdateDelete() > 0
                        }
                }
            if (changed) snapshot = readSnapshot()
            changed
        }
    }

    override suspend fun remove(word: String): Boolean {
        val key = Validation.normalizeWord(word)
        return writeLock.withLock {
            val changed =
                database.transaction(DbTable.USER_WORDS) { db ->
                    db.compileStatement("DELETE FROM user_words WHERE word = ?").use { st ->
                        st.bindString(1, key)
                        st.executeUpdateDelete() > 0
                    }
                }
            if (changed) snapshot = readSnapshot()
            changed
        }
    }

    override suspend fun contains(word: String): Boolean {
        val key = Validation.normalizeWord(word)
        return database.read { db ->
            db.query("SELECT 1 FROM user_words WHERE word = ? LIMIT 1", arrayOf<Any?>(key)).use {
                it.moveToFirst()
            }
        }
    }

    override fun all(): Flow<List<UserWord>> =
        database
            .invalidations(DbTable.USER_WORDS)
            .onSubscription { emit(Unit) }
            .conflate()
            .map { database.read { db -> queryAll(db) } }
            .distinctUntilChanged()

    private suspend fun readSnapshot(): UserWordSnapshot =
        UserWordSnapshot(database.read { db -> queryAll(db) }.associateBy { it.word })

    private fun queryAll(db: SupportSQLiteDatabase): List<UserWord> =
        db.query("SELECT $COLUMNS FROM user_words ORDER BY word").use { c ->
            buildList(c.count) { while (c.moveToNext()) add(c.toUserWord()) }
        }

    private fun queryOne(db: SupportSQLiteDatabase, word: String): UserWord? =
        db.query("SELECT $COLUMNS FROM user_words WHERE word = ?", arrayOf<Any?>(word)).use { c ->
            if (c.moveToFirst()) c.toUserWord() else null
        }

    private fun android.database.Cursor.toUserWord() =
        UserWord(
            word = getString(0),
            frequency = getInt(1),
            locale = if (isNull(2)) null else getString(2),
            createdAt = getLong(3),
            updatedAt = getLong(4),
        )

    private companion object {
        const val COLUMNS = "word, frequency, locale, created_at, updated_at"
    }
}
