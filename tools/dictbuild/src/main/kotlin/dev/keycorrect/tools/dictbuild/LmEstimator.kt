package dev.keycorrect.tools.dictbuild

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Parameters of the language-model estimator.
 *
 * @property tatoebaWeight multiplier of Tatoeba counts: `count = wiki + weight * tatoeba`.
 * @property tatoebaCap if set, the Tatoeba count of any single n-gram type is clipped to this value
 *   before weighting (counters the corpus' skew towards a few names such as "Tom").
 * @property discount2 absolute discount of the bigram level (null = Chen-Goodman estimate `n1 /
 *   (n1 + 2 n2)` from the raw count-of-counts).
 * @property discount3 same for the trigram level.
 * @property unigramMix weight of the Kneser-Ney continuation distribution in the unigram
 *   distribution that is the base of the interpolation (0 = pure frequency).
 * @property minFitCount minimum raw corpus count for a word to take part in the AOSP-level fit.
 */
data class LmParams(
    val tatoebaWeight: Double = 3.0,
    val tatoebaCap: Long = Long.MAX_VALUE,
    val discount2: Double? = null,
    val discount3: Double? = null,
    val unigramMix: Double = 0.5,
    val minFitCount: Int = 3,
)

/**
 * Interpolated absolute-discounting / Kneser-Ney estimator over the counts in [LmTables].
 *
 * ```
 * P1(w)        = (1 - Mu) * ((1 - a) * c(w)/N + a * N1+(.w)/N1+(..))      seen words
 *              = Mu * exp(fit(level(w))) / sum over unseen words            words never seen in a corpus
 * P2(w | v)    = max(c(v,w) - D2, 0) / C(v)   + g2(v) * P1(w),            g2(v) = D2 * N1+(v.) / C(v)
 * P3(z | v,w)  = max(c(v,w,z) - D3, 0) / C(v,w) + g3(v,w) * P2(z | w),    g3 = D3 * N1+(v w .) / C(v,w)
 * ```
 *
 * `C` are *unpruned* context totals, `N1+(v.)` the unpruned number of distinct successors, so each
 * distribution sums to 1 over the vocabulary (up to the mass of pruned bigrams in the lower-order
 * term of the trigram level, where an absent bigram is represented by its back-off part only). Mu
 * is the Good-Turing estimate of the probability of an unseen word, `n1 / N` (n1 = number of words
 * seen exactly once). The lower-order distribution of a trigram is the **bigram** model P2 itself
 * (not a continuation-count distribution), so the table the runtime backs off to is exactly the
 * lower-order term of the model and a missing trigram is `g3(v,w) * P2(z|w)`.
 *
 * Counts are corpus-weighted (`wiki + weight * tatoeba`), but the absolute discounts D are taken
 * from the *raw* counts (an entry seen once loses D of its one occurrence, whichever corpus it came
 * from) and then scaled by the entry's weight per raw token, so the discounted mass and the
 * back-off weight stay consistent with the weighted context totals.
 */
class LmEstimator(val tables: LmTables, val params: LmParams = LmParams()) {
    private val t = tables
    private val vocabSize = t.vocabSize
    private val tatWeight = params.tatoebaWeight

    init {
        require(tatWeight > 0) { "tatoeba weight must be positive" }
        require(params.unigramMix in 0.0..1.0) { "unigram mix must be in 0..1" }
    }

    /** Absolute discounts, in units of *raw* counts (see the class comment). */
    val d2: Double = params.discount2 ?: chenGoodman(t.biCountOfCounts, 0.7)
    val d3: Double = params.discount3 ?: chenGoodman(t.triCountOfCounts, 0.8)

    private fun chenGoodman(coc: LongArray?, fallback: Double): Double {
        if (coc == null || coc[0] == 0L) return fallback
        return coc[0].toDouble() / (coc[0] + 2.0 * coc[1])
    }

    /** Weighted count of an n-gram type. */
    fun weighted(wiki: Int, tat: Int): Double =
        wiki +
            tatWeight *
                (if (tat > params.tatoebaCap) params.tatoebaCap else tat.toLong()).toDouble()

    /**
     * Discounted weighted count of an n-gram type: the discount [d] is taken from the raw count and
     * the result scaled by the corpus weight per raw token, `max(raw - d, 0) * weighted / raw`.
     */
    fun discounted(wiki: Int, tat: Int, d: Double): Double {
        val raw = wiki + tat
        if (raw <= 0) return 0.0
        return max(raw - d, 0.0) * weighted(wiki, tat) / raw
    }

    /**
     * Back-off weight `d * (sum over the distinct successors of weighted/raw) / total`; the sum is
     * rebuilt from the number of distinct successors and their Tatoeba share.
     */
    private fun backoffWeight(d: Double, distinct: Int, tatShare: Double, total: Double): Double =
        min(1.0, d * (distinct + (tatWeight - 1.0) * tatShare) / total)

    // ------------------------------------------------------------------ unigrams

    /** Fraction of the unigram mass reserved for words no corpus contains. */
    var unseenMass = 0.0
        private set

    /** P1(w) by word index (index 0 = `<s>` is 0). */
    val uni = DoubleArray(vocabSize + 1)

    /** True for vocabulary words without any corpus count. */
    val unseen = BooleanArray(vocabSize + 1)

    /** The fitted `AOSP level -> ln P` curve (see [UnigramFit]). */
    var fitCurve = DoubleArray(UnigramFit.LEVELS)
        private set

    init {
        val uc = DoubleArray(vocabSize + 1)
        var total = 0.0
        var contSum = 0.0
        var nRaw = 0L
        var n1 = 0L
        var unseenCount = 0
        for (i in 1..vocabSize) {
            val raw = t.uniWiki[i] + t.uniTat[i]
            if (raw == 0L) {
                unseen[i] = true
                unseenCount++
                continue
            }
            val tat = min(t.uniTat[i], params.tatoebaCap)
            uc[i] = t.uniWiki[i] + tatWeight * tat
            total += uc[i]
            contSum += max(t.cont[i].toDouble(), 0.5)
            nRaw += raw
            if (raw == 1L) n1++
        }
        unseenMass =
            if (unseenCount == 0 || nRaw == 0L) 0.0 else min(0.5, max(n1, 1L).toDouble() / nRaw)
        // AOSP-level fit on the words with enough counts.
        val levels = IntArray(vocabSize)
        val lnp = DoubleArray(vocabSize)
        var n = 0
        for (i in 1..vocabSize) {
            if (unseen[i] || t.uniWiki[i] + t.uniTat[i] < params.minFitCount) continue
            levels[n] = t.vocab[i - 1].originalFreq
            lnp[n++] = ln(uc[i] / total)
        }
        fitCurve = UnigramFit.fit(levels, lnp, n)
        var unseenWeightSum = 0.0
        for (i in 1..vocabSize) {
            if (unseen[i])
                unseenWeightSum += exp(fitCurve[t.vocab[i - 1].originalFreq.coerceIn(0, 255)])
        }
        val a = params.unigramMix
        for (i in 1..vocabSize) {
            uni[i] =
                if (unseen[i]) {
                    unseenMass * exp(fitCurve[t.vocab[i - 1].originalFreq.coerceIn(0, 255)]) /
                        unseenWeightSum
                } else {
                    (1 - unseenMass) *
                        ((1 - a) * uc[i] / total + a * max(t.cont[i].toDouble(), 0.5) / contSum)
                }
        }
    }

    // ------------------------------------------------------------------ bigrams

    /** Total weighted count of the (unpruned) successors of each w1; 0 = no context. */
    val biTotal = DoubleArray(vocabSize + 1)

    /** Back-off weight g2(v) (1 for a context without bigrams). */
    val gamma2 = DoubleArray(vocabSize + 1) { 1.0 }

    /** `ln P2(w2 | w1)` for every row of `t.bi`. */
    val lp2 = DoubleArray(t.bi.size)

    private val biIndex = LongIntMap(t.bi.size * 2)

    init {
        val b = t.bi
        val excess = LongArray(vocabSize + 1)
        for (r in 0 until b.size) {
            biIndex.put(LmTables.pairKey(b.w1[r], b.last[r]), r)
            if (b.tat[r] > params.tatoebaCap) excess[b.w1[r]] += b.tat[r] - params.tatoebaCap
        }
        for (v in 0..vocabSize) {
            val d = t.biCtx.distinct[v]
            if (d == 0) continue
            val c = t.biCtx.wiki[v] + tatWeight * (t.biCtx.tat[v] - excess[v])
            if (c <= 0.0) continue
            biTotal[v] = c
            gamma2[v] = backoffWeight(d2, d, t.biCtx.tatShare[v], c)
        }
        for (r in 0 until b.size) {
            val v = b.w1[r]
            val disc = discounted(b.wiki[r], b.tat[r], d2)
            lp2[r] =
                ln((if (biTotal[v] > 0) disc / biTotal[v] else 0.0) + gamma2[v] * uni[b.last[r]])
        }
    }

    /** Row of bigram (v, w) in `t.bi`, or -1. */
    fun bigramRow(v: Int, w: Int): Int = biIndex.get(LmTables.pairKey(v, w))

    /**
     * Bigram probability P2(w | v) over the full (unpruned) model as far as the tables go: a bigram
     * absent from the tables is represented by its back-off part only.
     */
    fun p2(v: Int, w: Int): Double {
        val r = bigramRow(v, w)
        val backoff = gamma2[v] * uni[w]
        if (r < 0 || biTotal[v] <= 0.0) return backoff
        return discounted(t.bi.wiki[r], t.bi.tat[r], d2) / biTotal[v] + backoff
    }

    // ------------------------------------------------------------------ trigrams

    /**
     * Total weighted count of the (unpruned) successors of each context pair (row of `t.triCtx`).
     */
    val triTotal = DoubleArray(t.triCtx.size)

    /** Back-off weight g3(v, w) per context row. */
    val gamma3 = DoubleArray(t.triCtx.size) { 1.0 }

    /** `ln P3(z | v, w)` for every row of `t.tri` (NaN if the row's context is unknown). */
    val lp3 = DoubleArray(t.tri.size)

    /** Context row (index into `t.triCtx`) of every trigram row, -1 if unknown. */
    val triCtxRow = IntArray(t.tri.size)

    init {
        val r3 = t.tri
        val excess = LongArray(t.triCtx.size)
        for (r in 0 until r3.size) {
            val c = t.triCtx.indexOf(r3.w1[r], r3.w2[r])
            triCtxRow[r] = c
            if (c >= 0 && r3.tat[r] > params.tatoebaCap) excess[c] += r3.tat[r] - params.tatoebaCap
        }
        for (c in 0 until t.triCtx.size) {
            val d = t.triCtx.distinct(c)
            val total = t.triCtx.wiki(c) + tatWeight * (t.triCtx.tat(c) - excess[c])
            if (d == 0 || total <= 0.0) continue
            triTotal[c] = total
            gamma3[c] = backoffWeight(d3, d, t.triCtx.tatShare(c), total)
        }
        for (r in 0 until r3.size) {
            val c = triCtxRow[r]
            if (c < 0 || triTotal[c] <= 0.0) {
                lp3[r] = Double.NaN
                continue
            }
            val disc = discounted(r3.wiki[r], r3.tat[r], d3)
            lp3[r] = ln(disc / triTotal[c] + gamma3[c] * p2(r3.w2[r], r3.last[r]))
        }
    }

    private val triIndex by lazy {
        LongIntMap(t.tri.size * 2).also { m ->
            for (r in 0 until t.tri.size) m.put(
                tripleKey(t.tri.w1[r], t.tri.w2[r], t.tri.last[r]),
                r,
            )
        }
    }

    private fun tripleKey(a: Int, b: Int, c: Int): Long =
        (a.toLong() shl 42) or (b.toLong() shl 21) or c.toLong()

    /** P3(z | v, w) of the model (a trigram absent from the tables = its back-off part only). */
    fun p3(v: Int, w: Int, z: Int): Double {
        val c = t.triCtx.indexOf(v, w)
        if (c < 0 || triTotal[c] <= 0.0) return p2(w, z)
        val back = gamma3[c] * p2(w, z)
        val r = triIndex.get(tripleKey(v, w, z))
        if (r < 0) return back
        return discounted(t.tri.wiki[r], t.tri.tat[r], d3) / triTotal[c] + back
    }
}
