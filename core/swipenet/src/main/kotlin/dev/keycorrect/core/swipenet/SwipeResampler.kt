// Ported from FUTO's swipe-library, src/resampler.cpp at commit e8ade0e0
// (https://gitlab.futo.org/keyboard/swipe-library), Copyright FUTO, GPL-3.0; see
// THIRD_PARTY_NOTICES.md. Only the input resampler is taken (docs/DECISIONS.md N-7); it must match
// the encoder's training-time preprocessing exactly, so the arithmetic follows the original
// (float32, the same rounding and search) rather than being rewritten.
package dev.keycorrect.core.swipenet

import kotlin.math.roundToLong

/**
 * Turns a gesture into the encoder's `features` input: the points, already in the encoder's frame
 * ([SwipeFrame]), are resampled uniformly in time to 60 Hz, then by index to [POINTS] points, and
 * clamped to [0, 1]. The result holds all [POINTS] x values, then all y values.
 */
object SwipeResampler {
    /** Points per gesture the encoder takes. */
    const val POINTS = 64

    private const val INTERVAL_MS = 1000f / 60f
    private const val EPS = 1e-12f

    /**
     * The most 60 Hz samples one gesture yields: 10 s of gesture. A longer gesture is resampled at
     * a lower rate over its whole duration (not clipped), so the allocation stays bounded; gestures
     * up to 10 s are unaffected.
     */
    internal const val MAX_SAMPLES = 601

    /**
     * The encoder features for a gesture with coordinates [x] and [y] (in the encoder's frame) and
     * timestamps [t] in milliseconds relative to the gesture's first point (absolute uptimes lose
     * float precision, so callers should subtract the origin; [t]`[0]` is subtracted here anyway),
     * non-decreasing. Returns `2 * POINTS` floats; all zeros for an empty gesture.
     */
    fun features(x: FloatArray, y: FloatArray, t: FloatArray): FloatArray {
        require(x.size == y.size && x.size == t.size) { "x, y and t differ in length" }
        val origin = if (t.isEmpty()) 0f else t[0]
        val (rx, ry) = to60Hz(x, y, FloatArray(t.size) { t[it] - origin })
        return toPoints(rx, ry)
    }

    /**
     * Resamples uniformly in time to 60 Hz: `max(2, round(duration / 16.667 ms) + 1)` points over
     * `linspace(0, duration)` (at most [MAX_SAMPLES] points), linearly interpolated. One point is
     * doubled; a gesture without duration keeps its first and last points.
     */
    internal fun to60Hz(x: FloatArray, y: FloatArray, t: FloatArray): Pair<FloatArray, FloatArray> {
        val n = x.size
        return when {
            n == 0 -> FloatArray(0) to FloatArray(0)
            n == 1 -> floatArrayOf(x[0], x[0]) to floatArrayOf(y[0], y[0])
            t[n - 1] - t[0] <= EPS -> floatArrayOf(x[0], x[n - 1]) to floatArrayOf(y[0], y[n - 1])
            else -> uniformInTime(x, y, t)
        }
    }

    private fun uniformInTime(
        x: FloatArray,
        y: FloatArray,
        t: FloatArray,
    ): Pair<FloatArray, FloatArray> {
        val n = x.size
        val t0 = t[0]
        val duration = t[n - 1] - t0
        // std::round rounds halves away from zero; the duration is positive, so roundToLong agrees.
        val count =
            (duration / INTERVAL_MS).roundToLong().coerceIn(1L, MAX_SAMPLES - 1L).toInt() + 1
        val outX = FloatArray(count)
        val outY = FloatArray(count)
        val step = duration / (count - 1).toFloat()
        for (i in 0 until count) {
            // Round-off may put the last target a hair past the last timestamp.
            val target = minOf(t0 + i.toFloat() * step, t[n - 1])
            val idx1 = lowerBound(t, target)
            when {
                idx1 == 0 -> {
                    outX[i] = x[0]
                    outY[i] = y[0]
                }
                idx1 >= n -> {
                    outX[i] = x[n - 1]
                    outY[i] = y[n - 1]
                }
                else -> {
                    val idx0 = idx1 - 1
                    val seg = t[idx1] - t[idx0]
                    val frac = if (seg > EPS) (target - t[idx0]) / seg else 0f
                    outX[i] = lerp(x[idx0], x[idx1], frac)
                    outY[i] = lerp(y[idx0], y[idx1], frac)
                }
            }
        }
        return outX to outY
    }

    /**
     * Resamples by index (the input is evenly spaced in time) to [POINTS] points, clamped to
     * [0, 1], as `[x0..x63, y0..y63]`; all zeros without points. (The original special-cases one
     * point; the loop below gives the same result for it.)
     */
    internal fun toPoints(x: FloatArray, y: FloatArray): FloatArray {
        val n = x.size
        val out = FloatArray(2 * POINTS)
        if (n > 0) {
            for (i in 0 until POINTS) {
                val fracPos = i.toFloat() / (POINTS - 1).toFloat()
                val idx = fracPos * (n - 1).toFloat()
                val idx0 = idx.toInt()
                val idx1 = minOf(idx0 + 1, n - 1)
                val frac = idx - idx0.toFloat()
                out[i] = lerp(x[idx0], x[idx1], frac).coerceIn(0f, 1f)
                out[POINTS + i] = lerp(y[idx0], y[idx1], frac).coerceIn(0f, 1f)
            }
        }
        return out
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + t * (b - a)

    /** The first index whose value is not below [value] (std::lower_bound); size if none. */
    private fun lowerBound(sorted: FloatArray, value: Float): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < value) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
