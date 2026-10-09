package dev.keycorrect.core.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * The keyboard's encrypted SQLite database (SQLCipher), `keycorrect.db` in the app's default
 * credential-encrypted storage. Not usable before first unlock (by design, see PLAN section 15).
 *
 * Create exactly one instance per process and hold it in the application graph; opening a second
 * instance on the same file works but loses cross-instance change notification.
 *
 * The file is opened lazily on first use, on [dispatcher], so constructing this is free. Every
 * database access is a suspend function that hops to [dispatcher] (default [Dispatchers.IO]), so
 * callers on the main thread never block. WAL mode and foreign keys are enabled.
 *
 * Key handling: the 32-byte key from [keyProvider] is passed to SQLCipher as a raw key (`x'<64
 * hex>'`), which skips the passphrase KDF (the key is already uniformly random), keeping open time
 * low. SQLCipher keeps a reference to that byte array for the lifetime of the open database because
 * its connection pool reuses it to open additional (WAL reader) connections; it therefore cannot be
 * zeroed while the database is open. What we do zero immediately: the decrypted key returned by the
 * provider, and the array itself if opening fails. See [KeystoreDatabaseKeyProvider] for the threat
 * model.
 */
class KeyCorrectDatabase
internal constructor(
    context: Context,
    private val keyProvider: DatabaseKeyProvider,
    private val dispatcher: CoroutineDispatcher,
    private val name: String,
    private val version: Int,
    private val migrations: List<Migration>,
) {
    private val appContext = context.applicationContext
    private val lock = Any()
    @Volatile private var db: SupportSQLiteDatabase? = null
    @Volatile private var helper: SupportSQLiteOpenHelper? = null

    private val tracker = InvalidationTracker()

    /** Emits `query()` now and after each committed change to [table]. */
    internal fun <T> observe(table: DbTable, query: suspend () -> T): Flow<T> =
        tracker.observe(table, query)

    internal fun notifyChanged(vararg tables: DbTable) = tracker.notifyChanged(*tables)

    /** Runs [block] against the database on [dispatcher], outside any explicit transaction. */
    internal suspend fun <T> read(block: (SupportSQLiteDatabase) -> T): T =
        withContext(dispatcher) { block(database()) }

    /**
     * Runs [block] in a transaction on [dispatcher]. It commits if [block] returns and rolls back
     * if it throws. After a commit, observers of [changed] tables are notified.
     */
    suspend fun <T> transaction(
        vararg changed: DbTable,
        block: (SupportSQLiteDatabase) -> T,
    ): T {
        val result =
            withContext(dispatcher) {
                val database = database()
                database.beginTransaction()
                try {
                    block(database).also { database.setTransactionSuccessful() }
                } finally {
                    database.endTransaction()
                }
            }
        notifyChanged(*changed)
        return result
    }

    /** Closes the database. It is transparently reopened by the next call. */
    fun close() {
        synchronized(lock) {
            helper?.close()
            helper = null
            db = null
        }
    }

    /**
     * Deletes the database files and the encryption key. Use only to recover from
     * [KeyUnavailableException] or on an explicit "erase all data" request. All stored data is
     * lost; the next call creates a fresh database with a fresh key.
     */
    suspend fun destroy() {
        withContext(dispatcher) {
            synchronized(lock) {
                close()
                appContext.deleteDatabase(name)
                keyProvider.reset()
            }
        }
    }

    /** The database file, for diagnostics and tests. */
    fun file(): File = appContext.getDatabasePath(name)

    private fun database(): SupportSQLiteDatabase =
        db ?: synchronized(lock) { db ?: open().also { db = it } }

    private fun open(): SupportSQLiteDatabase {
        loadNativeLibrary()
        val rawKey = keyProvider.getOrCreatePassphrase()
        val sqlcipherKey = toRawKeySyntax(rawKey)
        rawKey.fill(0)
        var newHelper: SupportSQLiteOpenHelper? = null
        try {
            val factory = SupportOpenHelperFactory(sqlcipherKey, null, true)
            val config =
                SupportSQLiteOpenHelper.Configuration.builder(appContext)
                    .name(name)
                    .callback(SchemaCallback(version, migrations))
                    .build()
            newHelper = factory.create(config)
            val database = newHelper.writableDatabase
            helper = newHelper
            return database
        } catch (e: Throwable) {
            newHelper?.close()
            sqlcipherKey.fill(0)
            throw e
        }
    }

    private class SchemaCallback(version: Int, private val migrations: List<Migration>) :
        SupportSQLiteOpenHelper.Callback(version) {
        private val target = version

        override fun onConfigure(db: SupportSQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SupportSQLiteDatabase) {
            Schema.createV1(db)
            Schema.migrate(db, migrations, from = 1, to = target)
        }

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
            Schema.migrate(db, migrations, oldVersion, newVersion)
        }
    }

    companion object {
        const val DATABASE_NAME = "keycorrect.db"

        /** Creates the process-wide database holder. Cheap: nothing is opened yet. */
        fun create(
            context: Context,
            keyProvider: DatabaseKeyProvider = KeystoreDatabaseKeyProvider(context),
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): KeyCorrectDatabase =
            KeyCorrectDatabase(
                context,
                keyProvider,
                dispatcher,
                DATABASE_NAME,
                Schema.VERSION,
                Schema.MIGRATIONS,
            )

        @Volatile private var libraryLoaded = false

        private fun loadNativeLibrary() {
            if (libraryLoaded) return
            synchronized(this) {
                if (!libraryLoaded) {
                    System.loadLibrary("sqlcipher")
                    libraryLoaded = true
                }
            }
        }

        /** `x'<hex>'` of [key], the SQLCipher raw-key syntax. */
        internal fun toRawKeySyntax(key: ByteArray): ByteArray {
            val out = ByteArray(key.size * 2 + 3)
            out[0] = 'x'.code.toByte()
            out[1] = '\''.code.toByte()
            val digits = "0123456789abcdef"
            key.forEachIndexed { i, b ->
                val v = b.toInt() and 0xFF
                out[2 + i * 2] = digits[v ushr 4].code.toByte()
                out[3 + i * 2] = digits[v and 0xF].code.toByte()
            }
            out[out.size - 1] = '\''.code.toByte()
            return out
        }
    }
}
