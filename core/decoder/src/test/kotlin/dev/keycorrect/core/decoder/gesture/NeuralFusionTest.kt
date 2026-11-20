package dev.keycorrect.core.decoder.gesture

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.decoder.ClassicEngine
import dev.keycorrect.core.decoder.NeuralSwipeConfig
import dev.keycorrect.core.decoder.TestKeyboard
import dev.keycorrect.core.engineapi.GestureRequest
import dev.keycorrect.core.engineapi.KeySpec
import dev.keycorrect.core.engineapi.PredictResult
import dev.keycorrect.core.engineapi.TypingContext
import dev.keycorrect.core.swipenet.SwipeEmissions
import dev.keycorrect.core.swipenet.SwipeEncoder
import dev.keycorrect.core.swipenet.SwipeFrame
import dev.keycorrect.core.swipenet.SwipeLayout
import dev.keycorrect.core.swipenet.SwipeNetBackend
import kotlin.math.ln
import org.junit.Test

/**
 * The neural swipe fusion (docs/PHASE10.md, 10a-3) against fake encoders: the fallback to SHARK²
 * alone, and the trie searches over hand-made emissions. No native library needed.
 */
class NeuralFusionTest {
    private val dictionary = GestureTestDictionary.dictionary
    private val words = listOf("keyboard", "word", "hold", "held", "then", "health", "don't")

    private val swipeLayout =
        SwipeLayout.of(
            TestKeyboard.layout.keys.map {
                it.char to
                    SwipeFrame(
                        it.centerX - it.width / 2,
                        it.centerY - it.height / 2,
                        it.centerX + it.width / 2,
                        it.centerY + it.height / 2,
                    )
            }
        )!!

    /** A backend that fails in the way given, or spells [word] when it runs. */
    private class FakeBackend(
        private val mode: Mode,
        private val layout: SwipeLayout? = null,
        private val word: String = "",
    ) : SwipeNetBackend {
        enum class Mode {
            Fails,
            Throws,
            Spells,
        }

        var runs = 0

        override fun open(model: ByteArray, threads: Int) = 1L

        override fun run(
            handle: Long,
            features: FloatArray,
            keys: FloatArray,
            mask: ByteArray,
            out: FloatArray,
        ): Boolean {
            runs++
            return when (mode) {
                Mode.Fails -> false
                Mode.Throws -> error("native failure")
                Mode.Spells -> {
                    spell(word, layout!!).copyInto(out)
                    true
                }
            }
        }

        override fun close(handle: Long) = Unit
    }

    private fun request(word: String, layoutId: Long = TestKeyboard.layout.id) =
        GestureRequest(
            TypingContext(""),
            Swipes.through(word),
            TestKeyboard.layout.copy(id = layoutId),
            maxCandidates = 5,
            sequence = 7,
        )

    private fun decodeAll(
        engine: ClassicEngine,
        layoutKeys: List<KeySpec>? = null,
        layoutId: Long = 42,
    ) = words.map { w ->
        val r = request(w)
        engine.decodeGestureNow(
            if (layoutKeys == null) r
            else r.copy(layout = r.layout.copy(id = layoutId, keys = layoutKeys))
        )
    }

    private fun List<PredictResult>.summary() = map { r ->
        r.candidates.map { it.text to it.score }
    }

    private val shark = decodeAll(ClassicEngine(dictionary)).summary()

    @Test
    fun withoutAnEncoderTheDecodeIsSharkAlone() {
        val engine = ClassicEngine(dictionary, swipeEncoder = { null })
        assertThat(decodeAll(engine).summary()).isEqualTo(shark)
    }

    @Test
    fun aFailingOrThrowingEncoderFallsBackToSharkExactly() {
        for (mode in listOf(FakeBackend.Mode.Fails, FakeBackend.Mode.Throws)) {
            val backend = FakeBackend(mode)
            val encoder = SwipeEncoder.open(ByteArray(1), backend = backend)
            val engine = ClassicEngine(dictionary, swipeEncoder = { encoder })
            assertThat(decodeAll(engine).summary()).isEqualTo(shark)
            assertThat(backend.runs).isEqualTo(words.size)
        }
    }

    @Test
    fun aClosedEncoderFallsBackToSharkExactly() {
        val encoder = SwipeEncoder.open(ByteArray(1), backend = FakeBackend(FakeBackend.Mode.Fails))
        encoder.close()
        val engine = ClassicEngine(dictionary, swipeEncoder = { encoder })
        assertThat(decodeAll(engine).summary()).isEqualTo(shark)
    }

    @Test
    fun aLayoutTheEncoderCantTakeFallsBackToSharkWithoutRunningIt() {
        val backend = FakeBackend(FakeBackend.Mode.Spells, swipeLayout, "held")
        val encoder = SwipeEncoder.open(ByteArray(1), backend = backend)
        val engine = ClassicEngine(dictionary, swipeEncoder = { encoder })
        val keys = TestKeyboard.layout.keys
        // Two keys typing the same letter: SwipeLayout.of refuses the layout.
        val q = keys.first { it.char == 'q' }
        val doubled = keys + q.copy(centerY = q.centerY + 1000f)
        assertThat(decodeAll(engine, doubled).summary())
            .isEqualTo(decodeAll(ClassicEngine(dictionary), doubled).summary())
        // More than 64 letter keys.
        val many =
            keys +
                (0 until SwipeLayout.MAX_KEYS).map {
                    KeySpec('一' + it, 2000f + 10f * it, 2000f, 10f, 10f)
                }
        assertThat(decodeAll(engine, many, 43).summary())
            .isEqualTo(decodeAll(ClassicEngine(dictionary), many, 43).summary())
        assertThat(backend.runs).isEqualTo(0)
    }

    @Test
    fun theEncoderCanOverruleSharkOnAnAmbiguousPath() {
        // A gesture through t-h-e-n, but the encoder clearly says t-h-e-m (n and m are neighbours).
        val backend = FakeBackend(FakeBackend.Mode.Spells, swipeLayout, "them")
        val encoder = SwipeEncoder.open(ByteArray(1), backend = backend)
        val fused = ClassicEngine(dictionary, swipeEncoder = { encoder })
        assertThat(fused.decodeGestureNow(request("then")).candidates.first().text)
            .isEqualTo("them")
        assertThat(
                ClassicEngine(dictionary).decodeGestureNow(request("then")).candidates.first().text
            )
            .isEqualTo("then")
    }

    @Test
    fun theBeamFindsWordsWithRepeatsAndApostrophes() {
        val search = NeuralSearch(dictionary, NeuralSwipeConfig())
        val classes = KeyClasses(swipeLayout)
        for (word in listOf("held", "keen", "hollow", "don't", "world's", "keyboard")) {
            val e = NeuralEmissions(spell(word, swipeLayout).copyOf(EMISSIONS), classes)
            val out = arrayOfNulls<String>(4)
            val n = search.beam(e, out) { false }
            assertThat(n).isAtLeast(1)
            assertThat(out[0]).isEqualTo(word)
            // The beam's best word scores what an exact alignment of it scores.
            assertThat(search.align(word, e)).isGreaterThan(search.align("hold", e))
        }
    }

    @Test
    fun alignmentRejectsWordsOffTheLayoutOrTooLong() {
        val search = NeuralSearch(dictionary, NeuralSwipeConfig())
        val e =
            NeuralEmissions(spell("held", swipeLayout).copyOf(EMISSIONS), KeyClasses(swipeLayout))
        assertThat(search.align("hé", e)).isNegativeInfinity()
        assertThat(search.align("abcdefghijklmnopqrstuvwxyzabcdefghij", e)).isNegativeInfinity()
        assertThat(search.align("held", e)).isGreaterThan(ln(0.5f) * 8)
    }

    @Test
    fun aStaleBeamSearchGivesUp() {
        val search = NeuralSearch(dictionary, NeuralSwipeConfig())
        val e =
            NeuralEmissions(spell("held", swipeLayout).copyOf(EMISSIONS), KeyClasses(swipeLayout))
        assertThat(search.beam(e, arrayOfNulls(4)) { true }).isEqualTo(-1)
    }

    private companion object {
        const val EMISSIONS = SwipeEmissions.STEPS * SwipeEmissions.CLASSES

        /**
         * Encoder output (all three tensors) that spells [word]: each of its keys (repeats
         * collapsed, apostrophes skipped) peaks at one step, evenly spread; blank elsewhere.
         */
        fun spell(word: String, layout: SwipeLayout): FloatArray {
            val keys = ArrayList<Int>()
            for (c in word) {
                if (!c.isLetter()) continue
                val k = layout.chars.indexOf(c)
                if (keys.isEmpty() || keys.last() != k) keys += k
            }
            val out = FloatArray(SwipeEmissions.SIZE)
            val steps = SwipeEmissions.STEPS
            val classes = SwipeEmissions.CLASSES
            val low = ln(0.01f / classes)
            val peakAt = keys.indices.associateBy { 1 + it * (steps - 2) / keys.size }
            for (t in 0 until steps) {
                val k = peakAt[t]?.let { keys[it] } ?: SwipeEmissions.BLANK
                for (c in 0 until classes) out[t * classes + c] = if (c == k) ln(0.99f) else low
            }
            return out
        }
    }
}
