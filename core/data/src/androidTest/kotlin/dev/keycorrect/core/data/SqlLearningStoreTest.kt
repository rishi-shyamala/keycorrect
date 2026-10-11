package dev.keycorrect.core.data

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.learning.LearningUpdate
import dev.keycorrect.core.learning.TouchSample
import dev.keycorrect.core.learning.model.BlockRow
import dev.keycorrect.core.learning.model.CandidateSessionRow
import dev.keycorrect.core.learning.model.CandidateWordRow
import dev.keycorrect.core.learning.model.CasingRow
import dev.keycorrect.core.learning.model.CorrectionRow
import dev.keycorrect.core.learning.model.JournalEntry
import dev.keycorrect.core.learning.model.NgramRow
import dev.keycorrect.core.learning.model.OutcomeRow
import dev.keycorrect.core.learning.model.PendingAddRow
import dev.keycorrect.core.learning.model.PersistedLearning
import dev.keycorrect.core.learning.model.RankerWeightRow
import dev.keycorrect.core.learning.model.SentenceRow
import dev.keycorrect.core.learning.model.SuppressionRow
import dev.keycorrect.core.learning.model.TouchProfileRow
import dev.keycorrect.core.learning.model.TouchRow
import dev.keycorrect.core.learning.model.UserWordRow
import java.util.UUID
import kotlin.random.Random
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqlLearningStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val id = UUID.randomUUID().toString().take(8)
    private val dbName = "kc_learn_test_$id.db"
    private val keyProvider =
        KeystoreDatabaseKeyProvider(context, "kc_learn_key_$id", "kc_learn_prefs_$id", dbName)
    private val opened = mutableListOf<KeyCorrectDatabase>()

    private fun open(
        version: Int = Schema.VERSION,
        migrations: List<Migration> = Schema.MIGRATIONS,
    ) =
        KeyCorrectDatabase(context, keyProvider, Dispatchers.IO, dbName, version, migrations).also {
            opened += it
        }

    @Before fun setUp() = runBlocking { open().destroy() }

    @After
    fun tearDown() = runBlocking {
        opened.forEach { it.close() }
        opened.first().destroy()
        Unit
    }

    private fun small() =
        PersistedLearning(
            activeClockMillis = 12_345,
            lastWallMillis = 99,
            rankerUpdates = 7,
            ngrams = listOf(NgramRow(1, "hello", 2.5, 10), NgramRow(2, "hello world", 0.125, 11)),
            sentences = listOf(SentenceRow(3, "secret sentence zebra", 1000)),
            userWords = listOf(UserWordRow("rishi", "Rishi", 1, 5, false)),
        )

    @Test
    fun loadIsNullBeforeFirstSave() = runBlocking {
        assertThat(SqlLearningStore(open()).load()).isNull()
    }

    @Test
    fun defaultStateRoundTripsIncludingSentinels() = runBlocking {
        val store = SqlLearningStore(open())
        val empty = PersistedLearning()
        store.save(empty)
        assertThat(store.load()).isEqualTo(empty)
    }

    @Test
    fun saveReplacesPreviousState() = runBlocking {
        val store = SqlLearningStore(open())
        store.save(small())
        val second =
            small().copy(ngrams = listOf(NgramRow(1, "other", 1.0, 1)), sentences = emptyList())
        store.save(second)
        assertThat(store.load()).isEqualTo(second)
    }

    @Test
    fun largeStateRoundTripsAndIsFast() = runBlocking {
        val db = open()
        val store = SqlLearningStore(db)
        val state = largeState()
        val rows =
            state.ngrams.size +
                state.corrections.size +
                state.touch.size +
                state.sentences.size +
                state.rankerWeights.size

        val saveMs = measureTimeMillis { store.save(state) }
        var loaded: PersistedLearning? = null
        val loadMs = measureTimeMillis { loaded = store.load() }
        // Second save over existing rows (DELETE + INSERT path).
        val resaveMs = measureTimeMillis { store.save(state) }
        Log.i(
            "KeyCorrectPerf",
            "learning rows=$rows save=${saveMs}ms resave=${resaveMs}ms load=${loadMs}ms",
        )
        println("KeyCorrectPerf rows=$rows save=${saveMs}ms resave=${resaveMs}ms load=${loadMs}ms")

        assertThat(loaded).isEqualTo(state)

        // Survives close/reopen.
        db.close()
        assertThat(SqlLearningStore(open()).load()).isEqualTo(state)
        // Generous bound; real numbers are logged for the report.
        assertThat(saveMs).isLessThan(30_000)
    }

    @Test
    fun sentencesPersistAreEncryptedAndClearedByClear() = runBlocking {
        val db = open()
        val store = SqlLearningStore(db)
        store.save(small())
        assertThat(store.load()!!.sentences.single().text).isEqualTo("secret sentence zebra")
        db.close()
        val bytes = String(db.file().readBytes(), Charsets.ISO_8859_1)
        assertThat(bytes).doesNotContain("secret sentence zebra")
        assertThat(bytes).doesNotContain("learning_sentences")
        val header = bytes.substring(0, 15)
        assertThat(header).isNotEqualTo("SQLite format 3")

        val store2 = SqlLearningStore(open())
        store2.clear()
        assertThat(store2.load()).isNull()
        val rowCount =
            opened.last().read { d ->
                d.query("SELECT COUNT(*) FROM learning_sentences").use {
                    it.moveToFirst()
                    it.getInt(0)
                }
            }
        assertThat(rowCount).isEqualTo(0)
    }

    @Test
    fun journalAppendSinceTruncate() = runBlocking {
        val store = SqlLearningStore(open())
        val updates =
            listOf(
                JournalEntry(100, LearningUpdate.BlockWord("foo")),
                JournalEntry(200, LearningUpdate.WordUsed(listOf("a", "b%"), "c\u001Fd", 1.5f)),
                JournalEntry(200, LearningUpdate.AutocorrectOutcome(true)),
                JournalEntry(
                    300,
                    LearningUpdate.TouchEvidence("k", listOf(TouchSample('q', 0.25f, -0.5f))),
                ),
            )
        store.appendJournal(updates)
        store.appendJournal(emptyList())
        assertThat(store.journalSince(Long.MIN_VALUE)).isEqualTo(updates)
        assertThat(store.journalSince(200)).isEqualTo(updates.drop(1))
        assertThat(store.journalSince(301)).isEmpty()

        store.truncateJournalBefore(200)
        assertThat(store.journalSince(Long.MIN_VALUE)).isEqualTo(updates.drop(1))

        // save() keeps the journal; clear() wipes it.
        store.save(small())
        assertThat(store.journalSince(Long.MIN_VALUE)).hasSize(3)
        store.truncateJournalBefore(Long.MAX_VALUE)
        assertThat(store.journalSince(Long.MIN_VALUE)).isEmpty()
        store.appendJournal(updates)
        store.clear()
        assertThat(store.journalSince(Long.MIN_VALUE)).isEmpty()
    }

    @Test
    fun commitSavesAppendsAndTrimsInOneTransaction() = runBlocking {
        val store = SqlLearningStore(open())
        val old = JournalEntry(100, LearningUpdate.BlockWord("old"))
        val fresh = JournalEntry(500, LearningUpdate.BlockWord("fresh"))
        store.appendJournal(listOf(old))
        store.commit(small(), listOf(fresh), truncateBefore = 200)
        assertThat(store.load()).isEqualTo(small())
        assertThat(store.journalSince(Long.MIN_VALUE)).containsExactly(fresh)
        Unit
    }

    @Test
    fun aFailingCommitLeavesStateAndJournalUntouched() = runBlocking {
        val store = SqlLearningStore(open())
        val kept = JournalEntry(100, LearningUpdate.BlockWord("kept"))
        store.save(small())
        store.appendJournal(listOf(kept))
        // NaN binds as NULL and violates NOT NULL: the transaction must roll back as a whole.
        val bad = small().copy(ngrams = listOf(NgramRow(1, "x", Double.NaN, 1)))
        val fresh = JournalEntry(500, LearningUpdate.BlockWord("fresh"))
        val failedCommit = runCatching { store.commit(bad, listOf(fresh), Long.MAX_VALUE) }
        assertThat(failedCommit.isFailure).isTrue()
        val failedReplace = runCatching { store.replaceAll(bad, listOf(fresh)) }
        assertThat(failedReplace.isFailure).isTrue()
        assertThat(store.load()).isEqualTo(small())
        assertThat(store.journalSince(Long.MIN_VALUE)).containsExactly(kept)
        Unit
    }

    @Test
    fun replaceAllReplacesStateAndTheWholeJournal() = runBlocking {
        val store = SqlLearningStore(open())
        store.save(small())
        store.appendJournal(
            listOf(
                JournalEntry(100, LearningUpdate.BlockWord("a")),
                JournalEntry(200, LearningUpdate.BlockWord("b")),
            )
        )
        val remaining = listOf(JournalEntry(100, LearningUpdate.BlockWord("a")))
        val next = small().copy(sentences = emptyList())
        store.replaceAll(next, remaining)
        assertThat(store.load()).isEqualTo(next)
        assertThat(store.journalSince(Long.MIN_VALUE)).isEqualTo(remaining)
        store.replaceAll(next, emptyList())
        assertThat(store.journalSince(Long.MIN_VALUE)).isEmpty()
    }

    @Test
    fun userWordFlagsRoundTrip() = runBlocking {
        val store = SqlLearningStore(open())
        val words =
            listOf(
                UserWordRow("a", "a", 0, 1, removed = false, preExisting = false),
                UserWordRow("b", "b", 1, 2, removed = true, preExisting = false),
                UserWordRow("c", "c", 2, 3, removed = false, preExisting = true),
                UserWordRow("d", "d", 0, 4, removed = true, preExisting = true),
            )
        store.save(small().copy(userWords = words))
        assertThat(store.load()!!.userWords).isEqualTo(words)
    }

    @Test
    fun clearLeavesUserDictionaryAndShortcutsIntact() = runBlocking {
        val db = open()
        val words = SqlUserDictionaryStore(db)
        val shortcuts = SqlShortcutStore(db)
        words.add("keepme", 55)
        shortcuts.put("omw", "On my way")
        val store = SqlLearningStore(db)
        store.save(small())
        store.appendJournal(listOf(JournalEntry(1, LearningUpdate.BlockWord("x"))))

        store.clear()

        assertThat(store.load()).isNull()
        assertThat(store.journalSince(Long.MIN_VALUE)).isEmpty()
        assertThat(words.contains("keepme")).isTrue()
        words.load()
        assertThat(words.snapshot["keepme"]?.frequency).isEqualTo(55)
        assertThat(shortcuts.get("omw")?.expansion).isEqualTo("On my way")
    }

    @Test
    fun migrationV1ToV2KeepsUserWordsAndShortcuts() = runBlocking {
        val v1 = open(version = 1, migrations = emptyList())
        SqlUserDictionaryStore(v1).add("legacy", 42)
        SqlShortcutStore(v1).put("sig", "Best,\n{date}")
        assertThat(v1.read { d -> tableNames(d) }).doesNotContain("learning_ngrams")
        v1.close()

        val v2 = open()
        assertThat(v2.read { d -> tableNames(d) })
            .containsAtLeastElementsIn(Schema.LEARNING_V2_TABLES)
        val words = SqlUserDictionaryStore(v2)
        words.load()
        assertThat(words.snapshot["legacy"]?.frequency).isEqualTo(42)
        assertThat(SqlShortcutStore(v2).get("sig")?.expansion).isEqualTo("Best,\n{date}")

        val store = SqlLearningStore(v2)
        assertThat(store.load()).isNull()
        store.save(small())
        assertThat(store.load()).isEqualTo(small())
        assertThat(words.contains("legacy")).isTrue()
    }

    @Test
    fun freshDatabaseHasLearningTablesAndJournalIndex() = runBlocking {
        val db = open()
        val (tables, indexes) =
            db.read { d ->
                tableNames(d) to
                    d.query("SELECT name FROM sqlite_master WHERE type = 'index'").use { c ->
                        buildList { while (c.moveToNext()) add(c.getString(0)) }
                    }
            }
        assertThat(tables).containsAtLeastElementsIn(Schema.LEARNING_V2_TABLES)
        assertThat(indexes).contains("learning_journal_time")
    }

    private fun tableNames(db: androidx.sqlite.db.SupportSQLiteDatabase) =
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    private fun largeState(): PersistedLearning {
        val rnd = Random(42)
        val syllables = listOf("th", "an", "er", "ing", "ou", "re", "st", "en", "al", "to", "me")

        fun word(): String = buildString {
            repeat(2 + rnd.nextInt(3)) { append(syllables[rnd.nextInt(syllables.size)]) }
        }

        fun w(n: Int) = List(n) { word() }

        val ngrams =
            List(20_000) {
                NgramRow(1, "${word()}$it", rnd.nextDouble() * 50, rnd.nextLong(1_000_000))
            } +
                List(50_000) {
                    NgramRow(
                        2,
                        "${word()} ${word()}$it",
                        rnd.nextDouble() * 10,
                        rnd.nextLong(1_000_000),
                    )
                } +
                List(50_000) {
                    NgramRow(
                        3,
                        w(3).joinToString(" ") + it,
                        rnd.nextDouble() * 3,
                        rnd.nextLong(1_000_000),
                    )
                }
        return PersistedLearning(
            activeClockMillis = 987_654_321,
            lastWallMillis = 1_700_000_000_000,
            rankerUpdates = 4242,
            lastCheckpointMillis = 1_699_999_999_000,
            ngrams = ngrams,
            corrections =
                List(20_000) {
                    CorrectionRow(
                        word() + it,
                        word(),
                        if (it % 3 == 0) "" else word(),
                        rnd.nextDouble() * 5 - 1,
                        rnd.nextLong(1_000_000),
                    )
                },
            suppressions = List(50) { SuppressionRow(word() + it, word(), it.toLong()) },
            blocks = List(30) { BlockRow(word() + it, it.toLong()) },
            userWords =
                List(500) { UserWordRow(word() + it, "W$it", it % 3, it.toLong(), it % 7 == 0) },
            candidates =
                List(2_000) {
                    CandidateWordRow("k$it", "Cand$it", rnd.nextDouble(), it.toLong(), it + 5L)
                },
            candidateSessions =
                List(4_000) { CandidateSessionRow("k${it / 2}", it.toLong(), rnd.nextDouble()) },
            casing = List(1_000) { CasingRow("c$it", "Cased$it", rnd.nextDouble() * 4) },
            touch =
                List(300) {
                    TouchRow(
                        "kb$it",
                        'a'.code + it % 26,
                        rnd.nextInt(5000),
                        rnd.nextDouble() * 100,
                        rnd.nextDouble(),
                        rnd.nextDouble(),
                        rnd.nextDouble(),
                        rnd.nextDouble(),
                        rnd.nextLong(1_000_000),
                    )
                },
            touchProfiles = List(10) { TouchProfileRow("kb$it", it * 100) },
            rankerWeights =
                List(64) { RankerWeightRow(-1, it, rnd.nextDouble() - 0.5) } +
                    List(64) { RankerWeightRow(1_699_000_000_000, it, rnd.nextDouble() - 0.5) },
            outcomes = List(500) { OutcomeRow(it % 2, it.toLong(), it % 2, (it + 1) % 3) },
            sentences =
                List(300) {
                    SentenceRow(
                        it / 10L,
                        "Sentence number $it with \"quotes\", unicode é中😀 and 'apostrophes'",
                        it * 1000L,
                    )
                },
            pendingAdds = List(5) { PendingAddRow(word() + it, it % 2) },
        )
    }
}
