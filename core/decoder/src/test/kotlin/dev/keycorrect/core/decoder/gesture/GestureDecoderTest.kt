package dev.keycorrect.core.decoder.gesture

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.decoder.ClassicConfig
import dev.keycorrect.core.decoder.ClassicEngine
import dev.keycorrect.core.decoder.GestureConfig
import dev.keycorrect.core.decoder.TestKeyboard
import dev.keycorrect.core.engineapi.Capability
import dev.keycorrect.core.engineapi.GestureInput
import dev.keycorrect.core.engineapi.GestureRequest
import dev.keycorrect.core.engineapi.TouchPoint
import dev.keycorrect.core.engineapi.TypingContext
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Test

class GestureDecoderTest {
    private val dictionary = GestureTestDictionary.dictionary
    private val engine = ClassicEngine(dictionary)

    private fun decode(
        gesture: GestureInput,
        before: String = "",
        max: Int = 3,
        engine: ClassicEngine = this.engine,
        stale: () -> Boolean = { false },
    ) =
        engine.decodeGestureNow(
            GestureRequest(TypingContext(before), gesture, TestKeyboard.layout, max, 7),
            stale,
        )

    private fun top(
        word: String,
        seed: Long = 1,
        jitter: Float = 0f,
        noise: Float = 0f,
        n: Int = 3,
    ) = decode(Swipes.through(word, seed, jitter, noise), max = n).candidates.map { it.text }

    // ------------------------------------------------------------ geometry

    @Test
    fun resamplingIsEquidistantAndKeepsEndpoints() {
        val src = floatArrayOf(0f, 0f, 30f, 0f, 30f, 40f, 100f, 40f) // 30 + 40 + 70 = 140
        val out = FloatArray(2 * 8)
        PathMath.resample(src, 4, out, 8)
        assertThat(out[0]).isEqualTo(0f)
        assertThat(out[1]).isEqualTo(0f)
        assertThat(out[14]).isEqualTo(100f)
        assertThat(out[15]).isEqualTo(40f)
        // Equal spacing along the path (not nec. in a straight line): 20 px apart.
        var along = 0f
        for (i in 1 until 8) {
            val d = hypot(out[2 * i] - out[2 * i - 2], out[2 * i + 1] - out[2 * i - 1])
            along += d
        }
        assertThat(PathMath.length(out, 8)).isWithin(1f).of(along)
        // Points sit at path distances 0, 20, 40, ...: check the first segment points.
        assertThat(out[2]).isWithin(1e-3f).of(20f)
        assertThat(out[3]).isWithin(1e-3f).of(0f)
        assertThat(out[4]).isWithin(1e-3f).of(30f)
        assertThat(out[5]).isWithin(1e-3f).of(10f)
    }

    @Test
    fun resamplingDegeneratePathRepeatsThePoint() {
        val out = FloatArray(2 * 5)
        PathMath.resample(floatArrayOf(3f, 4f, 3f, 4f), 2, out, 5)
        for (i in 0 until 5) {
            assertThat(out[2 * i]).isEqualTo(3f)
            assertThat(out[2 * i + 1]).isEqualTo(4f)
        }
    }

    @Test
    fun normalizeCentresAndScalesToUnitBox() {
        val src = floatArrayOf(10f, 10f, 30f, 10f, 30f, 20f)
        val out = FloatArray(6)
        assertThat(PathMath.normalize(src, 3, out)).isTrue()
        val xs = listOf(out[0], out[2], out[4])
        assertThat(xs.max() - xs.min()).isWithin(1e-5f).of(1f)
        assertThat((out[0] + out[2] + out[4])).isWithin(1e-5f).of(0f)
    }

    // ------------------------------------------------------------ templates

    @Test
    fun templateCollapsesDoubledLetters() {
        val index = GestureDecoder(dictionary, GestureConfig(), -7f).indexFor(TestKeyboard.layout)
        val seq = ByteArray(16)
        val len = index.sequence("hello", seq)
        assertThat(len).isEqualTo(4) // h e l o
        val chars = (0 until len).map { TestKeyboard.layout.keys[seq[it].toInt()].char }
        assertThat(chars.joinToString("")).isEqualTo("helo")
        // Apostrophes are ignored, case too; a char off the layout makes the word unusable.
        assertThat(index.sequence("Don't", seq)).isEqualTo(4)
        assertThat(index.sequence("café", seq)).isEqualTo(0)
        val t = index.templateOfSequence(seq.also { index.sequence("hello", it) }, 4)
        assertThat(t.pts.size).isEqualTo(2 * 64)
        val h = TestKeyboard.layout.keys.first { it.char == 'h' }
        val o = TestKeyboard.layout.keys.first { it.char == 'o' }
        assertThat(t.pts[0]).isEqualTo(h.centerX)
        assertThat(t.pts[127]).isEqualTo(o.centerY)
    }

    // ------------------------------------------------------------ decoding

    @Test
    fun perfectGesturesDecodeToTheWordAtTop1() {
        for (w in listOf("hello", "the", "world", "keyboard", "tomorrow", "thank", "friend")) {
            assertThat(top(w, n = 1)).containsExactly(w)
        }
    }

    @Test
    fun capabilityIsAdvertised() {
        assertThat(engine.info.capabilities).contains(Capability.Gesture)
    }

    @Test
    fun resultShapeFollowsTheContract() {
        val r = decode(Swipes.through("hello"))
        assertThat(r.literal).isNull()
        assertThat(r.autocorrect).isEqualTo(r.candidates.first())
        assertThat(r.sequence).isEqualTo(7)
        assertThat(r.candidates.map { it.score }).isInOrder(Comparator.reverseOrder<Float>())
    }

    @Test
    fun noisyGesturesStayInTheTop3() {
        val words =
            listOf("hello", "the", "world", "keyboard", "tomorrow", "thank", "friend", "please")
        var hits = 0
        var total = 0
        for (w in words) {
            for (seed in 1L..10L) {
                total++
                if (w in top(w, seed, jitter = 0.25f, noise = 0.08f)) hits++
            }
        }
        println("noisy top-3: $hits / $total")
        assertThat(hits.toDouble() / total).isAtLeast(0.9)
    }

    @Test
    fun contextBreaksNearTies() {
        // "there" and "three" ... use the corpus: after "put it over" -> "there".
        val g = Swipes.through("there", 3, jitter = 0.3f)
        val r = decode(g, before = "put it over ")
        assertThat(r.candidates.first().text).isEqualTo("there")
    }

    @Test
    fun endpointPruningExcludesImpossibleWords() {
        // A gesture from t to e must not offer words starting or ending elsewhere.
        val words = top("the", n = 10)
        assertThat(words).isNotEmpty()
        for (w in words) {
            assertThat(w.first()).isAnyOf('t', 'r', 'y', 'f', 'g', '5', 'd')
            assertThat(w.last()).isAnyOf('e', 'w', 'r', 's', 'd', 'f', '3')
        }
        assertThat(words).doesNotContain("hello")
    }

    @Test
    fun lengthRatioPrunesWordsOfTheWrongSize() {
        // A long path from t to e (twice the length of "the" and more) cannot be "the" or "tee".
        val index = GestureDecoder(dictionary, GestureConfig(), -7f).indexFor(TestKeyboard.layout)
        assertThat(index).isNotNull()
        val short = Swipes.through("te")
        val longPath = Swipes.through("tpoiuyte")
        val a = decode(short, max = 10).candidates.map { it.text }
        val b = decode(longPath, max = 10).candidates.map { it.text }
        assertThat(b).doesNotContain("the")
        assertThat(a).isNotEmpty()
    }

    @Test
    fun staleAbandonsTheDecode() {
        val r = decode(Swipes.through("hello"), stale = { true })
        assertThat(r.candidates).isEmpty()
        assertThat(r.autocorrect).isNull()
        var calls = 0
        val later = decode(Swipes.through("keyboard")) { ++calls > 3 }
        assertThat(later.candidates).isEmpty()
        assertThat(calls).isGreaterThan(3)
    }

    @Test
    fun tinyAndEmptyGesturesReturnNothing() {
        val k = TestKeyboard.layout.keys.first { it.char == 'h' }
        val dot =
            GestureInput(
                listOf(
                    TouchPoint(k.centerX, k.centerY, 0),
                    TouchPoint(k.centerX + 1f, k.centerY, 5),
                )
            )
        assertThat(decode(dot).candidates).isEmpty()
        val same = GestureInput(listOf(TouchPoint(5f, 5f, 0), TouchPoint(5f, 5f, 5)))
        assertThat(decode(same).candidates).isEmpty()
        // Far from every key: no start key within reach.
        val away =
            GestureInput(listOf(TouchPoint(-5000f, -5000f, 0), TouchPoint(-4000f, -5000f, 5)))
        assertThat(decode(away).candidates).isEmpty()
        val noKeys =
            engine.decodeGestureNow(
                GestureRequest(
                    TypingContext(),
                    Swipes.through("hello"),
                    TestKeyboard.layout.copy(id = 99, keys = emptyList()),
                    3,
                )
            )
        assertThat(noKeys.candidates).isEmpty()
    }

    @Test
    fun userWordsCanBeSwiped() {
        val e = ClassicEngine(dictionary, userWords = { listOf("Zorblax") })
        val r = decode(Swipes.through("zorblax"), engine = e, max = 3)
        assertThat(r.candidates.map { it.text }).contains("Zorblax")
        assertThat(r.candidates.first().source.name).isEqualTo("UserDictionary")
    }

    @Test
    fun layoutChangesGetTheirOwnIndex() {
        val shifted =
            TestKeyboard.layout.copy(
                id = 2,
                keys = TestKeyboard.layout.keys.map { it.copy(centerX = it.centerX + 40f) },
            )
        val g = GestureInput(Swipes.through("hello").points.map { it.copy(x = it.x + 40f) })
        val r = engine.decodeGestureNow(GestureRequest(TypingContext(), g, shifted, 1))
        assertThat(r.candidates.map { it.text }).containsExactly("hello")
        assertThat(decode(Swipes.through("hello"), max = 1).candidates.map { it.text })
            .containsExactly("hello")
    }

    @Test
    fun decodeLatencyIsReasonable() {
        val words =
            listOf(
                "hello",
                "the",
                "world",
                "keyboard",
                "tomorrow",
                "thank",
                "friend",
                "please",
                "which",
            )
        val gestures = words.flatMap { w -> (1L..6L).map { Swipes.through(w, it, 0.25f, 0.08f) } }
        engine.warmUpGestures(TestKeyboard.layout)
        repeat(3) { for (g in gestures) decode(g) }
        val times = ArrayList<Long>()
        for (g in gestures) {
            val t0 = System.nanoTime()
            decode(g)
            times.add(System.nanoTime() - t0)
        }
        times.sort()
        val p50 = times[times.size / 2] / 1e6
        val p95 = times[(times.size * 95) / 100] / 1e6
        println("gesture decode (test dictionary): p50=%.2f ms p95=%.2f ms".format(p50, p95))
        assertThat(p95).isLessThan(50.0)
        assertThat(abs(p50)).isAtLeast(0.0)
    }

    @Test
    fun decodeLatencyWithAFullSizedDictionary() {
        val e = ClassicEngine(GestureTestDictionary.large)
        val w0 = System.nanoTime()
        e.warmUpGestures(TestKeyboard.layout)
        println("warm-up (60k words, cold JIT): %.0f ms".format((System.nanoTime() - w0) / 1e6))
        val words =
            listOf(
                "hello",
                "the",
                "world",
                "keyboard",
                "tomorrow",
                "thank",
                "friend",
                "please",
                "which",
            )
        val gestures = words.flatMap { w -> (1L..6L).map { Swipes.through(w, it, 0.25f, 0.08f) } }
        repeat(5) { for (g in gestures) decode(g, engine = e) }
        val times = ArrayList<Long>()
        var hits = 0
        repeat(5) {
            for ((i, g) in gestures.withIndex()) {
                val t0 = System.nanoTime()
                val r = decode(g, engine = e)
                times.add(System.nanoTime() - t0)
                if (r.candidates.firstOrNull()?.text == words[i / 6]) hits++
            }
        }
        times.sort()
        println(
            "gesture decode (60k words): p50=%.2f ms p95=%.2f ms max=%.2f ms top1=%d/%d"
                .format(
                    times[times.size / 2] / 1e6,
                    times[(times.size * 95) / 100] / 1e6,
                    times.last() / 1e6,
                    hits,
                    times.size,
                )
        )
        assertThat(times[(times.size * 95) / 100] / 1e6).isLessThan(30.0)
    }

    @Test
    fun configIsHonoured() {
        val cfg = ClassicConfig(gesture = GestureConfig(shortlistSize = 4, maxWords = 40))
        val e = ClassicEngine(dictionary, cfg)
        val r = decode(Swipes.through("keyboard"), engine = e, max = 5)
        // "keyboard" is not among the 40 most frequent words.
        assertThat(r.candidates.map { it.text }).doesNotContain("keyboard")
    }
}
