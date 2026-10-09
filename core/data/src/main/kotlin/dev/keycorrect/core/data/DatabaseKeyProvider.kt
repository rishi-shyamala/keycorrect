package dev.keycorrect.core.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Supplies the passphrase of the encrypted database.
 *
 * Threading: implementations block (Keystore IPC, disk), so call from a background thread only.
 */
interface DatabaseKeyProvider {
    /**
     * Returns the 32-byte database key, creating and persisting it on first use. The caller owns
     * the returned array and should zero it as soon as it has been handed to the database layer.
     *
     * @throws KeyUnavailableException when a key was stored previously but can no longer be
     *   recovered (Keystore entry lost, ciphertext corrupt). The database is then unreadable.
     */
    fun getOrCreatePassphrase(): ByteArray

    /** Irreversibly forgets the key. Any database encrypted with it becomes unreadable. */
    fun reset()
}

/** The stored passphrase cannot be recovered, so existing encrypted data is lost. */
class KeyUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * [DatabaseKeyProvider] backed by AndroidKeyStore.
 *
 * Flow: on first use a random 32-byte passphrase is generated and encrypted with AES-256-GCM under
 * a non-exportable AndroidKeyStore key (alias [keyAlias]; hardware-backed where the device has a
 * TEE; not StrongBox, whose per-operation latency would slow IME start). The IV and ciphertext are
 * stored Base64-encoded in a private SharedPreferences file in credential-encrypted storage (the
 * app's default storage, not device-protected). Later runs decrypt it again. The plaintext exists
 * only in the returned array.
 *
 * Threat model: protects the database against offline extraction of the app's files (rooted device
 * image, adb backup, cloud transfer): without the Keystore key, which never leaves the secure
 * hardware or keystore daemon, the stored blob and the database are useless. It does NOT protect
 * against a compromised running app process, which can ask the Keystore to decrypt on its behalf,
 * nor against a rooted attacker who can inject code into the process. The key is deliberately not
 * bound to user authentication or "unlocked device" so the keyboard keeps working on the lock
 * screen after first unlock.
 *
 * Backup is disabled app-wide (docs/PLAN.md section 15), so the preferences file and database are
 * never restored onto a device that lacks the matching Keystore key.
 */
class KeystoreDatabaseKeyProvider(
    context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    prefsName: String = DEFAULT_PREFS_NAME,
) : DatabaseKeyProvider {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences

    init {
        check(!appContext.isDeviceProtectedStorage) {
            "Database key must live in credential-encrypted storage"
        }
        prefs = appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    @Synchronized
    override fun getOrCreatePassphrase(): ByteArray {
        val iv = prefs.getString(PREF_IV, null)
        val ciphertext = prefs.getString(PREF_CIPHERTEXT, null)
        return if (iv != null && ciphertext != null) {
            decrypt(Base64.decode(iv, Base64.NO_WRAP), Base64.decode(ciphertext, Base64.NO_WRAP))
        } else {
            createAndStore()
        }
    }

    @SuppressLint("ApplySharedPref")
    @Synchronized
    override fun reset() {
        prefs.edit().clear().commit()
        val ks = keyStore()
        if (ks.containsAlias(keyAlias)) ks.deleteEntry(keyAlias)
    }

    @SuppressLint("ApplySharedPref") // durability matters more than latency here
    private fun createAndStore(): ByteArray {
        val passphrase = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(AAD)
            val ciphertext = cipher.doFinal(passphrase)
            // commit(), not apply(): the key must be durable before the database is created.
            val saved =
                prefs
                    .edit()
                    .putString(PREF_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                    .putString(PREF_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                    .commit()
            check(saved) { "Could not persist encrypted database key" }
            return passphrase
        } catch (e: Exception) {
            passphrase.fill(0)
            throw e
        }
    }

    private fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        try {
            val key =
                keyStore().getKey(keyAlias, null) as? SecretKey
                    ?: throw KeyUnavailableException("Keystore entry $keyAlias is missing")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(AAD)
            return cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            throw KeyUnavailableException("Stored database key cannot be decrypted", e)
        } catch (e: IllegalArgumentException) {
            throw KeyUnavailableException("Stored database key is corrupt", e)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore().getKey(keyAlias, null) as? SecretKey)?.let {
            return it
        }
        val spec =
            KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(spec)
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        const val DEFAULT_KEY_ALIAS = "kc_db_key_v1"
        const val DEFAULT_PREFS_NAME = "kc_db_key"
        const val PASSPHRASE_BYTES = 32

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val PREF_IV = "iv"
        private const val PREF_CIPHERTEXT = "ct"
        private val AAD = "dev.keycorrect.core.data.db_passphrase.v1".toByteArray()
    }
}
