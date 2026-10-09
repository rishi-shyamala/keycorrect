package dev.keycorrect.core.data

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** [ShortcutStore] on [KeyCorrectDatabase]; same semantics as [SqlUserDictionaryStore]. */
class SqlShortcutStore(
    private val database: KeyCorrectDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : ShortcutStore {
    private val writeLock = Mutex()
    @Volatile
    override var snapshot: ShortcutSnapshot = ShortcutSnapshot.EMPTY
        private set

    override suspend fun load() {
        writeLock.withLock { snapshot = readSnapshot() }
    }

    override suspend fun put(trigger: String, expansion: String, mode: ShortcutMode): Shortcut {
        Validation.requireValidShortcut(trigger, expansion)
        val key = Validation.normalizeTrigger(trigger)
        val now = clock()
        return writeLock.withLock {
            val stored =
                database.transaction(DbTable.SHORTCUTS) { db ->
                    db.execSQL(
                        "INSERT INTO shortcuts (\"trigger\", expansion, mode, created_at, " +
                            "updated_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT(\"trigger\") " +
                            "DO UPDATE SET expansion = excluded.expansion, " +
                            "mode = excluded.mode, updated_at = excluded.updated_at",
                        arrayOf<Any?>(key, expansion, mode.name, now, now),
                    )
                    queryOne(db, key)!!
                }
            snapshot = readSnapshot()
            stored
        }
    }

    override suspend fun remove(trigger: String): Boolean {
        val key = Validation.normalizeTrigger(trigger)
        return writeLock.withLock {
            val changed =
                database.transaction(DbTable.SHORTCUTS) { db ->
                    db.compileStatement("DELETE FROM shortcuts WHERE \"trigger\" = ?").use { st ->
                        st.bindString(1, key)
                        st.executeUpdateDelete() > 0
                    }
                }
            if (changed) snapshot = readSnapshot()
            changed
        }
    }

    override suspend fun contains(trigger: String): Boolean = get(trigger) != null

    override suspend fun get(trigger: String): Shortcut? {
        val key = Validation.normalizeTrigger(trigger)
        return database.read { db -> queryOne(db, key) }
    }

    override fun all(): Flow<List<Shortcut>> =
        database.observe(DbTable.SHORTCUTS) { database.read { db -> queryAll(db) } }

    private suspend fun readSnapshot(): ShortcutSnapshot =
        ShortcutSnapshot(database.read { db -> queryAll(db) }.associateBy { it.trigger })

    private fun queryAll(db: SupportSQLiteDatabase): List<Shortcut> =
        db.query("SELECT $COLUMNS FROM shortcuts ORDER BY \"trigger\"").use { c ->
            buildList(c.count) { while (c.moveToNext()) add(c.toShortcut()) }
        }

    private fun queryOne(db: SupportSQLiteDatabase, trigger: String): Shortcut? =
        db.query("SELECT $COLUMNS FROM shortcuts WHERE \"trigger\" = ?", arrayOf<Any?>(trigger))
            .use { c -> if (c.moveToFirst()) c.toShortcut() else null }

    private fun android.database.Cursor.toShortcut() =
        Shortcut(
            trigger = getString(0),
            expansion = getString(1),
            mode =
                ShortcutMode.entries.firstOrNull { it.name == getString(2) }
                    ?: ShortcutMode.SUGGEST,
            createdAt = getLong(3),
            updatedAt = getLong(4),
        )

    private companion object {
        const val COLUMNS = "\"trigger\", expansion, mode, created_at, updated_at"
    }
}
