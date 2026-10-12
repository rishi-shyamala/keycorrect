package dev.keycorrect.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqlEmojiHistoryStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val id = UUID.randomUUID().toString().take(8)
    private val dbName = "kc_emoji_test_$id.db"
    private val keyProvider =
        KeystoreDatabaseKeyProvider(context, "kc_emoji_key_$id", "kc_emoji_prefs_$id", dbName)
    private val opened = mutableListOf<KeyCorrectDatabase>()
    private var now = 1_000L

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

    private fun store(db: KeyCorrectDatabase = open()) = SqlEmojiHistoryStore(db) { now }

    @Before fun setUp() = runBlocking<Unit> { open().destroy() }

    @After
    fun tearDown() =
        runBlocking<Unit> {
            opened.forEach { it.close() }
            opened.first().destroy()
            Unit
        }

    @Test
    fun allVariantChoicesAreReadInOneQuery() =
        runBlocking<Unit> {
            val s = store()
            s.setPreferredVariant("👍", "👍🏽")
            s.setPreferredVariant("👋", "👋🏿")
            assertThat(s.preferredVariants()).containsExactly("👍", "👍🏽", "👋", "👋🏿")
        }

    @Test
    fun recentsAreMostRecentFirst() =
        runBlocking<Unit> {
            val s = store()
            s.recordUse("😀", "😀")
            s.recordUse("👍🏽", "👍") // same millisecond on purpose
            s.recordUse("🎉", "🎉")
            assertThat(s.recents(10)).containsExactly("🎉", "👍🏽", "😀").inOrder()
            s.recordUse("😀", "😀")
            assertThat(s.recents(2)).containsExactly("😀", "🎉").inOrder()
            assertThat(s.recents(0)).isEmpty()
        }

    @Test
    fun useCountAndBaseAreStored() =
        runBlocking<Unit> {
            val db = open()
            val s = store(db)
            s.recordUse("👍🏽", "👍")
            s.recordUse("👍🏽", "👍")
            val row = db.read { d ->
                d.query(
                        "SELECT base, use_count FROM emoji_recents WHERE emoji = ?",
                        arrayOf<Any?>("👍🏽"),
                    )
                    .use {
                        it.moveToFirst()
                        it.getString(0) to it.getInt(1)
                    }
            }
            assertThat(row).isEqualTo("👍" to 2)
        }

    @Test
    fun recentsArePrunedToSixtyFour() =
        runBlocking<Unit> {
            val s = store()
            for (i in 0 until 100) {
                now += 10
                s.recordUse("e$i", "e$i")
            }
            val all = s.recents(1_000)
            assertThat(all).hasSize(EmojiHistoryStore.MAX_RECENTS)
            assertThat(all.first()).isEqualTo("e99")
            assertThat(all.last()).isEqualTo("e36")
        }

    @Test
    fun variantChoiceSetReplaceClear() =
        runBlocking<Unit> {
            val s = store()
            assertThat(s.preferredVariant("👍")).isNull()
            s.setPreferredVariant("👍", "👍🏽")
            assertThat(s.preferredVariant("👍")).isEqualTo("👍🏽")
            s.setPreferredVariant("👍", "👍🏿")
            assertThat(s.preferredVariant("👍")).isEqualTo("👍🏿")
            s.setPreferredVariant("👍", null)
            assertThat(s.preferredVariant("👍")).isNull()
        }

    @Test
    fun invalidValuesAreIgnored() =
        runBlocking<Unit> {
            val s = store()
            s.recordUse("", "x")
            s.recordUse("x", " ")
            s.recordUse("y".repeat(EmojiHistoryStore.MAX_VALUE_LENGTH + 1), "y")
            assertThat(s.recents(10)).isEmpty()
        }

    @Test
    fun clearSinceAndClear() =
        runBlocking<Unit> {
            val s = store()
            now = 1_000
            s.recordUse("old", "old")
            s.setPreferredVariant("a", "a1")
            now = 5_000
            s.recordUse("new", "new")
            s.setPreferredVariant("b", "b1")
            s.clearSince(3_000)
            assertThat(s.recents(10)).containsExactly("old")
            assertThat(s.preferredVariant("a")).isEqualTo("a1")
            assertThat(s.preferredVariant("b")).isNull()

            s.clear()
            assertThat(s.recents(10)).isEmpty()
            assertThat(s.preferredVariant("a")).isNull()
        }

    @Test
    fun dataSurvivesReopenAndIsNotPlaintext() =
        runBlocking<Unit> {
            val db = open()
            store(db).recordUse("🦄", "🦄")
            store(db).setPreferredVariant("👍", "👍🏽")
            db.close()
            val again = store()
            assertThat(again.recents(5)).containsExactly("🦄")
            assertThat(again.preferredVariant("👍")).isEqualTo("👍🏽")
            opened.last().close()
            val text = String(opened.last().file().readBytes(), Charsets.ISO_8859_1)
            assertThat(text).doesNotContain("emoji_recents")
        }

    @Test
    fun learningStoreClearLeavesEmojiHistoryAlone() =
        runBlocking<Unit> {
            val db = open()
            store(db).recordUse("🦄", "🦄")
            SqlLearningStore(db).clear()
            assertThat(store(db).recents(5)).containsExactly("🦄")
        }
}
