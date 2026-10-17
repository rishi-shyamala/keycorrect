package dev.keycorrect.core.data

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EncryptedBlobStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun newKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun store(key: SecretKey = newKey(), max: Long = 1_000_000) =
        EncryptedBlobStore(File(tmp.root, "clips"), AesGcmBlobCipher { key }, max)

    private fun EncryptedBlobStore.readAll(name: String) =
        openDecrypted(name).use { it.readBytes() }

    @Test
    fun roundTripsAndKeepsPlaintextOffDisk() {
        val s = store()
        val secret = "SENTINEL-IMAGE-BYTES".repeat(50).toByteArray()
        val name = s.write(secret)
        assertThat(EncryptedBlobStore.isValidName(name)).isTrue()
        assertThat(s.readAll(name)).isEqualTo(secret)
        assertThat(s.sizeOf(name)).isEqualTo(secret.size.toLong())
        val onDisk = File(File(tmp.root, "clips"), name).readBytes()
        assertThat(String(onDisk, Charsets.ISO_8859_1)).doesNotContain("SENTINEL")
    }

    @Test
    fun emptyAndMultiBufferBlobsRoundTrip() {
        val s = store()
        val big = ByteArray(100_000) { (it * 31).toByte() }
        assertThat(s.readAll(s.write(ByteArray(0)))).isEmpty()
        assertThat(s.readAll(s.write(ByteArrayInputStream(big)))).isEqualTo(big)
    }

    @Test
    fun everyWriteGetsItsOwnNameAndIv() {
        val s = store()
        val a = s.write(byteArrayOf(1, 2, 3))
        val b = s.write(byteArrayOf(1, 2, 3))
        assertThat(a).isNotEqualTo(b)
        val dir = File(tmp.root, "clips")
        assertThat(File(dir, a).readBytes()).isNotEqualTo(File(dir, b).readBytes())
    }

    @Test
    fun oversizedWritesAreRejectedAndLeaveNothingBehind() {
        val s = store(max = 100)
        assertThrows(BlobTooLargeException::class.java) { s.write(ByteArray(101)) }
        assertThrows(BlobTooLargeException::class.java) {
            s.write(ByteArrayInputStream(ByteArray(5_000)), 100)
        }
        assertThat(s.list()).isEmpty()
        assertThat(s.sizeOf(s.write(ByteArray(100)))).isEqualTo(100)
        // A caller-supplied limit overrides the default.
        assertThrows(BlobTooLargeException::class.java) { s.write(ByteArray(11), 10) }
    }

    @Test
    fun tamperedFilesFailToRead() {
        val s = store()
        val name = s.write("hello world".toByteArray())
        val file = File(File(tmp.root, "clips"), name)
        val bytes = file.readBytes()
        bytes[bytes.size - 20] = (bytes[bytes.size - 20].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertThrows(IOException::class.java) { s.readAll(name) }
    }

    @Test
    fun aFileRenamedToAnotherNameDoesNotAuthenticate() {
        val s = store()
        val name = s.write("hello world".toByteArray())
        val dir = File(tmp.root, "clips")
        val other = "00000000-0000-4000-8000-000000000000"
        File(dir, name).renameTo(File(dir, other))
        assertThrows(IOException::class.java) { s.readAll(other) }
    }

    @Test
    fun anotherKeyCannotRead() {
        val name = store(newKey()).write("hello".toByteArray())
        val other = EncryptedBlobStore(File(tmp.root, "clips"), AesGcmBlobCipher { newKey() })
        assertThrows(IOException::class.java) { other.readAll(name) }
    }

    @Test
    fun deleteAndDeleteAll() {
        val s = store()
        val a = s.write(byteArrayOf(1))
        val b = s.write(byteArrayOf(2))
        assertThat(s.delete(a)).isTrue()
        assertThat(s.delete(a)).isFalse()
        assertThat(s.sizeOf(a)).isNull()
        assertThat(s.list()).containsExactly(b)
        s.deleteAll()
        assertThat(s.list()).isEmpty()
    }

    @Test
    fun namesThatCouldEscapeTheDirectoryAreRejected() {
        val s = store()
        File(tmp.root, "outside").writeText("keep")
        assertThat(s.delete("../outside")).isFalse()
        assertThat(File(tmp.root, "outside").exists()).isTrue()
        assertThat(s.sizeOf("../outside")).isNull()
        assertThrows(IOException::class.java) { s.openDecrypted("../outside") }
    }
}
