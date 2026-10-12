package dev.keycorrect.core.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Creates AES-GCM [Cipher]s for [EncryptedBlobStore]. Splitting this out lets JVM tests use a plain
 * AES key ([AesGcmBlobCipher]) while the app uses the Android Keystore ([KeystoreBlobCipher]).
 *
 * Both operations take [aad], which the store binds to the blob's name so a file renamed to another
 * name no longer authenticates.
 */
interface BlobCipher {
    /** A cipher ready to encrypt, with a fresh random IV (read it from [Cipher.getIV]). */
    fun newEncryptor(aad: ByteArray): Cipher

    /** A cipher ready to decrypt data sealed with [iv]. */
    fun newDecryptor(iv: ByteArray, aad: ByteArray): Cipher

    companion object {
        /** GCM authentication tag length, in bits. */
        const val TAG_BITS = 128
    }
}

/** AES-256-GCM under a key supplied by [key] (called for every operation). */
open class AesGcmBlobCipher(private val key: () -> SecretKey) : BlobCipher {
    override fun newEncryptor(aad: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            // No IV given: the provider (Keystore requires it) picks a random one.
            init(Cipher.ENCRYPT_MODE, key())
            updateAAD(aad)
        }

    override fun newDecryptor(iv: ByteArray, aad: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(BlobCipher.TAG_BITS, iv))
            updateAAD(aad)
        }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/**
 * AES-256-GCM under a dedicated, non-exportable AndroidKeyStore key [alias], created on first use
 * (hardware-backed where available, like the database key; not bound to user authentication so the
 * keyboard works after first unlock). The key is separate from the database key. If the key is
 * lost, the blobs are unreadable, which surfaces as an exception on open; the repository's orphan
 * sweep then removes rows whose blob cannot be found, and the user can clear history.
 */
class KeystoreBlobCipher(alias: String = DEFAULT_ALIAS) :
    AesGcmBlobCipher({ getOrCreateKey(alias) }) {
    companion object {
        const val DEFAULT_ALIAS = "keycorrect_clip_blobs"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        private fun keyStore(): KeyStore =
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        @Synchronized
        private fun getOrCreateKey(alias: String): SecretKey {
            (keyStore().getKey(alias, null) as? SecretKey)?.let {
                return it
            }
            val spec =
                KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            val generator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(spec)
            return generator.generateKey()
        }

        /**
         * Removes the key (tests, and "erase all data"). Blobs sealed with it become unreadable.
         */
        fun deleteKey(alias: String = DEFAULT_ALIAS) {
            val ks = keyStore()
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        }
    }
}
