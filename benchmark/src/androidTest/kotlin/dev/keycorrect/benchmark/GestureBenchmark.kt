package dev.keycorrect.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.keycorrect.core.decoder.ClassicEngine
import dev.keycorrect.core.engineapi.GestureInput
import dev.keycorrect.core.engineapi.GestureRequest
import dev.keycorrect.core.engineapi.TouchPoint
import dev.keycorrect.core.engineapi.TypingContext
import kotlin.math.hypot
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One measured iteration decodes the whole fixed batch of [GestureInputs.requests] (about 60
 * swipes), so the reported time divided by the batch size is the mean cost of one decode on lift.
 * The word list and template index are built before measuring (`warmUpGestures`); the first-ever
 * swipe pays for them otherwise. [decodeCold] measures a single decode on an empty template cache
 * (what the first swipes after startup or a layout change cost), [decodeOnLift] the warmed-up cost.
 */
@RunWith(AndroidJUnit4::class)
class GestureBenchmark {
    @get:Rule val benchmarkRule = BenchmarkRule()

    /** Defeats dead-code elimination of the results. */
    private var sink = 0

    @Test
    fun decodeOnLift() {
        val engine = ClassicEngine(BenchDictionary.dictionary)
        engine.warmUpGestures(BenchKeyboard.layout)
        val requests = GestureInputs.requests
        benchmarkRule.measureRepeated {
            var n = 0
            for (r in requests) n += engine.decodeGestureNow(r).candidates.size
            sink = n
        }
        assertTrue(sink > 0)
    }

    @Test
    fun decodeCold() {
        val requests = GestureInputs.requests
        var i = 0
        lateinit var engine: ClassicEngine
        benchmarkRule.measureRepeated {
            // Fresh engine and index (empty template cache), built with timing paused.
            runWithTimingDisabled {
                engine = ClassicEngine(BenchDictionary.dictionary)
                engine.warmUpGestures(BenchKeyboard.layout)
            }
            sink += engine.decodeGestureNow(requests[i++ % requests.size]).candidates.size
        }
        assertTrue(sink > 0)
    }

    @Test
    fun warmUp() {
        val engine = ClassicEngine(BenchDictionary.dictionary)
        var id = 1000L
        benchmarkRule.measureRepeated {
            // A new layout id forces the index to be rebuilt (the word list stays cached).
            val layout = BenchKeyboard.layout.copy(id = id++)
            engine.warmUpGestures(layout)
        }
    }
}

/** Noisy swipes through the key centres of common words, deterministic. */
object GestureInputs {
    private const val SEED = 20_261_016
    private const val STEP_PX = 8f
    private const val SAMPLE_MS = 10L
    private const val JITTER = 0.25f
    private const val WORDS =
        "the and that have with this they from would there their what about which when make " +
            "like time just know take people into year your good some could them other than then " +
            "think also back after work first well even want because give hello world weekend " +
            "morning friend tomorrow beautiful probably definitely thinking wonderful keyboard"

    private val contexts = listOf("", "thank you for the ", "see you ")

    val requests: List<GestureRequest> by lazy {
        val rnd = Random(SEED)
        WORDS.split(' ').mapIndexed { i, w ->
            GestureRequest(
                TypingContext(contexts[i % contexts.size]),
                swipe(w, rnd),
                BenchKeyboard.layout,
                maxCandidates = 3,
            )
        }
    }

    private fun swipe(word: String, rnd: Random): GestureInput {
        val way = ArrayList<Pair<Float, Float>>()
        var prev = ' '
        for (c in word) {
            val k = BenchKeyboard.key(c) ?: continue
            if (c == prev) continue
            prev = c
            way.add(
                (k.centerX + (rnd.nextFloat() - 0.5f) * 2 * JITTER * k.width) to
                    (k.centerY + (rnd.nextFloat() - 0.5f) * 2 * JITTER * k.width)
            )
        }
        val pts = ArrayList<TouchPoint>()
        var t = 0L
        pts.add(TouchPoint(way[0].first, way[0].second, t))
        for (i in 1 until way.size) {
            val (ax, ay) = way[i - 1]
            val (bx, by) = way[i]
            val steps = maxOf(1, (hypot(bx - ax, by - ay) / STEP_PX).toInt())
            for (s in 1..steps) {
                val f = s.toFloat() / steps
                t += SAMPLE_MS
                pts.add(TouchPoint(ax + f * (bx - ax), ay + f * (by - ay), t))
            }
        }
        return GestureInput(pts)
    }
}
