package dev.keycorrect.core.data

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import java.util.UUID
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream

/** A blob is larger than the limit given to [EncryptedBlobStore.write]. */
class BlobTooLargeException(val maxBytes: Long) :
    IOException("Blob exceeds the $maxBytes byte limit")

/**
 * Image bytes at rest: one file per blob under [directory] (the app uses `noBackupFilesDir/clips`),
 * each sealed with AES-256-GCM via [cipher]. The plaintext exists only in memory while writing or
 * while the stream returned by [openDecrypted] is read.
 *
 * File layout: `[version=1][ivLength][iv][ciphertext + 16-byte tag]`. The blob's name is
 * authenticated as additional data, so files cannot be swapped between names.
 *
 * Methods block on disk and Keystore IO: call them off the main thread. Names are random UUIDs; any
 * other name is rejected, so a name from outside can never escape [directory]. Blob contents are
 * user data and are never logged. The GCM tag is checked when the stream is fully read (the JCA
 * providers release no plaintext before that); a damaged file throws [IOException].
 */
class EncryptedBlobStore(
    private val directory: File,
    private val cipher: BlobCipher,
    private val defaultMaxBytes: Long = DEFAULT_MAX_BYTES,
) {
    /**
     * Encrypts [bytes] into a new blob and returns its name.
     *
     * @throws BlobTooLargeException when [bytes] is longer than [maxBytes].
     */
    fun write(bytes: ByteArray, maxBytes: Long = defaultMaxBytes): String {
        if (bytes.size > maxBytes) throw BlobTooLargeException(maxBytes)
        return write(bytes.inputStream(), maxBytes)
    }

    /**
     * Reads [input] to its end (it is not closed) and encrypts it into a new blob, returning the
     * name. At most [maxBytes] + 1 bytes are read; nothing is left on disk on any failure.
     *
     * @throws BlobTooLargeException when the stream holds more than [maxBytes].
     */
    fun write(input: InputStream, maxBytes: Long = defaultMaxBytes): String =
        newName().also { write(it, input, maxBytes) }

    /** [write] into blob [name] (from [newName]). */
    fun write(name: String, input: InputStream, maxBytes: Long = defaultMaxBytes) {
        require(maxBytes >= 0) { "Negative limit" }
        directory.mkdirs()
        val temp = File(directory, name + TEMP_SUFFIX)
        var done = false
        try {
            val encryptor = cipher.newEncryptor(aadFor(name))
            val iv = encryptor.iv
            check(iv != null && iv.size in 1..MAX_IV_BYTES) { "Unusable IV" }
            FileOutputStream(temp).use { file ->
                val out = BufferedOutputStream(file)
                out.write(VERSION)
                out.write(iv.size)
                out.write(iv)
                val sealed = CipherOutputStream(out, encryptor)
                val buffer = ByteArray(BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw BlobTooLargeException(maxBytes)
                    sealed.write(buffer, 0, n)
                }
                sealed.close() // writes the tag and closes the buffered stream
            }
            if (!temp.renameTo(File(directory, name))) throw IOException("Cannot store blob")
            done = true
        } finally {
            if (!done) temp.delete()
        }
    }

    /**
     * A stream of the decrypted bytes of blob [name]. The caller closes it.
     *
     * @throws java.io.FileNotFoundException when there is no such blob.
     * @throws IOException when the file is damaged or was sealed with another key.
     */
    fun openDecrypted(name: String): InputStream {
        val raw = FileInputStream(existingFile(name)).buffered()
        try {
            val iv = readHeader(raw)
            return CipherInputStream(raw, cipher.newDecryptor(iv, aadFor(name)))
        } catch (e: IOException) {
            raw.close()
            throw e
        } catch (e: GeneralSecurityException) {
            raw.close()
            throw IOException("Cannot open blob", e)
        }
    }

    private fun existingFile(name: String): File =
        fileFor(name) ?: throw java.io.FileNotFoundException("No such blob")

    /**
     * Deletes blob [name] (or its half-written `.tmp` file); returns whether a file was removed.
     * Names of any other shape are ignored.
     */
    fun delete(name: String): Boolean {
        val base = name.removeSuffix(TEMP_SUFFIX)
        if (!isValidName(base)) return false
        val removedBlob = File(directory, base).delete()
        val removedTemp = File(directory, base + TEMP_SUFFIX).delete()
        return removedBlob || removedTemp
    }

    /** Deletes every blob, including half-written ones. */
    fun deleteAll() {
        directory.listFiles()?.forEach { it.delete() }
    }

    /** The plaintext size of blob [name] in bytes, or null when it does not exist. */
    fun sizeOf(name: String): Long? {
        val file = fileFor(name)
        val overhead = file?.let(::headerLength)
        return if (file == null || overhead == null) null else file.length() - overhead - TAG_BYTES
    }

    /**
     * Names of the blobs (and half-written `.tmp` files) in the directory; foreign files are
     * skipped.
     */
    fun list(): List<String> =
        directory.list().orEmpty().filter { isValidName(it.removeSuffix(TEMP_SUFFIX)) }

    private fun fileFor(name: String): File? =
        if (isValidName(name)) File(directory, name).takeIf { it.isFile } else null

    private fun aadFor(name: String): ByteArray = (AAD_PREFIX + name).toByteArray(Charsets.UTF_8)

    companion object {
        /** A fresh blob name, for a caller that must know it before [write] finishes. */
        fun newName(): String = UUID.randomUUID().toString()

        private fun readHeader(input: InputStream): ByteArray {
            val version = input.read()
            val ivLength = input.read()
            if (version != VERSION || ivLength !in 1..MAX_IV_BYTES) {
                throw IOException("Unsupported or damaged blob")
            }
            return ByteArray(ivLength).also { DataInputStream(input).readFully(it) }
        }

        private fun headerLength(file: File): Long? =
            try {
                FileInputStream(file).use { input ->
                    val version = input.read()
                    val ivLength = input.read()
                    if (version == VERSION && ivLength in 1..MAX_IV_BYTES) 2L + ivLength else null
                }
            } catch (_: IOException) {
                null
            }

        /** Default per-write limit, equal to the 20 MB image cap of the clipboard history. */
        const val DEFAULT_MAX_BYTES = 20L * 1024 * 1024

        internal const val TEMP_SUFFIX = ".tmp"
        private const val VERSION = 1
        private const val MAX_IV_BYTES = 32
        private const val TAG_BYTES = BlobCipher.TAG_BITS / 8
        private const val BUFFER_BYTES = 16 * 1024
        private const val AAD_PREFIX = "dev.keycorrect.core.data.clip_blob.v1:"
        private val NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /** Whether [name] has the shape of a name returned by [write]. */
        fun isValidName(name: String): Boolean = NAME.matches(name)
    }
}
