package dev.keycorrect.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyCorrectDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val id = UUID.randomUUID().toString().take(8)
    private val dbName = "kc_test_$id.db"
    private val keyProvider =
        KeystoreDatabaseKeyProvider(context, "kc_test_key_$id", "kc_test_prefs_$id", dbName)
    private val opened = mutableListOf<KeyCorrectDatabase>()

    private fun open(
        version: Int = Schema.VERSION,
        migrations: List<Migration> = Schema.MIGRATIONS,
        provider: DatabaseKeyProvider = keyProvider,
    ) =
        KeyCorrectDatabase(context, provider, Dispatchers.IO, dbName, version, migrations).also {
            opened += it
        }

    @Before fun setUp() = runBlocking<Unit> { open().destroy() }

    @After
    fun tearDown() =
        runBlocking<Unit> {
            opened.forEach { it.close() }
            opened.first().destroy()
            Unit
        }

    @Test
    fun databaseFileIsNotPlaintext() =
        runBlocking<Unit> {
            val db = open()
            SqlUserDictionaryStore(db).add("sentinelword")
            db.close() // checkpoints WAL into the main file

            val bytes = db.file().readBytes()
            assertThat(bytes.size).isAtLeast(4096)
            val header = String(bytes.copyOf(15), Charsets.ISO_8859_1)
            assertThat(header).isNotEqualTo("SQLite format 3")
            assertThat(String(bytes, Charsets.ISO_8859_1)).doesNotContain("sentinelword")
            assertThat(String(bytes, Charsets.ISO_8859_1)).doesNotContain("user_words")
        }

    @Test
    fun dataSurvivesCloseAndReopenWithSameKey() =
        runBlocking<Unit> {
            SqlUserDictionaryStore(open()).add("persisted", 77)
            opened.last().close()
            val store = SqlUserDictionaryStore(open())
            store.load()
            assertThat(store.snapshot["persisted"]?.frequency).isEqualTo(77)
        }

    @Test
    fun wrongKeyCannotOpen() {
        runBlocking { SqlUserDictionaryStore(open()).add("secret") }
        opened.last().close()
        val other =
            KeystoreDatabaseKeyProvider(context, "kc_test_key_other_$id", "kc_test_prefs_other_$id")
        try {
            assertThrows(Exception::class.java) {
                runBlocking { SqlUserDictionaryStore(open(provider = other)).load() }
            }
        } finally {
            other.reset()
        }
    }

    @Test
    fun walAndForeignKeysAreEnabled() =
        runBlocking<Unit> {
            val db = open()
            val (journal, fk) =
                db.read { d ->
                    val j =
                        d.query("PRAGMA journal_mode").use {
                            it.moveToFirst()
                            it.getString(0)
                        }
                    val f =
                        d.query("PRAGMA foreign_keys").use {
                            it.moveToFirst()
                            it.getInt(0)
                        }
                    j to f
                }
            assertThat(journal.lowercase()).isEqualTo("wal")
            assertThat(fk).isEqualTo(1)
        }

    @Test
    fun transactionRollsBackOnFailure() =
        runBlocking<Unit> {
            val db = open()
            val store = SqlUserDictionaryStore(db)
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    db.transaction(DbTable.USER_WORDS) { d ->
                        d.execSQL(
                            "INSERT INTO user_words (word, frequency, created_at, updated_at) " +
                                "VALUES ('x', 1, 0, 0)"
                        )
                        error("boom")
                    }
                }
            }
            assertThat(store.contains("x")).isFalse()
        }

    @Test
    fun keyProviderIsStableAndRandom() {
        val a = keyProvider.getOrCreatePassphrase()
        val b = keyProvider.getOrCreatePassphrase()
        assertThat(a).hasLength(32)
        assertThat(a).isEqualTo(b)
        assertThat(a.any { it != 0.toByte() }).isTrue()
        keyProvider.reset()
        val c = keyProvider.getOrCreatePassphrase()
        assertThat(c).isNotEqualTo(a)
    }

    @Test
    fun userDictionaryCrudAndFlow() =
        runBlocking<Unit> {
            val store = SqlUserDictionaryStore(open())
            store.load()
            assertThat(store.snapshot.size).isEqualTo(0)

            // Collect incrementally: the list is visible to the test while the flow is still live.
            val seen = java.util.concurrent.CopyOnWriteArrayList<List<String>>()
            val job =
                launch(Dispatchers.Default) {
                    store.all().collect { seen += it.map(UserWord::word) }
                }
            awaitLast(seen, emptyList())

            val added = store.add("  Rishi ", 200, "en-GB")
            assertThat(added.word).isEqualTo("Rishi")
            assertThat(added.frequency).isEqualTo(200)
            assertThat(added.locale).isEqualTo("en-GB")
            assertThat(store.contains("Rishi")).isTrue()
            assertThat(store.snapshot["Rishi"]).isEqualTo(added)

            awaitLast(seen, listOf("Rishi"))

            assertThat(store.update("Rishi", 10, null)).isTrue()
            assertThat(store.snapshot["Rishi"]?.frequency).isEqualTo(10)
            assertThat(store.snapshot["Rishi"]?.locale).isNull()
            assertThat(store.update("nope", 10)).isFalse()

            val upserted = store.add("Rishi", 99)
            assertThat(upserted.createdAt).isEqualTo(added.createdAt)

            assertThat(store.remove("Rishi")).isTrue()
            assertThat(store.remove("Rishi")).isFalse()
            assertThat(store.contains("Rishi")).isFalse()
            assertThat(store.snapshot.size).isEqualTo(0)

            awaitLast(seen, emptyList())
            job.cancel()
            assertThat(seen.first()).isEmpty()
            assertThat(seen).contains(listOf("Rishi"))
        }

    private suspend fun awaitLast(seen: List<List<String>>, expected: List<String>) =
        withTimeout(5_000) {
            while (seen.lastOrNull() != expected) kotlinx.coroutines.delay(20)
        }

    @Test
    fun userDictionaryRejectsInvalid() =
        runBlocking<Unit> {
            val store = SqlUserDictionaryStore(open())
            val e =
                assertThrows(InvalidEntryException::class.java) { runBlocking { store.add("a b") } }
            assertThat(e.error).isEqualTo(ValidationError.WORD_CONTAINS_WHITESPACE)
        }

    @Test
    fun shortcutCrudAndFlow() =
        runBlocking<Unit> {
            val store = SqlShortcutStore(open())
            store.load()

            val stored = store.put("  OMW ", "On my way {cursor}", ShortcutMode.AUTO_EXPAND)
            assertThat(stored.trigger).isEqualTo("omw")
            assertThat(stored.expansion).isEqualTo("On my way {cursor}")
            assertThat(stored.mode).isEqualTo(ShortcutMode.AUTO_EXPAND)
            assertThat(store.get("Omw")).isEqualTo(stored)
            assertThat(store.contains("omw")).isTrue()
            assertThat(store.snapshot["OMW"]).isEqualTo(stored)

            val replaced = store.put("omw", "On my way!", ShortcutMode.SUGGEST)
            assertThat(replaced.createdAt).isEqualTo(stored.createdAt)
            assertThat(replaced.mode).isEqualTo(ShortcutMode.SUGGEST)
            assertThat(store.all().first()).containsExactly(replaced)

            // Flow re-queries on change.
            val next =
                async(Dispatchers.Default) {
                    withTimeout(5_000) { store.all().take(2).toList().last() }
                }
            kotlinx.coroutines.delay(200)
            val sig = store.put("sig", "Best,\n{date}")
            assertThat(next.await().map(Shortcut::trigger)).containsExactly("omw", "sig").inOrder()
            assertThat(sig.expansion).isEqualTo("Best,\n{date}")

            assertThat(store.remove("omw")).isTrue()
            assertThat(store.get("omw")).isNull()
            assertThat(store.snapshot.size).isEqualTo(1)
        }

    @Test
    fun shortcutRejectsInvalid() =
        runBlocking<Unit> {
            val store = SqlShortcutStore(open())
            assertThat(
                    assertThrows(InvalidEntryException::class.java) {
                            runBlocking { store.put("a b", "x") }
                        }
                        .error
                )
                .isEqualTo(ValidationError.TRIGGER_CONTAINS_WHITESPACE)
            assertThat(
                    assertThrows(InvalidEntryException::class.java) {
                            runBlocking { store.put("ok", "x".repeat(1001)) }
                        }
                        .error
                )
                .isEqualTo(ValidationError.EXPANSION_TOO_LONG)
        }

    @Test
    fun migrationFrameworkUpgradesExistingDatabase() =
        runBlocking<Unit> {
            SqlUserDictionaryStore(open(version = 1)).add("kept", 42)
            opened.last().close()

            val addNote =
                Migration(1, 2) { it.execSQL("ALTER TABLE user_words ADD COLUMN note TEXT") }
            val upgraded = open(version = 2, migrations = listOf(addNote))
            val columns = upgraded.read { d -> columnNames(d, "user_words") }
            assertThat(columns).contains("note")
            val store = SqlUserDictionaryStore(upgraded)
            store.load()
            assertThat(store.snapshot["kept"]?.frequency).isEqualTo(42)
            upgraded.close()

            // Reopening at the same version must not rerun the migration (would fail: duplicate
            // column).
            val again = open(version = 2, migrations = listOf(addNote))
            assertThat(again.read { d -> columnNames(d, "user_words") }).contains("note")
        }

    @Test
    fun freshDatabaseRunsMigrationsFromV1() =
        runBlocking<Unit> {
            val addNote =
                Migration(1, 2) { it.execSQL("ALTER TABLE shortcuts ADD COLUMN note TEXT") }
            val db = open(version = 2, migrations = listOf(addNote))
            assertThat(db.read { d -> columnNames(d, "shortcuts") }).contains("note")
        }

    @Test
    fun upgradeFromV3AddsEmojiTablesAndKeepsData() =
        runBlocking<Unit> {
            val upToV3 = Schema.MIGRATIONS.filter { it.to <= 3 }
            val old = open(version = 3, migrations = upToV3)
            SqlUserDictionaryStore(old).add("kept", 42)
            SqlShortcutStore(old).put("omw", "On my way")
            assertThat(old.read { d -> tableNames(d) }).containsNoneIn(Schema.EMOJI_TABLES)
            old.close()

            val upgraded = open()
            assertThat(upgraded.read { d -> tableNames(d) })
                .containsAtLeastElementsIn(Schema.EMOJI_TABLES)
            val words = SqlUserDictionaryStore(upgraded)
            words.load()
            assertThat(words.snapshot["kept"]?.frequency).isEqualTo(42)
            assertThat(SqlShortcutStore(upgraded).get("omw")?.expansion).isEqualTo("On my way")
            val emoji = SqlEmojiHistoryStore(upgraded)
            emoji.recordUse("😀", "😀")
            assertThat(emoji.recents(5)).containsExactly("😀")
        }

    @Test
    fun upgradeFromV4AddsClipsTableAndKeepsData() =
        runBlocking<Unit> {
            val upToV4 = Schema.MIGRATIONS.filter { it.to <= 4 }
            val old = open(version = 4, migrations = upToV4)
            SqlUserDictionaryStore(old).add("kept", 42)
            SqlEmojiHistoryStore(old).recordUse("😀", "😀")
            assertThat(old.read { d -> tableNames(d) }).containsNoneIn(Schema.CLIP_TABLES)
            old.close()

            val upgraded = open()
            assertThat(upgraded.read { d -> tableNames(d) })
                .containsAtLeastElementsIn(Schema.CLIP_TABLES)
            assertThat(upgraded.read { d -> columnNames(d, "clips") })
                .containsAtLeast("kind", "text", "image_file", "pinned", "expires_at", "sensitive")
            val words = SqlUserDictionaryStore(upgraded)
            words.load()
            assertThat(words.snapshot["kept"]?.frequency).isEqualTo(42)
            assertThat(SqlEmojiHistoryStore(upgraded).recents(5)).containsExactly("😀")
            val clips = SqlClipboardStore(upgraded)
            clips.insert(NewClip.text("hello"))
            assertThat(clips.clips().map { it.text }).containsExactly("hello")
        }

    private fun tableNames(db: androidx.sqlite.db.SupportSQLiteDatabase) =
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    private fun columnNames(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String) =
        db.query("PRAGMA table_info($table)").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(1)) }
        }
}
