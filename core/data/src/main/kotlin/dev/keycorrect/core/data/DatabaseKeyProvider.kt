package dev.keycorrect.core.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
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
class KeystoreDatabaseKeyProvider
internal constructor(
    private val prefs: SharedPreferences,
    private val crypto: KeyCrypto,
    private val clearKeystoreEntry: () -> Unit,
) : DatabaseKeyProvider {
    constructor(
        context: Context,
        keyAlias: String = DEFAULT_KEY_ALIAS,
        prefsName: String = DEFAULT_PREFS_NAME,
    ) : this(
        preferencesOf(context, prefsName),
        AndroidKeystoreCrypto(keyAlias),
        { AndroidKeystoreCrypto.deleteEntry(keyAlias) },
    )

    /**
     * Any failure to recover a previously stored key (missing or half-written preferences, bad
     * Base64, lost Keystore entry, authentication failure, wrong length, any Keystore error) is a
     * [KeyUnavailableException]. Only the first-use path (nothing stored) creates a key.
     */
    @Synchronized
    override fun getOrCreatePassphrase(): ByteArray {
        val stored: Pair<String, String>? =
            try {
                val iv = prefs.getString(PREF_IV, null)
                val ct = prefs.getString(PREF_CIPHERTEXT, null)
                when {
                    iv == null && ct == null -> null
                    iv != null && ct != null -> iv to ct
                    else -> throw KeyUnavailableException("Stored database key is incomplete")
                }
            } catch (e: KeyUnavailableException) {
                throw e
            } catch (e: Exception) {
                throw KeyUnavailableException("Stored database key cannot be read", e)
            }
        return if (stored != null) recover(stored.first, stored.second) else createAndStore()
    }

    @SuppressLint("ApplySharedPref")
    @Synchronized
    override fun reset() {
        prefs.edit().clear().commit()
        clearKeystoreEntry()
    }

    @SuppressLint("ApplySharedPref") // durability matters more than latency here
    private fun createAndStore(): ByteArray {
        val passphrase = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        try {
            val sealed = crypto.encrypt(passphrase)
            // commit(), not apply(): the key must be durable before the database is created.
            val saved =
                prefs
                    .edit()
                    .putString(PREF_IV, Base64.getEncoder().encodeToString(sealed.iv))
                    .putString(
                        PREF_CIPHERTEXT,
                        Base64.getEncoder().encodeToString(sealed.ciphertext),
                    )
                    .commit()
            check(saved) { "Could not persist encrypted database key" }
            return passphrase
        } catch (e: Exception) {
            passphrase.fill(0)
            throw e
        }
    }

    private fun recover(ivText: String, ciphertextText: String): ByteArray {
        var plain: ByteArray? = null
        try {
            val iv = Base64.getDecoder().decode(ivText)
            val ciphertext = Base64.getDecoder().decode(ciphertextText)
            plain = crypto.decrypt(iv, ciphertext)
            if (plain.size != PASSPHRASE_BYTES) {
                throw KeyUnavailableException("Stored database key has the wrong length")
            }
            return plain
        } catch (e: KeyUnavailableException) {
            plain?.fill(0)
            throw e
        } catch (e: Exception) {
            // GeneralSecurityException, IllegalArgumentException (Base64), ProviderException,
            // IllegalStateException from the Keystore daemon, ...: all mean "cannot recover".
            plain?.fill(0)
            throw KeyUnavailableException("Stored database key cannot be recovered", e)
        }
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "kc_db_key_v1"
        const val DEFAULT_PREFS_NAME = "kc_db_key"
        const val PASSPHRASE_BYTES = 32

        private const val PREF_IV = "iv"
        private const val PREF_CIPHERTEXT = "ct"

        private fun preferencesOf(context: Context, prefsName: String): SharedPreferences {
            val appContext = context.applicationContext
            check(!appContext.isDeviceProtectedStorage) {
                "Database key must live in credential-encrypted storage"
            }
            return appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        }
    }
}

/** An AES-GCM sealed value: the random [iv] and the [ciphertext] with its tag. */
internal class SealedKey(val iv: ByteArray, val ciphertext: ByteArray)

/** Seals and opens the database passphrase; the production implementation uses the Keystore. */
internal interface KeyCrypto {
    fun encrypt(plain: ByteArray): SealedKey

    /** Throws [KeyUnavailableException] or any security exception when it cannot decrypt. */
    fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray
}

/** AES-256-GCM under a non-exportable AndroidKeyStore key [keyAlias]. */
internal class AndroidKeystoreCrypto(private val keyAlias: String) : KeyCrypto {
    override fun encrypt(plain: ByteArray): SealedKey {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(AAD)
        val ciphertext = cipher.doFinal(plain)
        return SealedKey(cipher.iv, ciphertext)
    }

    override fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val key =
            keyStore().getKey(keyAlias, null) as? SecretKey
                ?: throw KeyUnavailableException("Keystore entry $keyAlias is missing")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(AAD)
        return cipher.doFinal(ciphertext)
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

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private val AAD = "dev.keycorrect.core.data.db_passphrase.v1".toByteArray()

        private fun keyStore(): KeyStore =
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        fun deleteEntry(alias: String) {
            val ks = keyStore()
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        }
    }
}
