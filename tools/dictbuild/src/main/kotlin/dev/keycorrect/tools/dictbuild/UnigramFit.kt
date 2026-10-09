package dev.keycorrect.tools.dictbuild

import kotlin.math.exp

/**
 * Monotone map from the AOSP frequency level (0..255) to a natural-log unigram probability, learned
 * on the words that have both an AOSP level and a corpus count.
 *
 * Fit: for every level, the mean of `ln P_corpus(w)` over the words at that level (weight = number
 * of words); then isotonic regression (pool-adjacent-violators) enforces a non-decreasing curve;
 * levels without data are linearly interpolated between their neighbours and extrapolated flat
 * outside the observed range. The curve is only used for its *shape*: the total mass of the words
 * the corpora never saw is fixed separately (see [LmEstimator]).
 */
object UnigramFit {
    const val LEVELS = 256

    /** Fits the curve to `(levels[i], lnp[i])` pairs (`n` of them). */
    fun fit(levels: IntArray, lnp: DoubleArray, n: Int): DoubleArray {
        val sum = DoubleArray(LEVELS)
        val cnt = IntArray(LEVELS)
        for (i in 0 until n) {
            val l = levels[i].coerceIn(0, LEVELS - 1)
            sum[l] += lnp[i]
            cnt[l]++
        }
        // Blocks of pooled levels, left to right.
        val mean = ArrayList<Double>()
        val weight = ArrayList<Int>()
        val first = ArrayList<Int>()
        val last = ArrayList<Int>()
        for (l in 0 until LEVELS) {
            if (cnt[l] == 0) continue
            mean.add(sum[l] / cnt[l])
            weight.add(cnt[l])
            first.add(l)
            last.add(l)
            while (mean.size > 1 && mean[mean.size - 2] > mean[mean.size - 1]) {
                val k = mean.size - 1
                val w = weight[k - 1] + weight[k]
                mean[k - 1] = (mean[k - 1] * weight[k - 1] + mean[k] * weight[k]) / w
                weight[k - 1] = w
                last[k - 1] = last[k]
                mean.removeAt(k)
                weight.removeAt(k)
                first.removeAt(k)
                last.removeAt(k)
            }
        }
        val out = DoubleArray(LEVELS)
        if (mean.isEmpty()) return out
        // Anchor each block at the (weighted) centre of the levels it covers, interpolate between.
        val xs = DoubleArray(mean.size) { (first[it] + last[it]) / 2.0 }
        for (l in 0 until LEVELS) {
            out[l] =
                when {
                    l <= xs.first() -> mean.first()
                    l >= xs.last() -> mean.last()
                    else -> {
                        var k = 0
                        while (xs[k + 1] < l) k++
                        val t = (l - xs[k]) / (xs[k + 1] - xs[k])
                        mean[k] + t * (mean[k + 1] - mean[k])
                    }
                }
        }
        return out
    }

    /** Relative weights `exp(curve[level])` of words at the given levels (for mass spreading). */
    fun weights(curve: DoubleArray, levels: IntArray): DoubleArray =
        DoubleArray(levels.size) { exp(curve[levels[it].coerceIn(0, LEVELS - 1)]) }
}
