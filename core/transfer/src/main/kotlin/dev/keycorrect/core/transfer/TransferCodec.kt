package dev.keycorrect.core.transfer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Why a backup could not be read. Messages never contain file content. */
sealed class TransferException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    /** Neither a zip nor an encrypted backup. */
    class NotABackup : TransferException("Not a KeyCorrect backup")

    /** Written by a newer app ([found] > [supported]). */
    class NewerFormat(val found: Int, val supported: Int) :
        TransferException("Backup format $found is newer than supported $supported")

    /** Encrypted, but no passphrase was supplied. */
    class PassphraseRequired : TransferException("This backup is encrypted")

    /** The passphrase is wrong, or the encrypted file was modified (AES-GCM cannot tell). */
    class WrongPassphrase(cause: Throwable? = null) :
        TransferException("Wrong passphrase, or the file was changed", cause)

    /** Damaged or malformed contents. */
    class Corrupt(reason: String, cause: Throwable? = null) :
        TransferException("Damaged backup: $reason", cause)

    /** The contents exceed [TransferLimits] (zip-bomb guard). */
    class TooLarge : TransferException("Backup contents are too large")
}

private const val DEFAULT_MAX_UNCOMPRESSED_BYTES = 200L * 1024 * 1024

/** Guards against hostile files. */
data class TransferLimits(
    val maxUncompressedBytes: Long = DEFAULT_MAX_UNCOMPRESSED_BYTES,
    val maxEntries: Int = 32,
)

/**
 * Reads and writes `.kcbackup` files (docs/TRANSFER.md).
 *
 * Plain file = a zip of `manifest.json`, `settings.json`, `dictionary.json`, `learning.json`,
 * `touch.json`. Encrypted file = header + AES-256-GCM(zip): `"KCBK" | version(1) | kdf id(1) = 1
 * (PBKDF2-HMAC-SHA256) | iterations(4, big endian) | salt(16) | nonce(12) | ciphertext+tag`. The
 * whole header is the GCM associated data.
 */
@Suppress("TooManyFunctions") // Small steps of one read/write pipeline.
object TransferCodec {
    const val PBKDF2_ITERATIONS = 600_000
    internal const val MIN_ITERATIONS = 310_000
    internal const val MAX_ITERATIONS = 5_000_000
    internal const val MAGIC = "KCBK"
    internal const val ENVELOPE_VERSION = 1
    internal const val KDF_PBKDF2_SHA256 = 1
    internal const val SALT_BYTES = 16
    internal const val NONCE_BYTES = 12
    private const val HEADER_BYTES = 4 + 1 + 1 + 4 + SALT_BYTES + NONCE_BYTES
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val BUFFER = 16 * 1024
    private const val BYTE_MASK = 0xFF
    private const val ZIP_MAGIC_0 = 'P'.code.toByte()
    private const val ZIP_MAGIC_1 = 'K'.code.toByte()

    private const val MANIFEST = "manifest.json"
    private const val SETTINGS = "settings.json"
    private const val DICTIONARY = "dictionary.json"
    private const val LEARNING = "learning.json"
    private const val TOUCH = "touch.json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val random = SecureRandom()

    /** True when [bytes] start with the encrypted-envelope magic. */
    fun isEncrypted(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.length && String(bytes, 0, MAGIC.length, Charsets.ISO_8859_1) == MAGIC

    /**
     * Serialises [bundle]. A null or empty [passphrase] gives a plain file; otherwise an encrypted
     * one. The passphrase array is zeroed before returning.
     */
    fun write(
        bundle: TransferBundle,
        passphrase: CharArray? = null,
        iterations: Int = PBKDF2_ITERATIONS,
    ): ByteArray {
        try {
            val zip = zip(bundle)
            return if (passphrase == null || passphrase.isEmpty()) zip
            else encrypt(zip, passphrase, iterations)
        } finally {
            passphrase?.fill('\u0000')
        }
    }

    /**
     * Parses a backup. [passphrase] is asked for only when the file is encrypted; the array it
     * returns is zeroed after use. Throws [TransferException].
     */
    fun read(
        bytes: ByteArray,
        limits: TransferLimits = TransferLimits(),
        passphrase: () -> CharArray? = { null },
    ): TransferBundle {
        val zip =
            when {
                isEncrypted(bytes) -> decrypt(bytes, passphrase)
                bytes.size >= 2 && bytes[0] == ZIP_MAGIC_0 && bytes[1] == ZIP_MAGIC_1 -> bytes
                else -> throw TransferException.NotABackup()
            }
        return unzip(zip, limits)
    }

    // ------------------------------------------------------------------ zip

    private fun zip(b: TransferBundle): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.entry(MANIFEST, json.encodeToString(TransferManifest.serializer(), b.manifest))
            z.entry(SETTINGS, json.encodeToString(SettingsSection.serializer(), b.settings))
            z.entry(DICTIONARY, json.encodeToString(DictionarySection.serializer(), b.dictionary))
            z.entry(LEARNING, json.encodeToString(LearningSection.serializer(), b.learning))
            z.entry(TOUCH, json.encodeToString(TouchSection.serializer(), b.touch))
        }
        return out.toByteArray()
    }

    private fun ZipOutputStream.entry(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    @Suppress("ThrowsCount")
    private fun readEntries(zip: ByteArray, limits: TransferLimits): Map<String, ByteArray> {
        val files = HashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(zip)).use { z ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val e = z.nextEntry ?: break
                    if (files.size >= limits.maxEntries) throw TransferException.TooLarge()
                    val data = ByteArrayOutputStream()
                    total = inflate(z, buf, data, total, limits.maxUncompressedBytes)
                    if (files.put(e.name, data.toByteArray()) != null) {
                        throw TransferException.Corrupt("duplicate entry")
                    }
                }
            }
        } catch (e: IOException) {
            throw TransferException.Corrupt(e.javaClass.simpleName, e)
        }
        return files
    }

    /** Copies the current entry into [out]; returns the new running total, bounded by [max]. */
    private fun inflate(
        z: ZipInputStream,
        buf: ByteArray,
        out: ByteArrayOutputStream,
        total: Long,
        max: Long,
    ): Long {
        var sum = total
        while (true) {
            val n = z.read(buf)
            if (n < 0) return sum
            sum += n
            // Count what is actually inflated, never the sizes the file declares.
            if (sum > max) throw TransferException.TooLarge()
            out.write(buf, 0, n)
        }
    }

    private fun unzip(zip: ByteArray, limits: TransferLimits): TransferBundle {
        val files = readEntries(zip, limits)
        val manifest = parse(files, MANIFEST, TransferManifest.serializer())
        if (manifest.formatVersion > FORMAT_VERSION) {
            throw TransferException.NewerFormat(manifest.formatVersion, FORMAT_VERSION)
        }
        return TransferBundle(
            manifest,
            parse(files, SETTINGS, SettingsSection.serializer()),
            parse(files, DICTIONARY, DictionarySection.serializer()),
            parse(files, LEARNING, LearningSection.serializer()),
            parse(files, TOUCH, TouchSection.serializer()),
        )
    }

    @Suppress("ThrowsCount")
    private fun <T> parse(
        files: Map<String, ByteArray>,
        name: String,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T {
        val data = files[name] ?: throw TransferException.Corrupt("missing $name")
        return try {
            json.decodeFromString(serializer, String(data, Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw TransferException.Corrupt("bad $name", e)
        } catch (e: IllegalArgumentException) {
            throw TransferException.Corrupt("bad $name", e)
        }
    }

    // --------------------------------------------------------------- crypto

    private fun encrypt(plain: ByteArray, passphrase: CharArray, iterations: Int): ByteArray {
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "iterations out of range" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header =
            ByteBuffer.allocate(HEADER_BYTES)
                .put(MAGIC.toByteArray(Charsets.ISO_8859_1))
                .put(ENVELOPE_VERSION.toByte())
                .put(KDF_PBKDF2_SHA256.toByte())
                .putInt(iterations)
                .put(salt)
                .put(nonce)
                .array()
        val key = deriveKey(passphrase, salt, iterations)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, nonce),
            )
            cipher.updateAAD(header)
            return header + cipher.doFinal(plain)
        } finally {
            key.fill(0)
        }
    }

    @Suppress("ThrowsCount")
    private fun decrypt(bytes: ByteArray, passphrase: () -> CharArray?): ByteArray {
        if (bytes.size < HEADER_BYTES + TAG_BITS / Byte.SIZE_BITS) {
            throw TransferException.Corrupt("truncated header")
        }
        val buf = ByteBuffer.wrap(bytes)
        buf.position(MAGIC.length)
        val version = buf.get().toInt() and BYTE_MASK
        if (version > ENVELOPE_VERSION)
            throw TransferException.NewerFormat(version, ENVELOPE_VERSION)
        if (version < 1 || (buf.get().toInt() and BYTE_MASK) != KDF_PBKDF2_SHA256) {
            throw TransferException.Corrupt("unknown key derivation")
        }
        val iterations = buf.getInt()
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) {
            throw TransferException.Corrupt("bad iteration count")
        }
        val salt = ByteArray(SALT_BYTES).also(buf::get)
        val nonce = ByteArray(NONCE_BYTES).also(buf::get)
        val pass = passphrase() ?: throw TransferException.PassphraseRequired()
        val key = deriveKey(pass, salt, iterations)
        pass.fill('\u0000')
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, nonce),
            )
            cipher.updateAAD(bytes, 0, HEADER_BYTES)
            return cipher.doFinal(bytes, HEADER_BYTES, bytes.size - HEADER_BYTES)
        } catch (e: AEADBadTagException) {
            throw TransferException.WrongPassphrase(e)
        } catch (e: GeneralSecurityException) {
            throw TransferException.Corrupt(e.javaClass.simpleName, e)
        } finally {
            key.fill(0)
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
