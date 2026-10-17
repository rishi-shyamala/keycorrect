package dev.keycorrect.core.decoder.gesture

import kotlin.math.hypot
import kotlin.math.max

/**
 * Polyline helpers for the gesture decoder. Points are interleaved in a FloatArray (x at 2i, y at
 * 2i + 1); nothing here allocates.
 */
internal object PathMath {
    private const val EPS = 1e-6f

    /** Length of the first [count] points of [xy] as a polyline. */
    fun length(xy: FloatArray, count: Int): Float {
        var sum = 0f
        for (i in 1 until count) {
            sum += hypot(xy[2 * i] - xy[2 * i - 2], xy[2 * i + 1] - xy[2 * i - 1])
        }
        return sum
    }

    /**
     * Resamples the polyline of the first [count] points of [src] to [n] equidistant points in
     * [out] (size 2n). The first and last points are kept exactly. A zero-length polyline gives n
     * copies of its first point. Requires count >= 1 and n >= 2.
     */
    fun resample(src: FloatArray, count: Int, out: FloatArray, n: Int) {
        val total = length(src, count)
        if (count < 2 || total <= 0f) {
            for (i in 0 until n) {
                out[2 * i] = src[0]
                out[2 * i + 1] = src[1]
            }
            return
        }
        val step = total / (n - 1)
        var seg = 1
        var segStart = 0f // distance along the path at src point seg - 1
        var segLen = hypot(src[2] - src[0], src[3] - src[1])
        out[0] = src[0]
        out[1] = src[1]
        for (i in 1 until n - 1) {
            val target = step * i
            while (seg < count - 1 && segStart + segLen < target) {
                segStart += segLen
                seg++
                segLen = hypot(src[2 * seg] - src[2 * seg - 2], src[2 * seg + 1] - src[2 * seg - 1])
            }
            val t = if (segLen > 0f) ((target - segStart) / segLen).coerceIn(0f, 1f) else 0f
            out[2 * i] = src[2 * seg - 2] + t * (src[2 * seg] - src[2 * seg - 2])
            out[2 * i + 1] = src[2 * seg - 1] + t * (src[2 * seg + 1] - src[2 * seg - 1])
        }
        out[2 * n - 2] = src[2 * count - 2]
        out[2 * n - 1] = src[2 * count - 1]
    }

    /**
     * Writes the [n] points of [src] to [out] translated to their centroid and scaled so the
     * bounding box's larger side is 1 (unchanged scale for a single point). Returns false if the
     * curve is degenerate (all points equal).
     */
    fun normalize(src: FloatArray, n: Int, out: FloatArray): Boolean {
        var cx = 0f
        var cy = 0f
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            val x = src[2 * i]
            val y = src[2 * i + 1]
            cx += x
            cy += y
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        cx /= n
        cy /= n
        val side = max(maxX - minX, maxY - minY)
        val k = if (side > EPS) 1f / side else 1f
        for (i in 0 until n) {
            out[2 * i] = (src[2 * i] - cx) * k
            out[2 * i + 1] = (src[2 * i + 1] - cy) * k
        }
        return side > EPS
    }
}
