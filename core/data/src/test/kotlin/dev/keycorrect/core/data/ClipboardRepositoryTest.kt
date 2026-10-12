package dev.keycorrect.core.data

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.File
import javax.crypto.KeyGenerator
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClipboardRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000L
    private val store = InMemoryClipboardStore { now }
    private val dir by lazy { File(tmp.root, "clips") }
    private val blobs by lazy {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        EncryptedBlobStore(dir, AesGcmBlobCipher { key })
    }
    private val repo by lazy { ClipboardRepository(store, blobs, { now }) }

    private suspend fun image(size: Int = 10, pinned: Boolean = false): Long {
        now += 10
        val id = repo.insertImage(ByteArray(size) { it.toByte() }, "image/png")
        if (pinned) repo.setPinned(id, true)
        return id
    }

    @Test
    fun insertImageWritesEncryptedBlobThenRow() = runTest {
        val bytes = "PLAINTEXT-IMAGE".toByteArray()
        val id = repo.insertImage(ByteArrayInputStream(bytes), "image/png")
        val clip = repo.clips().single { it.id == id }
        assertThat(clip.kind).isEqualTo(ClipKind.Image)
        assertThat(clip.imageBytes).isEqualTo(bytes.size.toLong())
        assertThat(clip.mimeType).isEqualTo("image/png")
        assertThat(repo.openImage(clip).use { it.readBytes() }).isEqualTo(bytes)
        assertThat(String(File(dir, clip.imageFile!!).readBytes(), Charsets.ISO_8859_1))
            .doesNotContain("PLAINTEXT")
    }

    @Test
    fun oversizedImageLeavesNoRowAndNoBlob() = runTest {
        val e = runCatching { repo.insertImage(ByteArray(50), "image/png", 10) }.exceptionOrNull()
        assertThat(e).isInstanceOf(BlobTooLargeException::class.java)
        assertThat(repo.clips()).isEmpty()
        assertThat(blobs.list()).isEmpty()
    }

    @Test
    fun failedRowInsertRemovesTheBlob() = runTest {
        val failing =
            object : ClipboardStore by store {
                override suspend fun insert(clip: NewClip): Long = error("db down")
            }
        val r = ClipboardRepository(failing, blobs, { now })
        val e = runCatching { r.insertImage(ByteArray(5), "image/png") }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalStateException::class.java)
        assertThat(blobs.list()).isEmpty()
    }

    @Test
    fun deleteRemovesRowsAndBlobs() = runTest {
        val a = image()
        val b = image()
        repo.insertText("text")
        repo.delete(listOf(a))
        assertThat(repo.clips().map { it.id }).doesNotContain(a)
        assertThat(blobs.list()).hasSize(1)
        assertThat(repo.clips().single { it.id == b }.imageFile).isEqualTo(blobs.list().single())
    }

    @Test
    fun clearKeepsPinnedImagesAndTheirBlobs() = runTest {
        val pinned = image(pinned = true)
        image()
        repo.clear(keepPinned = true)
        assertThat(repo.clips().map { it.id }).containsExactly(pinned)
        assertThat(blobs.list()).hasSize(1)
        repo.clear(keepPinned = false)
        assertThat(repo.clips()).isEmpty()
        assertThat(blobs.list()).isEmpty()
    }

    @Test
    fun clearSinceRemovesNewerBlobsOnly() = runTest {
        now = 1_000
        val old = image()
        now = 5_000
        image()
        repo.clearSince(3_000)
        assertThat(repo.clips().map { it.id }).containsExactly(old)
        assertThat(blobs.list()).hasSize(1)
    }

    @Test
    fun pruneDropsExpiredUnpinnedClipsOnly() = runTest {
        repo.insertText("expired", expiresAt = 1_500)
        val pinned = store.insert(NewClip.text("pinned-expired", expiresAt = 1_500, pinned = true))
        repo.insertText("fresh", expiresAt = 9_000)
        repo.insertText("forever")
        val removed = repo.prune(nowMillis = 2_000)
        assertThat(removed).isEqualTo(1)
        assertThat(repo.clips().map { it.text })
            .containsExactly("forever", "fresh", "pinned-expired")
        assertThat(repo.clips().any { it.id == pinned }).isTrue()
    }

    @Test
    fun pruneKeepsTheNewestTextClipsAndSparesPinned() = runTest {
        val pinned = store.insert(NewClip.text("pinned", pinned = true))
        for (i in 1..5) repo.insertText("t$i")
        val removed = repo.prune(ClipboardLimits(maxTextClips = 3), nowMillis = 2_000)
        assertThat(removed).isEqualTo(2)
        assertThat(repo.clips().map { it.text }).containsExactly("t5", "t4", "t3", "pinned")
        assertThat(repo.clips().any { it.id == pinned }).isTrue()
    }

    @Test
    fun pruneDropsOldestUnpinnedImagesAndTheirBlobsToFitTheByteCap() = runTest {
        val oldest = image(size = 40)
        val pinned = image(size = 40, pinned = true)
        val newest = image(size = 40)
        val removed = repo.prune(ClipboardLimits(maxImageBytes = 90), nowMillis = 0)
        assertThat(removed).isEqualTo(1)
        assertThat(repo.clips().map { it.id }).containsExactly(newest, pinned)
        assertThat(repo.clips().any { it.id == oldest }).isFalse()
        assertThat(blobs.list()).hasSize(2)
    }

    @Test
    fun orphanSweepRemovesBlobsWithoutRowsAndHalfWrittenFiles() = runTest {
        val kept = image()
        val orphan = blobs.write(ByteArray(8))
        val halfWritten = "00000000-0000-4000-8000-000000000001" + EncryptedBlobStore.TEMP_SUFFIX
        File(dir, halfWritten).writeBytes(byteArrayOf(1))
        File(dir, "foreign.txt").writeText("not ours")

        assertThat(repo.orphanSweep()).isEqualTo(2)

        assertThat(blobs.list()).containsExactly(repo.clips().single { it.id == kept }.imageFile)
        assertThat(blobs.list()).doesNotContain(orphan)
        assertThat(File(dir, "foreign.txt").exists()).isTrue()
    }

    @Test
    fun orphanSweepRemovesRowsWhoseBlobIsGone() = runTest {
        val gone = image()
        val kept = image()
        blobs.delete(repo.clips().single { it.id == gone }.imageFile!!)
        repo.insertText("text")

        assertThat(repo.orphanSweep()).isEqualTo(1)

        assertThat(repo.clips().map { it.id }).doesNotContain(gone)
        assertThat(repo.clips().map { it.id }).contains(kept)
        assertThat(repo.clips().any { it.text == "text" }).isTrue()
    }
}
