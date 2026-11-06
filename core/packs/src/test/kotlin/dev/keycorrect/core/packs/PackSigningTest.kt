package dev.keycorrect.core.packs

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PackSigningTest {
    private val own = setOf("aa", "bb")

    @Test
    fun sameSignersAreAccepted() {
        assertThat(checkPackSigners(setOf("bb", "aa"), own)).isNull()
    }

    @Test
    fun differentSignerIsRefused() {
        assertThat(checkPackSigners(setOf("cc"), own)).isEqualTo(PackProblem.SignatureMismatch)
    }

    @Test
    fun supersetOrSubsetIsRefused() {
        assertThat(checkPackSigners(setOf("aa"), own)).isEqualTo(PackProblem.SignatureMismatch)
        assertThat(checkPackSigners(setOf("aa", "bb", "cc"), own))
            .isEqualTo(PackProblem.SignatureMismatch)
    }

    @Test
    fun unreadableOrEmptyPackSignaturesAreRefused() {
        assertThat(checkPackSigners(null, own)).isEqualTo(PackProblem.SignatureMismatch)
        assertThat(checkPackSigners(emptySet(), own)).isEqualTo(PackProblem.SignatureMismatch)
    }

    @Test
    fun unknownOwnSignersRefuseEvenAnEmptyMatch() {
        assertThat(checkPackSigners(emptySet(), emptySet()))
            .isEqualTo(PackProblem.SignatureMismatch)
        assertThat(checkPackSigners(setOf("aa"), emptySet()))
            .isEqualTo(PackProblem.SignatureMismatch)
    }

    @Test
    fun certDigestIsLowerCaseHexSha256() {
        assertThat(certDigest(ByteArray(0)))
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }
}
