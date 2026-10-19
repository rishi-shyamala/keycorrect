package dev.keycorrect.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EngineContractTest {
    @Test
    fun theContractMatchesThePlan() {
        assertThat(EngineContract.ACTION).isEqualTo("dev.keycorrect.TEXT_ENGINE")
        assertThat(EngineContract.API_VERSION).isEqualTo(1)
        assertThat(EngineContract.MAX_TIMEOUT_MS).isEqualTo(2000L)
    }

    @Test
    fun timeoutsAreClamped() {
        assertThat(EngineContract.clampTimeout(40)).isEqualTo(40L)
        assertThat(EngineContract.clampTimeout(0)).isEqualTo(1L)
        assertThat(EngineContract.clampTimeout(Long.MIN_VALUE)).isEqualTo(1L)
        assertThat(EngineContract.clampTimeout(Long.MAX_VALUE)).isEqualTo(2000L)
    }

    @Test
    fun errorStatuses() {
        val status = EngineContract.errorStatus("LibrariesMissing")

        assertThat(status).isEqualTo("error:LibrariesMissing")
        assertThat(EngineContract.isError(status)).isTrue()
        assertThat(EngineContract.isError(EngineContract.STATUS_READY)).isFalse()
        assertThat(EngineContract.isError(null)).isFalse()
    }
}
