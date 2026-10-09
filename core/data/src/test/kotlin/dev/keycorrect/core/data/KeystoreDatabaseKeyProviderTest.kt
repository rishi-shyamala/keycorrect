package dev.keycorrect.core.data

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import java.security.GeneralSecurityException
import java.util.Base64
import org.junit.Assert.assertThrows
import org.junit.Test

/** The provider's recovery logic on the JVM, with a fake store and a fake cipher. */
class KeystoreDatabaseKeyProviderTest {
    private class FakePrefs(val map: MutableMap<String, Any?> = HashMap()) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = map

        override fun getString(key: String, defValue: String?): String? =
            if (map.containsKey(key)) map[key] as String? else defValue

        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues

        override fun getInt(key: String, defValue: Int) = defValue

        override fun getLong(key: String, defValue: Long) = defValue

        override fun getFloat(key: String, defValue: Float) = defValue

        override fun getBoolean(key: String, defValue: Boolean) = defValue

        override fun contains(key: String) = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private var clear = false

            override fun putString(key: String, value: String?) = apply { pending[key] = value }

            override fun putStringSet(key: String, values: MutableSet<String>?) = this

            override fun putInt(key: String, value: Int) = this

            override fun putLong(key: String, value: Long) = this

            override fun putFloat(key: String, value: Float) = this

            override fun putBoolean(key: String, value: Boolean) = this

            override fun remove(key: String) = apply { pending[key] = null }

            override fun clear() = apply { clear = true }

            override fun commit(): Boolean {
                if (clear) map.clear()
                map.putAll(pending)
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }

    /** XOR "cipher" with a tag byte so a wrong IV or tampered text fails like GCM would. */
    private class FakeCrypto : KeyCrypto {
        var failWith: Exception? = null
        var returnLength: Int? = null

        override fun encrypt(plain: ByteArray): SealedKey {
            val iv = ByteArray(12) { 7 }
            return SealedKey(iv, plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray() + 1)
        }

        override fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
            failWith?.let { throw it }
            if (iv.size != 12 || ciphertext.isEmpty() || ciphertext.last() != 1.toByte()) {
                throw GeneralSecurityException("tag mismatch")
            }
            val plain =
                ciphertext.copyOf(ciphertext.size - 1).map { (it.toInt() xor 0x5A).toByte() }
            return plain.toByteArray().copyOf(returnLength ?: plain.size)
        }
    }

    private val prefs = FakePrefs()
    private val crypto = FakeCrypto()
    private var cleared = 0
    private val provider = KeystoreDatabaseKeyProvider(prefs, crypto) { cleared++ }

    private fun put(iv: String?, ct: String?) {
        prefs.map["iv"] = iv
        prefs.map["ct"] = ct
    }

    @Test
    fun firstUseCreatesAndLaterRunsRecoverTheSameKey() {
        val first = provider.getOrCreatePassphrase()
        assertThat(first).hasLength(32)
        assertThat(prefs.map.keys).containsExactly("iv", "ct")
        val second = KeystoreDatabaseKeyProvider(prefs, crypto) {}.getOrCreatePassphrase()
        assertThat(second).isEqualTo(first)
    }

    @Test
    fun invalidBase64IsKeyUnavailable() {
        provider.getOrCreatePassphrase()
        put("!!!not base64!!!", "also not")
        val e =
            assertThrows(KeyUnavailableException::class.java) { provider.getOrCreatePassphrase() }
        assertThat(e).hasCauseThat().isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun truncatedOrTamperedBlobIsKeyUnavailable() {
        provider.getOrCreatePassphrase()
        val ct = Base64.getDecoder().decode(prefs.map["ct"] as String)
        put(prefs.map["iv"] as String, Base64.getEncoder().encodeToString(ct.copyOf(5)))
        assertThrows(KeyUnavailableException::class.java) { provider.getOrCreatePassphrase() }
        put(Base64.getEncoder().encodeToString(ByteArray(3)), prefs.map["ct"] as String)
        assertThrows(KeyUnavailableException::class.java) { provider.getOrCreatePassphrase() }
        put("", "")
        assertThrows(KeyUnavailableException::class.java) { provider.getOrCreatePassphrase() }
    }

    @Test
    fun halfWrittenBlobIsKeyUnavailableNotASilentNewKey() {
        provider.getOrCreatePassphrase()
        val iv = prefs.map["iv"] as String
        put(iv, null)
        assertThrows(KeyUnavailableException::class.java) { provider.getOrCreatePassphrase() }
        put(null, iv)
        assertThrows(KeyUnavailableException::class.java) { provider.getOrCreatePassphrase() }
    }

    @Test
    fun wrongTypeInPreferencesIsKeyUnavailable() {
        prefs.map["iv"] = 42
        prefs.map["ct"] = 43
        assertThrows(KeyUnavailableException::class.java) { provider.getOrCreatePassphrase() }
    }

    @Test
    fun anyKeystoreFailureIsKeyUnavailable() {
        provider.getOrCreatePassphrase()
        for (failure in
            listOf<Exception>(
                GeneralSecurityException("lost"),
                java.security.ProviderException("daemon died"),
                IllegalStateException("keystore"),
                RuntimeException("anything"),
            )) {
            crypto.failWith = failure
            val e =
                assertThrows(KeyUnavailableException::class.java) {
                    provider.getOrCreatePassphrase()
                }
            assertThat(e).hasCauseThat().isSameInstanceAs(failure)
        }
    }

    @Test
    fun wrongLengthPlaintextIsKeyUnavailable() {
        provider.getOrCreatePassphrase()
        crypto.returnLength = 16
        assertThrows(KeyUnavailableException::class.java) { provider.getOrCreatePassphrase() }
    }

    @Test
    fun resetForgetsTheKey() {
        val first = provider.getOrCreatePassphrase()
        provider.reset()
        assertThat(cleared).isEqualTo(1)
        assertThat(prefs.map).isEmpty()
        assertThat(provider.getOrCreatePassphrase()).isNotEqualTo(first)
    }

    @Test
    fun rawKeySyntaxIsHexAndCanBeWiped() {
        val key = ByteArray(32) { it.toByte() }
        val hex = KeyCorrectDatabase.toRawKeySyntax(key)
        assertThat(String(hex)).startsWith("x'000102")
        assertThat(String(hex)).endsWith("1e1f'")
        hex.fill(0)
        assertThat(hex.all { it == 0.toByte() }).isTrue()
    }
}
