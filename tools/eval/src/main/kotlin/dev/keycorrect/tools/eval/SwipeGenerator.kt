@file:Suppress("MagicNumber")

package dev.keycorrect.tools.eval

import dev.keycorrect.core.engineapi.GestureInput
import dev.keycorrect.core.engineapi.KeySpec
import dev.keycorrect.core.engineapi.TouchPoint
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sin

/** Knobs of the synthetic swiper (docs/EVAL.md, "Swipe mode"). */
data class SwipeConfig(
    /** Gaussian jitter of every letter's control point, in key widths (x) and key heights (y). */
    val noise: Float = 0.25f,
    /** Touch sampling interval; 10 ms is 100 Hz. */
    val sampleIntervalMs: Int = 10,
    /** Distance the finger runs on past the last letter, in key widths. */
    val overshoot: Float = 0.25f,
    /** Mean finger speed along the path, in key widths per second. */
    val speed: Float = 14f,
    /** Floor for the duration of one segment between two letters. */
    val minSegmentMs: Float = 40f,
    /** Length of the drag that stands in for a swipe over a single key, in key widths. */
    val dragLength: Float = 0.5f,
)

/**
 * Generates synthetic gestures for words on an [EvalKeyboard]: the control points are the letters'
 * key centres (consecutive duplicates collapsed, so "hello" is h-e-l-o), each jittered by Gaussian
 * noise; a Catmull-Rom spline runs through them (so the finger rounds the corners between letters)
 * and a little past the last one; it is sampled by time at [SwipeConfig.sampleIntervalMs] with a
 * speed that is highest in the middle of every segment and lowest at the letters. Words whose
 * letters collapse to one key produce a short drag instead.
 *
 * Deterministic given the [Random] passed to [generate]; the caller seeds one per word.
 */
class SwipeGenerator(private val keyboard: EvalKeyboard, val config: SwipeConfig = SwipeConfig()) {
    private val keyWidth: Double = keyboard.layout.keys.map { it.width }.average()

    /**
     * True when [word] is letters (apostrophes allowed) that all have a key on the letters layer.
     */
    fun canSwipe(word: String): Boolean {
        val letters = word.filterNot(::isApostrophe)
        return letters.isNotEmpty() && letters.all { keyboard.hasKey(it) }
    }

    /** The keys the path goes through, in order, with consecutive duplicates collapsed. */
    fun keysOf(word: String): List<KeySpec> {
        require(canSwipe(word)) { "cannot swipe '$word'" }
        val keys = ArrayList<KeySpec>(word.length)
        for (c in word) {
            if (isApostrophe(c)) continue
            val key = (keyboard.key(c) ?: error("no key for $c"))
            if (keys.isEmpty() || keys.last().char.lowercaseChar() != key.char.lowercaseChar())
                keys.add(key)
        }
        return keys
    }

    /**
     * The jittered control points of [word] as (x, y) pairs; exactly the key centres at noise 0.
     */
    internal fun controlPoints(word: String, rng: Random): List<DoubleArray> =
        keysOf(word).map { key ->
            doubleArrayOf(
                key.centerX + rng.nextGaussian() * config.noise * key.width,
                key.centerY + rng.nextGaussian() * config.noise * key.height,
            )
        }

    fun generate(word: String, rng: Random): GestureInput {
        val points = controlPoints(word, rng)
        val speed = config.speed * exp(rng.nextGaussian() * SPEED_SPREAD).toFloat()
        return if (points.size == 1) drag(points[0], rng) else sweep(points, speed)
    }

    private fun drag(start: DoubleArray, rng: Random): GestureInput {
        val angle = rng.nextDouble() * 2 * PI
        val length = config.dragLength * keyWidth
        val steps = DRAG_MS / config.sampleIntervalMs
        val out = ArrayList<TouchPoint>(steps + 1)
        for (i in 0..steps) {
            val f = ease(i.toDouble() / steps) * length
            out.add(
                TouchPoint(
                    (start[0] + cos(angle) * f).toFloat(),
                    (start[1] + sin(angle) * f).toFloat(),
                    (i * config.sampleIntervalMs).toLong(),
                )
            )
        }
        return GestureInput(out)
    }

    private fun sweep(controls: List<DoubleArray>, speed: Float): GestureInput {
        val n = controls.size
        // Run on past the last letter, in the direction of the last segment.
        val a = controls[n - 2]
        val b = controls[n - 1]
        val d = hypot(b[0] - a[0], b[1] - a[1])
        val path = ArrayList(controls)
        if (d > EPS && config.overshoot > 0f) {
            val k = config.overshoot * keyWidth / d
            path.add(doubleArrayOf(b[0] + (b[0] - a[0]) * k, b[1] + (b[1] - a[1]) * k))
        }
        val segments = path.size - 1
        val starts = DoubleArray(segments + 1)
        for (i in 0 until segments) {
            val len = hypot(path[i + 1][0] - path[i][0], path[i + 1][1] - path[i][1])
            val ms = max(config.minSegmentMs.toDouble(), len / (speed * keyWidth) * 1000.0)
            starts[i + 1] = starts[i] + ms
        }
        val total = starts[segments]
        val out = ArrayList<TouchPoint>()
        var segment = 0
        var k = 0
        while (k * config.sampleIntervalMs < total) {
            val t = (k * config.sampleIntervalMs).toDouble()
            while (segment < segments - 1 && t >= starts[segment + 1]) segment++
            val tau = (t - starts[segment]) / (starts[segment + 1] - starts[segment])
            val p = catmullRom(path, segment, ease(tau))
            out.add(TouchPoint(p[0].toFloat(), p[1].toFloat(), t.toLong()))
            k++
        }
        val end = path.last()
        val endTime = max(total.roundToLong(), out.last().timeMillis + 1)
        out.add(TouchPoint(end[0].toFloat(), end[1].toFloat(), endTime))
        return GestureInput(out)
    }

    /** Uniform Catmull-Rom through [path] on segment [i] at parameter [u]; ends are reflected. */
    private fun catmullRom(path: List<DoubleArray>, i: Int, u: Double): DoubleArray {
        val p1 = path[i]
        val p2 = path[i + 1]
        val p0 = if (i > 0) path[i - 1] else doubleArrayOf(2 * p1[0] - p2[0], 2 * p1[1] - p2[1])
        val p3 =
            if (i + 2 < path.size) path[i + 2]
            else doubleArrayOf(2 * p2[0] - p1[0], 2 * p2[1] - p1[1])
        val u2 = u * u
        val u3 = u2 * u
        return DoubleArray(2) { c ->
            0.5 *
                (2 * p1[c] +
                    (-p0[c] + p2[c]) * u +
                    (2 * p0[c] - 5 * p1[c] + 4 * p2[c] - p3[c]) * u2 +
                    (-p0[c] + 3 * p1[c] - 3 * p2[c] + p3[c]) * u3)
        }
    }

    /** Monotonic 0..1 map that is slow near 0 and 1 and fast in the middle (a smoothstep mix). */
    private fun ease(tau: Double): Double {
        val t = tau.coerceIn(0.0, 1.0)
        return (1 - EASE) * t + EASE * t * t * (3 - 2 * t)
    }

    private fun isApostrophe(c: Char) = c == '\'' || c == '’' || c == 'ʼ'

    private companion object {
        const val EPS = 1e-6
        const val EASE = 0.6
        const val SPEED_SPREAD = 0.2
        const val DRAG_MS = 100
    }
}
