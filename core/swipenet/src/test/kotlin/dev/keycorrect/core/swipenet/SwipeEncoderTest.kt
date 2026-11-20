package dev.keycorrect.core.swipenet

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** The wrapper's rules, against a fake backend: no native library needed. */
class SwipeEncoderTest {
    private class FakeBackend(var handle: Long = 7L, var ok: Boolean = true) : SwipeNetBackend {
        val closed = mutableListOf<Long>()
        var threads = -1

        override fun open(model: ByteArray, threads: Int): Long {
            this.threads = threads
            return handle
        }

        override fun run(handle: Long, features: FloatArray, keys: FloatArray, mask: ByteArray, out: FloatArray): Boolean {
            for (i in out.indices) out[i] = i.toFloat()
            return ok
        }

        override fun close(handle: Long) {
            closed += handle
        }
    }

    private val layout = SwipeLayout(SwipeFrame(0f, 0f, 1f, 1f), listOf('a'), listOf(0.5f to 0.5f))
    private val features = FloatArray(2 * SwipeResampler.POINTS)

    @Test
    fun theOutputIsSplitIntoTheThreeTensors() {
        val encoder = SwipeEncoder.open(ByteArray(1), backend = FakeBackend())
        val e = encoder.emissions(features, layout)!!
        val emissions = SwipeEmissions.STEPS * SwipeEmissions.CLASSES
        val coefficients = SwipeEmissions.STEPS * SwipeEmissions.COEFFICIENTS
        assertThat(e.logEmissions).hasLength(emissions)
        assertThat(e.coefficients).hasLength(coefficients)
        assertThat(e.lambda).hasLength(SwipeEmissions.STEPS)
        assertThat(e.coefficients[0]).isEqualTo(emissions.toFloat())
        assertThat(e.lambda[0]).isEqualTo((emissions + coefficients).toFloat())
        assertThat(e.logProb(1, SwipeEmissions.BLANK)).isEqualTo((65 + 64).toFloat())
    }

    @Test
    fun oneThreadByDefault() {
        val backend = FakeBackend()
        SwipeEncoder.open(ByteArray(1), backend = backend)
        assertThat(backend.threads).isEqualTo(1)
    }

    @Test
    fun aFailedOpenThrowsAndAFailedRunGivesNull() {
        assertThrows(SwipeNetException::class.java) {
            SwipeEncoder.open(ByteArray(1), backend = FakeBackend(handle = 0L))
        }
        val encoder = SwipeEncoder.open(ByteArray(1), backend = FakeBackend(ok = false))
        assertThat(encoder.emissions(features, layout)).isNull()
    }

    @Test
    fun closeFreesOnceAndLaterCallsGiveNull() {
        val backend = FakeBackend()
        val encoder = SwipeEncoder.open(ByteArray(1), backend = backend)
        encoder.close()
        encoder.close()
        assertThat(backend.closed).containsExactly(7L)
        assertThat(encoder.emissions(features, layout)).isNull()
    }

    @Test
    fun wrongInputSizesAreRefused() {
        val encoder = SwipeEncoder.open(ByteArray(1), backend = FakeBackend())
        assertThrows(IllegalArgumentException::class.java) {
            encoder.emissions(FloatArray(3), layout)
        }
        assertThrows(IllegalArgumentException::class.java) {
            encoder.emissions(features, FloatArray(3), ByteArray(SwipeLayout.MAX_KEYS))
        }
    }
}
