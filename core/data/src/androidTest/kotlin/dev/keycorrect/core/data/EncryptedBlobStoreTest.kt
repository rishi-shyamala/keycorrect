package dev.keycorrect.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The blob store against the real Android Keystore. */
@RunWith(AndroidJUnit4::class)
class EncryptedBlobStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alias = "kc_blob_test_${UUID.randomUUID().toString().take(8)}"
    private val dir = File(context.cacheDir, "kc_blob_test_${UUID.randomUUID()}")

    private fun store(max: Long = 1_000_000) =
        EncryptedBlobStore(dir, KeystoreBlobCipher(alias), max)

    @Before fun setUp() = KeystoreBlobCipher.deleteKey(alias)

    @After
    fun tearDown() {
        dir.deleteRecursively()
        KeystoreBlobCipher.deleteKey(alias)
    }

    @Test
    fun roundTripWithRealKeystoreKeepsPlaintextOffDisk() {
        val s = store()
        val secret = "SENTINEL-IMAGE-BYTES".repeat(500).toByteArray()
        val name = s.write(secret)

        assertThat(s.openDecrypted(name).use { it.readBytes() }).isEqualTo(secret)
        assertThat(s.sizeOf(name)).isEqualTo(secret.size.toLong())
        val onDisk = String(File(dir, name).readBytes(), Charsets.ISO_8859_1)
        assertThat(onDisk).doesNotContain("SENTINEL")
        assertThat(File(dir, name).length()).isGreaterThan(secret.size.toLong())
    }

    @Test
    fun blobsSurviveANewStoreInstanceWithTheSameKey() {
        val name = store().write(byteArrayOf(1, 2, 3))
        assertThat(store().openDecrypted(name).use { it.readBytes() })
            .isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun aDeletedKeyMakesBlobsUnreadable() {
        val name = store().write(byteArrayOf(1, 2, 3))
        KeystoreBlobCipher.deleteKey(alias)
        assertThrows(IOException::class.java) { store().openDecrypted(name).use { it.readBytes() } }
    }

    @Test
    fun oversizedWriteIsRejectedAndDeleteRemovesTheFile() {
        val s = store(max = 64)
        assertThrows(BlobTooLargeException::class.java) { s.write(ByteArray(65)) }
        assertThat(s.list()).isEmpty()
        val name = s.write(ByteArray(64))
        assertThat(s.delete(name)).isTrue()
        assertThat(s.sizeOf(name)).isNull()
    }
}
