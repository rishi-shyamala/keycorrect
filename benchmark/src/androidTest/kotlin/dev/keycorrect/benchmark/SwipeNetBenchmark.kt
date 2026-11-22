package dev.keycorrect.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.keycorrect.core.decoder.ClassicEngine
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.layout.KeyboardGeometry
import dev.keycorrect.core.layout.KeyboardSet
import dev.keycorrect.core.packs.SwipePackLoader
import dev.keycorrect.core.packs.SwipePackResult
import dev.keycorrect.core.swipenet.SwipeEncoder
import dev.keycorrect.core.swipenet.SwipeLayout
import dev.keycorrect.core.swipenet.SwipeResampler
import org.junit.AfterClass
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The cost of FUTO Swipe's encoder (docs/PHASE10.md, 10a-2 and 10a-3). [emissionsOneThread] is one
 * [SwipeEncoder.emissions] call on a QWERTY gesture. [fusedDecodeOnLift] is one whole swipe decode
 * on lift with the encoder fused into SHARK² (encoder, beam search, rescoring), and
 * [sharkDecodeOnLift] the same swipes without the encoder: each iteration decodes the next of
 * [GestureInputs.requests], so the reported times are per swipe (each run averages the swipes of
 * its iterations; the desktop eval gives the per-swipe distribution). Needs the swipe pack signed
 * with the same key as this test APK (the debug key: `./gradlew :swipepack:installDebug`); skipped
 * when it is missing.
 */
@RunWith(AndroidJUnit4::class)
class SwipeNetBenchmark {
    @get:Rule val benchmarkRule = BenchmarkRule()

    @Test
    fun emissionsOneThread() {
        val features = features()
        var sink = 0f
        benchmarkRule.measureRepeated {
            sink += encoder.emissions(features, layout)!!.lambda[0]
        }
        assertNotNull(sink)
    }

    @Test
    fun fusedDecodeOnLift() {
        decodeOnLift(ClassicEngine(BenchDictionary.dictionary, swipeEncoder = { encoder }))
    }

    @Test
    fun sharkDecodeOnLift() {
        decodeOnLift(ClassicEngine(BenchDictionary.dictionary))
    }

    private fun decodeOnLift(engine: ClassicEngine) {
        engine.warmUpGestures(BenchKeyboard.layout)
        val requests = GestureInputs.requests
        // Fills the template cache and warms the JIT, as a keyboard in use would be.
        for (r in requests) engine.decodeGestureNow(r)
        var i = 0
        var n = 0
        benchmarkRule.measureRepeated {
            n += engine.decodeGestureNow(requests[i++ % requests.size]).candidates.size
        }
        assertTrue(n > 0)
    }

    /** "computer" traced key centre to key centre, 8 points (16 ms apart) per segment. */
    private fun features(): FloatArray {
        val keys = "computer".map { layout.chars.indexOf(it) }
        val x = ArrayList<Float>()
        val y = ArrayList<Float>()
        for ((a, b) in keys.zipWithNext()) {
            for (step in 0 until SEGMENT) {
                val f = step / SEGMENT.toFloat()
                x += layout.keys[2 * a] + f * (layout.keys[2 * b] - layout.keys[2 * a])
                y += layout.keys[2 * a + 1] + f * (layout.keys[2 * b + 1] - layout.keys[2 * a + 1])
            }
        }
        val t = FloatArray(x.size) { it * 16f }
        return SwipeResampler.features(x.toFloatArray(), y.toFloatArray(), t)
    }

    companion object {
        private lateinit var encoder: SwipeEncoder
        private lateinit var layout: SwipeLayout
        private const val SEGMENT = 8

        @BeforeClass
        @JvmStatic
        fun open() {
            val context = InstrumentationRegistry.getInstrumentation().context
            val result = SwipePackLoader(context).open(threads = 1)
            assumeTrue("swipe pack unavailable: $result", result is SwipePackResult.Available)
            encoder = (result as SwipePackResult.Available).encoder
            val geometry =
                KeyboardGeometry(
                    KeyboardComposer(BuiltInLayouts.repository())
                        .compose(KeyboardSet.Default, KeyboardSet.LETTERS),
                    1080f,
                    900f,
                )
            layout = SwipeLayout.of(geometry)!!
        }

        @AfterClass
        @JvmStatic
        fun close() {
            if (::encoder.isInitialized) encoder.close()
        }
    }
}
