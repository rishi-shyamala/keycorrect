package dev.keycorrect.ime

import androidx.compose.ui.graphics.ImageBitmap
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.data.AesGcmBlobCipher
import dev.keycorrect.core.data.ClipboardRepository
import dev.keycorrect.core.data.ClipboardStore
import dev.keycorrect.core.data.EncryptedBlobStore
import dev.keycorrect.core.data.InMemoryClipboardStore
import dev.keycorrect.core.settings.ClipRetention
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.feature.keyboard.clipboard.ClipItem
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AppClipboardEnvironmentTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeTimer : DelayedRunner {
        class Task(val delay: Long, val action: Runnable) {
            var cancelled = false
        }

        val tasks = mutableListOf<Task>()

        override fun post(delayMillis: Long, action: Runnable): () -> Unit {
            val task = Task(delayMillis, action)
            tasks += task
            return { task.cancelled = true }
        }

        fun runDue(delay: Long) {
            tasks.filter { !it.cancelled && it.delay == delay }.forEach { it.action.run() }
        }
    }

    private var now = 1_000_000L
    private val store = InMemoryClipboardStore { now }
    private val timer = FakeTimer()
    private val imageBytes = ByteArray(2_000) { (it * 7).toByte() }

    private class Rig(
        val env: AppClipboardEnvironment,
        val repo: ClipboardRepository,
        val blobs: EncryptedBlobStore,
    )

    /** While true the environment cannot get its repository (a lost key, say). */
    private var broken = false

    private fun TestScope.rig(
        settings: KeyboardSettings = KeyboardSettings(),
        rows: ClipboardStore = store,
        decode: suspend (ImageSource) -> ImageBitmap? = { null },
        thumbnailDispatcher: CoroutineDispatcher? = null,
        captureDispatcher: CoroutineDispatcher? = null,
        captureTimeout: Long = AppClipboardEnvironment.CAPTURE_TIMEOUT_MILLIS,
    ): Rig {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val blobs = EncryptedBlobStore(File(tmp.root, "clips"), AesGcmBlobCipher { key })
        val repo = ClipboardRepository(rows, blobs, dispatcher)
        val env =
            AppClipboardEnvironment(
                backgroundScope,
                { if (broken) throw IOException("key lost") else repo },
                clock = { now },
                timer = timer,
                decodeThumbnail = decode,
                dispatcher = dispatcher,
                thumbnailDispatcher = thumbnailDispatcher ?: dispatcher,
                captureDispatcher = captureDispatcher ?: dispatcher,
                captureTimeoutMillis = captureTimeout,
            )
        env.applySettings(settings)
        env.start()
        return Rig(env, repo, blobs)
    }

    private fun AppClipboardEnvironment.copy(text: String) {
        now += 10
        captureText(text)
    }

    private val AppClipboardEnvironment.previews: List<String>
        get() = clips.map { it.preview }

    @Test
    fun capturedTextAppearsNewestFirst() = runTest {
        val env = rig().env
        env.copy("first")
        env.copy("second")
        assertThat(env.previews).containsExactly("second", "first").inOrder()
        assertThat(env.clips.first().kind).isEqualTo(ClipItem.Kind.Text)
    }

    @Test
    fun theSameTextTwiceIsOneClip() = runTest {
        val rig = rig()
        rig.env.copy("same")
        rig.env.copy("same")
        assertThat(rig.env.previews).containsExactly("same")
        assertThat(rig.repo.clips()).hasSize(1)
    }

    @Test
    fun blankAndHugeTextIsIgnored() = runTest {
        val env = rig().env
        env.copy("   \n ")
        env.copy("x".repeat(AppClipboardEnvironment.MAX_TEXT_CHARS + 1))
        assertThat(env.clips).isEmpty()
        env.copy("x".repeat(AppClipboardEnvironment.MAX_TEXT_CHARS))
        assertThat(env.clips).hasSize(1)
    }

    @Test
    fun everythingIsCapturedAsIs() = runTest {
        val env = rig().env
        env.copy("hunter2-Password!")
        env.copy("4111 1111 1111 1111")
        assertThat(env.previews)
            .containsExactly("4111 1111 1111 1111", "hunter2-Password!")
            .inOrder()
    }

    @Test
    fun startLoadsTheSavedHistory() = runTest {
        store.insert(dev.keycorrect.core.data.NewClip.text("saved"))
        val env = rig().env
        assertThat(env.previews).containsExactly("saved")
    }

    @Test
    fun pinnedClipsComeFirstAndSurviveClearUnpinned() = runTest {
        val rig = rig()
        val env = rig.env
        env.copy("a")
        env.copy("b")
        env.copy("c")
        val a = env.clips.single { it.preview == "a" }
        env.setPinned(a.id, true)
        assertThat(env.previews).containsExactly("a", "c", "b").inOrder()
        assertThat(rig.repo.clips().single { it.id == a.id }.pinned).isTrue()

        env.clearUnpinned()
        assertThat(env.previews).containsExactly("a")
        assertThat(rig.repo.clips()).hasSize(1)

        env.clearAll()
        assertThat(env.clips).isEmpty()
        assertThat(rig.repo.clips()).isEmpty()
    }

    @Test
    fun deleteRemovesTheClipEverywhere() = runTest {
        val rig = rig()
        rig.env.copy("keep")
        rig.env.copy("drop")
        rig.env.delete(rig.env.clips.single { it.preview == "drop" }.id)
        assertThat(rig.env.previews).containsExactly("keep")
        assertThat(rig.repo.clips().map { it.text }).containsExactly("keep")
    }

    @Test
    fun cleanUrlRemovesTrackingParameters() = runTest {
        val rig = rig()
        rig.env.copy("https://example.com/page?utm_source=x&id=7")
        val clip = rig.env.clips.single()
        assertThat(clip.cleanableUrl).isTrue()
        rig.env.cleanUrl(clip.id)
        assertThat(rig.env.previews).containsExactly("https://example.com/page?id=7")
        assertThat(rig.env.clips.single().cleanableUrl).isFalse()
        assertThat(rig.repo.clips().single().text).isEqualTo("https://example.com/page?id=7")
    }

    @Test
    fun freshClipIsTheNewCopyForSixtySeconds() = runTest {
        val env = rig().env
        assertThat(env.freshClip()).isNull()
        env.copy("hello")
        assertThat(env.freshClip()?.preview).isEqualTo("hello")

        // The scheduled callback clears it, so the chip goes away without any other event.
        timer.runDue(AppClipboardEnvironment.FRESH_MILLIS)
        assertThat(env.freshClip()).isNull()
        // The clip itself stays.
        assertThat(env.clips).hasSize(1)
    }

    @Test
    fun freshClipExpiresByTheClockToo() = runTest {
        val env = rig().env
        env.copy("hello")
        now += AppClipboardEnvironment.FRESH_MILLIS - 1
        assertThat(env.freshClip()).isNotNull()
        now += 1
        assertThat(env.freshClip()).isNull()
    }

    @Test
    fun aNewCopyRestartsFreshnessAndCancelsTheOldTimer() = runTest {
        val env = rig().env
        env.copy("one")
        val firstTimer = timer.tasks.last()
        env.copy("two")
        assertThat(firstTimer.cancelled).isTrue()
        assertThat(env.freshClip()?.preview).isEqualTo("two")
    }

    @Test
    fun deletingTheFreshClipHidesIt() = runTest {
        val env = rig().env
        env.copy("hello")
        env.delete(env.freshClip()!!.id)
        assertThat(env.freshClip()).isNull()
    }

    @Test
    fun historyIsPrunedToTheItemLimit() = runTest {
        val rig = rig(KeyboardSettings(clipboardMaxItems = 5))
        repeat(8) { rig.env.copy("clip $it") }
        assertThat(rig.env.clips).hasSize(5)
        assertThat(rig.repo.clips()).hasSize(5)
        assertThat(rig.env.clips.first().preview).isEqualTo("clip 7")
    }

    @Test
    fun loweringTheLimitPrunesAtOnce() = runTest {
        val rig = rig()
        repeat(8) { rig.env.copy("clip $it") }
        rig.env.applySettings(KeyboardSettings(clipboardMaxItems = 5))
        assertThat(rig.env.clips).hasSize(5)
    }

    @Test
    fun oldUnpinnedClipsExpireButPinnedOnesStay() = runTest {
        val rig = rig(KeyboardSettings(clipboardRetention = ClipRetention.Hour))
        val env = rig.env
        env.copy("old pinned")
        env.copy("old")
        env.setPinned(env.clips.single { it.preview == "old pinned" }.id, true)
        now += ClipRetention.Hour.millis + 1
        env.copy("new")
        assertThat(env.previews).containsExactly("old pinned", "new").inOrder()
        // Also at the next keyboard show, with nothing new copied.
        now += ClipRetention.Hour.millis + 1
        env.start()
        assertThat(env.previews).containsExactly("old pinned")
    }

    @Test
    fun nothingIsCapturedWhenHistoryIsOffOrIncognito() = runTest {
        val rig = rig(KeyboardSettings(clipboardHistory = false))
        assertThat(rig.env.enabled).isFalse()
        rig.env.copy("off")
        rig.env.captureImage("image/png") { ByteArrayInputStream(imageBytes) }

        rig.env.applySettings(KeyboardSettings(incognito = true))
        assertThat(rig.env.enabled).isFalse()
        rig.env.copy("incognito")
        rig.env.captureImage("image/png") { ByteArrayInputStream(imageBytes) }

        assertThat(rig.env.clips).isEmpty()
        assertThat(rig.repo.clips()).isEmpty()

        rig.env.applySettings(KeyboardSettings())
        assertThat(rig.env.enabled).isTrue()
        rig.env.copy("on")
        assertThat(rig.env.previews).containsExactly("on")
    }

    @Test
    fun imagesAreCapturedEncryptedAndOnlyWhenAllowed() = runTest {
        val rig = rig(KeyboardSettings(clipboardImages = false))
        rig.env.captureImage("image/png") { ByteArrayInputStream(imageBytes) }
        assertThat(rig.env.clips).isEmpty()

        rig.env.applySettings(KeyboardSettings(clipboardImages = true))
        rig.env.captureImage("image/png") { ByteArrayInputStream(imageBytes) }
        val clip = rig.env.clips.single()
        assertThat(clip.kind).isEqualTo(ClipItem.Kind.Image)
        val stored = rig.repo.clips().single()
        assertThat(rig.repo.openImage(stored).use { it.readBytes() }).isEqualTo(imageBytes)
        val raw = File(tmp.root, "clips/${stored.imageFile}").readBytes()
        assertThat(String(raw, Charsets.ISO_8859_1))
            .doesNotContain(String(imageBytes.copyOf(64), Charsets.ISO_8859_1))
    }

    @Test
    fun anImageOverTheBudgetIsNotKept() = runTest {
        val rig = rig()
        val tooBig =
            object : InputStream() {
                var left = 20L * 1024 * 1024 + 1

                override fun read() = if (left-- > 0) 0 else -1

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (left <= 0) return -1
                    val n = minOf(len.toLong(), left).toInt()
                    left -= n
                    return n
                }
            }
        rig.env.captureImage("image/png") { tooBig }
        assertThat(rig.env.clips).isEmpty()
        assertThat(rig.repo.clips()).isEmpty()
    }

    @Test
    fun pasteCommitsTextAndHandsImagesToThePaster() = runTest {
        val env = rig().env
        env.copy("hello")
        env.captureImage("image/png") { ByteArrayInputStream(imageBytes) }
        val text = env.clips.single { it.kind == ClipItem.Kind.Text }
        val image = env.clips.single { it.kind == ClipItem.Kind.Image }

        val committed = mutableListOf<String>()
        assertThat(env.paste(text.id) { committed += it }).isTrue()
        assertThat(committed).containsExactly("hello")

        assertThat(env.paste(image.id) { committed += it }).isFalse() // no keyboard attached
        val pasted = mutableListOf<String?>()
        env.imagePaster = ImagePaster {
            pasted += it.mimeType
            true
        }
        assertThat(env.paste(image.id) { committed += it }).isTrue()
        assertThat(pasted).containsExactly("image/png")
        assertThat(committed).containsExactly("hello")

        assertThat(env.paste(9_999) { committed += it }).isFalse()
    }

    @Test
    fun reloadForgetsErasedData() = runTest {
        val rig = rig()
        rig.env.copy("secret-ish")
        rig.repo.clear(keepPinned = false)
        rig.env.reload()
        assertThat(rig.env.clips).isEmpty()
        assertThat(rig.env.freshClip()).isNull()
    }

    // ------------------------------------------------------------------ review fixes

    @Test
    fun equalTextAnywhereInTheHistoryMovesToTheTop() = runTest {
        val rig = rig()
        rig.env.copy("a")
        rig.env.copy("b")
        rig.env.copy("c")
        rig.env.copy("a")
        assertThat(rig.env.previews).containsExactly("a", "c", "b").inOrder()
        assertThat(rig.repo.clips()).hasSize(3)
    }

    @Test
    fun aFailedWriteDoesNotLeaveTheOptimisticChangeOnScreen() = runTest {
        val failing =
            object : ClipboardStore by store {
                override suspend fun delete(ids: Collection<Long>) = throw IOException("disk full")
            }
        val rig = rig(rows = failing)
        rig.env.copy("keep")
        rig.env.copy("drop")
        rig.env.delete(rig.env.clips.single { it.preview == "drop" }.id)
        // The delete was not saved: memory shows what is stored again.
        assertThat(rig.env.previews).containsExactly("drop", "keep").inOrder()
        // Nothing is stuck: the next change still shows.
        rig.env.copy("later")
        assertThat(rig.env.previews).containsExactly("later", "drop", "keep").inOrder()
    }

    @Test
    fun aChangeWhenTheRepositoryIsUnavailableGoesBackToTheLastState() = runTest {
        val rig = rig()
        rig.env.copy("one")
        broken = true
        rig.env.delete(rig.env.clips.single().id)
        assertThat(rig.env.previews).containsExactly("one")
        broken = false
        rig.env.copy("two")
        assertThat(rig.env.previews).containsExactly("two", "one").inOrder()
    }

    @Test
    fun aThumbnailLoadFromBeforeAReloadDoesNotFillTheCache() = runTest {
        val gate = CompletableDeferred<Unit>()
        val decodes = AtomicInteger()
        val rig =
            rig(
                decode = {
                    decodes.incrementAndGet()
                    gate.await()
                    null // a failed decode: would mark the clip as failed
                }
            )
        rig.env.captureImage("image/png") { ByteArrayInputStream(imageBytes) }
        val id = rig.env.clips.single().id
        rig.env.thumbnail(id)
        assertThat(decodes.get()).isEqualTo(1)

        rig.env.reload() // the history was erased and read back while the load was running
        gate.complete(Unit)
        rig.env.thumbnail(id)
        // The old result was dropped: the clip is neither loading nor marked failed, so it loads.
        assertThat(decodes.get()).isEqualTo(2)
    }

    @Test
    fun anOutOfMemoryWhileDecodingLeavesTheThumbnailEmptyAndIsNotRetried() = runTest {
        val decodes = AtomicInteger()
        val rig =
            rig(
                decode = {
                    decodes.incrementAndGet()
                    throw OutOfMemoryError("test")
                }
            )
        rig.env.captureImage("image/png") { ByteArrayInputStream(imageBytes) }
        val id = rig.env.clips.single().id
        assertThat(rig.env.thumbnail(id)).isNull()
        assertThat(rig.env.thumbnail(id)).isNull()
        assertThat(decodes.get()).isEqualTo(1)
    }

    @Test
    fun thumbnailsAreDecodedOneAtATime() = runTest {
        val running = AtomicInteger()
        val most = AtomicInteger()
        val done = AtomicInteger()
        val rig =
            rig(
                decode = {
                    most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                    Thread.sleep(20)
                    running.decrementAndGet()
                    done.incrementAndGet()
                    null
                },
                // The default: the process-wide single lane.
                thumbnailDispatcher = THUMBNAIL_DISPATCHER,
            )
        repeat(6) { rig.env.captureImage("image/png") { ByteArrayInputStream(imageBytes) } }
        rig.env.clips.forEach { rig.env.thumbnail(it.id) }
        eventually { done.get() == 6 }
        assertThat(most.get()).isEqualTo(1)
    }

    /** A stream whose read blocks until it is closed, like a content provider that hangs. */
    private class HangingStream : InputStream() {
        val closed = CountDownLatch(1)

        override fun read(): Int {
            closed.await()
            throw IOException("closed")
        }

        override fun close() = closed.countDown()
    }

    @Test
    fun aHangingImageReadDoesNotBlockTheQueueAndIsDroppedAfterTheTimeout() = runTest {
        val rig = rig(captureDispatcher = Dispatchers.IO, captureTimeout = 200)
        val hanging = HangingStream()
        rig.env.captureImage("image/png") { hanging }
        // Text copied meanwhile goes through at once.
        rig.env.copy("while the image hangs")
        assertThat(rig.env.previews).containsExactly("while the image hangs")
        // After the timeout the stream is closed and nothing is kept.
        assertThat(hanging.closed.await(10, TimeUnit.SECONDS)).isTrue()
        eventually { rig.blobs.list().isEmpty() }
        assertThat(rig.repo.clips().map { it.kind })
            .doesNotContain(dev.keycorrect.core.data.ClipKind.Image)
    }

    @Test
    fun aSlowButFinishingImageReadIsKept() = runTest {
        val rig = rig(captureDispatcher = Dispatchers.IO, captureTimeout = 5_000)
        rig.env.captureImage("image/png") { ByteArrayInputStream(imageBytes) }
        eventually { rig.env.clips.size == 1 }
        assertThat(rig.env.clips.single().kind).isEqualTo(ClipItem.Kind.Image)
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Condition not met in time" }
            Thread.sleep(10)
        }
    }
}
