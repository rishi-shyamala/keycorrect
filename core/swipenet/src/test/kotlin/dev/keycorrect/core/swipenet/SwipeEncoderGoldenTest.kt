package dev.keycorrect.core.swipenet

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.math.abs
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Our ExecuTorch build against the official executorch==1.5.1 wheel (the goldens, written by
 * tools/swipenet/make-goldens.py). Skipped unless `KC_SWIPENET_LIB` points at the host build
 * (`tools/swipenet/build.sh host`) and the model is on disk (`tools/data/fetch-swipe-model.sh`).
 */
class SwipeEncoderGoldenTest {
    @Test
    fun outputsMatchTheReferenceRuntime() {
        var worst = 0f
        for (case in Goldens.cases) {
            val e = encoder.emissions(case.features, case.keys, case.mask)!!
            val diff =
                maxOf(
                    maxAbsDiff(e.logEmissions, case.logEmissions, case.keyCount),
                    maxAbsDiff(e.coefficients, case.coefficients),
                    maxAbsDiff(e.lambda, case.lambda),
                )
            println("golden '%s': max abs diff %.3g".format(case.name, diff))
            worst = maxOf(worst, diff)
        }
        println("golden max abs diff over all cases: %.3g".format(worst))
        assertThat(worst).isAtMost(TOLERANCE)
    }

    @Test
    fun ourPreprocessingGivesTheSameOutputsFromTheRawGesture() {
        for (case in Goldens.cases) {
            val features = SwipeResampler.features(case.x, case.y, case.t)
            val e = encoder.emissions(features, case.keys, case.mask)!!
            assertThat(maxAbsDiff(e.logEmissions, case.logEmissions, case.keyCount))
                .isAtMost(TOLERANCE)
            assertThat(case.greedy(e.logEmissions)).isEqualTo(case.greedy(case.logEmissions))
        }
    }

    @Test
    fun theModelCardsComputerSwipeDecodesToComputer() {
        for (case in Goldens.cases.filter { it.name.startsWith("computer") }) {
            val e = encoder.emissions(SwipeResampler.features(case.x, case.y, case.t), case.keys, case.mask)!!
            assertThat(case.greedy(e.logEmissions)).isEqualTo("computer")
        }
    }

    @Test
    fun callsAreRepeatable() {
        val case = Goldens.cases.first()
        val a = encoder.emissions(case.features, case.keys, case.mask)!!
        val b = encoder.emissions(case.features, case.keys, case.mask)!!
        assertThat(b.logEmissions).isEqualTo(a.logEmissions)
    }

    @Test
    fun aBrokenModelIsRefused() {
        val result = runCatching { SwipeEncoder.open(ByteArray(4096) { it.toByte() }) }
        assertThat(result.exceptionOrNull()).isInstanceOf(SwipeNetException::class.java)
    }

    /** Per-call latency on this machine (relative only; device numbers go in PERF.md). */
    @Test
    fun reportLatency() {
        val case = Goldens.cases.first()
        repeat(WARMUP) { encoder.emissions(case.features, case.keys, case.mask) }
        val ms =
            (1..ROUNDS)
                .map {
                    val start = System.nanoTime()
                    encoder.emissions(case.features, case.keys, case.mask)
                    (System.nanoTime() - start) / 1e6
                }
                .sorted()
        println(
            "swipenet latency (1 thread, desktop): median %.3f ms, p95 %.3f ms"
                .format(ms[ms.size / 2], ms[ms.size * 95 / 100])
        )
    }

    companion object {
        /**
         * Absolute tolerance on every output. Both runtimes run the same int8 XNNPACK graph; what
         * remains is float rounding (1.9e-6 seen on x86_64).
         */
        private const val TOLERANCE = 1e-4f
        private const val WARMUP = 20
        private const val ROUNDS = 200
        private lateinit var encoder: SwipeEncoder

        /** Max |a - b|; for emissions only over the real keys and the blank (padding is -inf). */
        private fun maxAbsDiff(a: FloatArray, b: FloatArray, keys: Int = -1): Float {
            assertThat(a).hasLength(b.size)
            var worst = 0f
            for (i in a.indices) {
                if (keys >= 0) {
                    val cls = i % SwipeEmissions.CLASSES
                    if (cls >= keys && cls != SwipeEmissions.BLANK) {
                        // Padding: both must agree that it is (effectively) impossible.
                        assertThat(a[i] < PADDING_FLOOR == b[i] < PADDING_FLOOR).isTrue()
                        continue
                    }
                }
                worst = maxOf(worst, abs(a[i] - b[i]))
            }
            return worst
        }

        private const val PADDING_FLOOR = -1e4f

        @BeforeClass
        @JvmStatic
        fun open() {
            val lib = System.getProperty("kc.swipenet.lib").orEmpty()
            val model = File(System.getProperty("kc.swipenet.model").orEmpty())
            assumeTrue("KC_SWIPENET_LIB not set", lib.isNotEmpty() && File(lib).isFile)
            assumeTrue("model missing: $model", model.isFile)
            encoder = SwipeEncoder.openDesktop(lib, model)
        }

        @AfterClass
        @JvmStatic
        fun close() {
            if (::encoder.isInitialized) encoder.close()
        }
    }
}
