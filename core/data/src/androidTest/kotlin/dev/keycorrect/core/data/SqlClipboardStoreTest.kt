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
class SqlClipboardStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val id = UUID.randomUUID().toString().take(8)
    private val dbName = "kc_clip_test_$id.db"
    private val keyProvider =
        KeystoreDatabaseKeyProvider(context, "kc_clip_key_$id", "kc_clip_prefs_$id", dbName)
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

    private fun store(db: KeyCorrectDatabase = open()) = SqlClipboardStore(db) { now }

    @Before fun setUp() = runBlocking<Unit> { open().destroy() }

    @After
    fun tearDown() =
        runBlocking<Unit> {
            opened.forEach { it.close() }
            opened.first().destroy()
            Unit
        }

    @Test
    fun clipsAreNewestFirstEvenWithinOneMillisecond() =
        runBlocking<Unit> {
            val s = store()
            s.insert(NewClip.text("a"))
            s.insert(NewClip.text("b"))
            s.insert(NewClip.text("c"))
            assertThat(s.clips().map { it.text }).containsExactly("c", "b", "a").inOrder()
        }

    @Test
    fun allFieldsRoundTrip() =
        runBlocking<Unit> {
            val s = store()
            val t = s.insert(NewClip.text("hello", expiresAt = 5_000, sensitive = true))
            val i = s.insert(NewClip.image("blob-name", "image/png", 1234, pinned = true))
            val byId = s.clips().associateBy { it.id }
            assertThat(byId.getValue(t))
                .isEqualTo(
                    StoredClip(t, ClipKind.Text, "hello", null, null, 0, 1_001, false, 5_000, true)
                )
            assertThat(byId.getValue(i))
                .isEqualTo(
                    StoredClip(
                        i,
                        ClipKind.Image,
                        null,
                        "image/png",
                        "blob-name",
                        1234,
                        1_002,
                        true,
                        null,
                        false,
                    )
                )
        }

    @Test
    fun equalTextAsNewestOnlyBumpsIt() =
        runBlocking<Unit> {
            val s = store()
            val first = s.insert(NewClip.text("a", expiresAt = 5_000))
            now = 2_000
            val again = s.insert(NewClip.text("a", expiresAt = 9_000))
            assertThat(again).isEqualTo(first)
            val only = s.clips().single()
            assertThat(only.createdAt).isEqualTo(2_000)
            assertThat(only.expiresAt).isEqualTo(9_000)

            s.insert(NewClip.text("b"))
            s.insert(NewClip.text("a"))
            assertThat(s.clips().map { it.text }).containsExactly("a", "b", "a").inOrder()
        }

    @Test
    fun pinnedClipKeepsItsExpiryWhenBumped() =
        runBlocking<Unit> {
            val s = store()
            s.insert(NewClip.text("a", expiresAt = 5_000, pinned = true))
            now = 2_000
            s.insert(NewClip.text("a", expiresAt = 9_000))
            assertThat(s.clips().single().expiresAt).isEqualTo(5_000)
        }

    @Test
    fun pinDeleteClearAndUpdateText() =
        runBlocking<Unit> {
            val s = store()
            val a = s.insert(NewClip.text("a"))
            val b = s.insert(NewClip.text("https://x.test/?utm_source=a"))
            val c = s.insert(NewClip.image("f", "image/png", 3))
            s.insert(NewClip.text("d"))
            s.setPinned(a, true)
            s.updateText(b, "https://x.test/")
            s.updateText(c, "ignored for images")
            val byId = s.clips().associateBy { it.id }
            assertThat(byId.getValue(a).pinned).isTrue()
            assertThat(byId.getValue(b).text).isEqualTo("https://x.test/")
            assertThat(byId.getValue(c).text).isNull()

            s.delete(listOf(b, c, 9_999L))
            assertThat(s.clips().map { it.text }).containsExactly("d", "a").inOrder()
            s.delete(emptyList())

            s.clear(keepPinned = true)
            assertThat(s.clips().map { it.id }).containsExactly(a)
            s.clear(keepPinned = false)
            assertThat(s.clips()).isEmpty()
        }

    @Test
    fun deleteHandlesMoreIdsThanOneStatementHolds() =
        runBlocking<Unit> {
            val s = store()
            val ids = (1..1_200).map { s.insert(NewClip.text("t$it")) }
            s.delete(ids.drop(1))
            assertThat(s.clips().map { it.id }).containsExactly(ids.first())
        }

    @Test
    fun clearSinceRemovesNewerClipsIncludingPinned() =
        runBlocking<Unit> {
            val s = store()
            now = 1_000
            s.insert(NewClip.text("old"))
            now = 5_000
            s.insert(NewClip.text("new", pinned = true))
            s.insert(NewClip.text("newer"))
            s.clearSince(3_000)
            assertThat(s.clips().map { it.text }).containsExactly("old")
        }

    @Test
    fun clipsLiveOnlyInTheEncryptedFile() =
        runBlocking<Unit> {
            val db = open()
            store(db).insert(NewClip.text("sentinelclip"))
            db.close()
            val bytes = String(db.file().readBytes(), Charsets.ISO_8859_1)
            assertThat(bytes).doesNotContain("sentinelclip")
        }
}
