package dev.keycorrect.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.learning.model.NgramRow
import dev.keycorrect.core.learning.model.PersistedLearning
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The backup restore writes everything in ONE transaction: a failure leaves the database as it was.
 */
@RunWith(AndroidJUnit4::class)
class SqlPersonalDataWriterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val id = UUID.randomUUID().toString().take(8)
    private val dbName = "kc_writer_test_$id.db"
    private val keyProvider =
        KeystoreDatabaseKeyProvider(context, "kc_writer_key_$id", "kc_writer_prefs_$id", dbName)
    private val opened = mutableListOf<KeyCorrectDatabase>()

    private fun open() =
        KeyCorrectDatabase(
                context,
                keyProvider,
                Dispatchers.IO,
                dbName,
                Schema.VERSION,
                Schema.MIGRATIONS,
            )
            .also { opened += it }

    @Before fun setUp() = runBlocking<Unit> { open().destroy() }

    @After
    fun tearDown() =
        runBlocking<Unit> {
            opened.forEach { it.close() }
            opened.first().destroy()
            Unit
        }

    private fun word(w: String) = UserWord(w, 100, null, 1, 2)

    private fun shortcut(t: String) = Shortcut(t, "expansion of $t", ShortcutMode.SUGGEST, 1, 2)

    private fun learning(weight: Double) =
        PersistedLearning(activeClockMillis = 5, ngrams = listOf(NgramRow(1, "w", weight, 3)))

    @Test
    fun aFailureMidWriteLeavesEveryTableUnchanged() =
        runBlocking<Unit> {
            val db = open()
            val words = SqlUserDictionaryStore(db)
            val shortcuts = SqlShortcutStore(db)
            val learningStore = SqlLearningStore(db)
            val writer = SqlPersonalDataWriter(db, learningStore)
            val before =
                PersonalData(
                    listOf(word("old")),
                    listOf(shortcut("omw")),
                    EmojiHistoryDump(listOf(EmojiRecentRow("😀", "😀", 1, 1))),
                    learning(1.0),
                    emptyList(),
                )
            writer.replaceAll(before)

            // The user words and the shortcut insert fine; the second shortcut repeats a primary
            // key, so the transaction fails after the dictionary and the first shortcut were
            // already rewritten.
            val broken =
                PersonalData(
                    listOf(word("new1"), word("new2")),
                    listOf(shortcut("brb"), shortcut("brb")),
                    EmojiHistoryDump(listOf(EmojiRecentRow("🎉", "🎉", 9, 9))),
                    learning(99.0),
                    emptyList(),
                )
            val failure = runCatching { writer.replaceAll(broken) }.exceptionOrNull()

            assertThat(failure).isNotNull()
            assertThat(words.all().first().map { it.word }).containsExactly("old")
            assertThat(shortcuts.all().first().map { it.trigger }).containsExactly("omw")
            assertThat(SqlEmojiHistoryStore(db) { 0L }.dump().recents.map { it.emoji })
                .containsExactly("😀")
            assertThat(learningStore.load()!!.ngrams.single().weight).isEqualTo(1.0)

            // And the writer still works afterwards (the lock was released).
            writer.replaceAll(
                broken.let {
                    PersonalData(it.words, emptyList(), it.emoji, it.learning, emptyList())
                }
            )
            assertThat(words.all().first().map { it.word }).containsExactly("new1", "new2")
        }
}
