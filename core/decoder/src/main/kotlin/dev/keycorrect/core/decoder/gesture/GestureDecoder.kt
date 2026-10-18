package dev.keycorrect.core.decoder.gesture

import dev.keycorrect.core.decoder.GestureConfig
import dev.keycorrect.core.decoder.UserLexicon
import dev.keycorrect.core.dictionary.Dictionary
import dev.keycorrect.core.engineapi.GestureInput
import dev.keycorrect.core.engineapi.KeyLayoutModel
import kotlin.math.sqrt

/**
 * The shape/location stage of swipe decoding (SHARK²-style, docs/PHASE5.md): resamples the gesture,
 * picks the words whose first and last key are near the path's ends, prunes by path length, and
 * keeps the [GestureConfig.shortlistSize] best by channel score plus a unigram prior. The engine
 * then adds the contextual language model to the shortlist.
 *
 * Threading: [run] and the per-decode scratch are not thread-safe; the engine calls [run] under its
 * lock. [indexFor] and [warmUp] are safe from any thread (they take [buildLock], never the engine's
 * lock) so an index build never blocks typed predictions. After [run], entries `0 until count` of
 * [keys], [userIndex], [channel] and [prior] describe the shortlist (unordered).
 */
// Small private steps of one pipeline; splitting the class would only add indirection.
@Suppress("TooManyFunctions")
internal class GestureDecoder(
    private val dict: Dictionary,
    private val config: GestureConfig,
    /** Prior log-probability of a user word (`UserDataConfig.wordLogProb`). */
    private val userLogProb: Float,
) {
    private val n = config.resamplePoints
    private val words by lazy { GestureWordList(dict, config) }

    // Guards [layouts] and the (long) construction of an index. A built GestureLayoutIndex is
    // published through this lock; afterwards only the engine-locked decode thread mutates it
    // (its template cache), and builders only read immutable inputs (word list, layout, config).
    private val buildLock = Any()
    private val layouts =
        object : LinkedHashMap<Long, GestureLayoutIndex>(4, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Long, GestureLayoutIndex>
            ) = size > MAX_LAYOUTS
        }

    // Per-decode scratch (no allocation in the loops).
    private var raw = FloatArray(256)
    private val gesture = FloatArray(2 * n)
    private val gestureShape = FloatArray(2 * n)
    private val weights = FloatArray(n)
    private var weightUnit = Float.NaN
    private var startOk = BooleanArray(0)
    private var endOk = BooleanArray(0)
    private var reachMask = IntArray(0)

    // Shortlist.
    private val k = config.shortlistSize
    val keys = arrayOfNulls<String>(k)

    /** -1 for a dictionary-list word, else the user-lexicon index. */
    val userIndex = IntArray(k)

    /** Channel log-likelihood (location + shape, no prior). */
    val channel = FloatArray(k)

    /** Shortlist ranking score: channel + unigramWeight * unigram. */
    private val rank = FloatArray(k)
    var count = 0
        private set

    private var worst = Float.NEGATIVE_INFINITY
    private var gestureLength = 0f
    private var locInv = 0f
    private var shapeInv = 0f
    private var singleKeyOk = false

    /** Builds the word list and the index of [layout] ahead of the first gesture. */
    fun warmUp(layout: KeyLayoutModel) {
        indexFor(layout)
    }

    /**
     * The index of [layout], built on first use. Call it outside the engine lock, then pass the
     * result to [run]; concurrent callers for the same layout wait for one build.
     */
    fun indexFor(layout: KeyLayoutModel): GestureLayoutIndex =
        synchronized(buildLock) {
            layouts[layout.id]
                ?: GestureLayoutIndex(layout, words, config).also { layouts[layout.id] = it }
        }

    /**
     * Fills the shortlist for [input] on [index] (from [indexFor]; null when the layout has no
     * keys). Returns the number of entries, or -1 when [stale] fired (the shortlist is then
     * meaningless).
     */
    fun run(
        index: GestureLayoutIndex?,
        input: GestureInput,
        lexicon: UserLexicon,
        stale: () -> Boolean,
    ): Int {
        count = 0
        worst = Float.NEGATIVE_INFINITY
        return when {
            index == null -> 0
            stale() -> -1
            !prepare(index, input) -> 0
            scanDictionary(index, stale) && considerUserWords(index, lexicon, stale) -> count
            else -> -1
        }
    }

    /** Prepares the gesture and the per-decode tables; false if there is nothing to decode. */
    private fun prepare(index: GestureLayoutIndex, input: GestureInput): Boolean =
        prepareGesture(input, index.unit) && prepareTables(index)

    private fun prepareTables(index: GestureLayoutIndex): Boolean {
        val unit = index.unit
        if (unit != weightUnit) buildWeights(unit)
        locInv = HALF / (config.locationSigma * config.locationSigma)
        shapeInv = HALF / (config.shapeSigma * config.shapeSigma)
        singleKeyOk = gestureLength <= config.singleKeyMaxPathKeyWidths * unit
        if (startOk.size < index.keyCount) {
            startOk = BooleanArray(index.keyCount)
            endOk = BooleanArray(index.keyCount)
            reachMask = IntArray(index.keyCount)
        }
        val gx0 = gesture[0]
        val gy0 = gesture[1]
        val gx1 = gesture[2 * n - 2]
        val gy1 = gesture[2 * n - 1]
        var anyStart = false
        var anyEnd = false
        for (i in 0 until index.keyCount) {
            val r = config.endRadius * index.keyWidth[i]
            startOk[i] = PathMath.dist(index.keyX[i] - gx0, index.keyY[i] - gy0) <= r
            endOk[i] = PathMath.dist(index.keyX[i] - gx1, index.keyY[i] - gy1) <= r
            anyStart = anyStart || startOk[i]
            anyEnd = anyEnd || endOk[i]
        }
        if (!anyStart || !anyEnd) return false
        prepareReach(index)
        return true
    }

    /**
     * Prepares the cheap pre-filter that runs before a word's template is built: for every key, the
     * set of [GestureConfig.keyReachSamples] equidistant gesture points within
     * [GestureConfig.keyReachKeyWidths] key widths (plus half the sample spacing) of it. A word
     * whose letters cannot be matched to the samples in order is not the intended one.
     */
    private fun prepareReach(index: GestureLayoutIndex) {
        val samples = config.keyReachSamples.coerceIn(2, Int.SIZE_BITS)
        val slack = gestureLength / (2 * (samples - 1))
        for (i in 0 until index.keyCount) {
            val reach = config.keyReachKeyWidths * index.keyWidth[i] + slack
            var mask = 0
            for (s in 0 until samples) {
                val p = s * (n - 1) / (samples - 1)
                if (
                    PathMath.dist(
                        index.keyX[i] - gesture[2 * p],
                        index.keyY[i] - gesture[2 * p + 1],
                    ) <= reach
                )
                    mask = mask or (1 shl s)
            }
            reachMask[i] = mask
        }
    }

    /** True if [len]-long template path is acceptable for the gesture's length (zero: one key). */
    private fun lengthFits(len: Float): Boolean =
        if (len <= 0f) singleKeyOk
        else
            (gestureLength / len).let { it >= config.lengthRatioMin && it <= config.lengthRatioMax }

    /** Scores the dictionary words of every (start key, end key) cell; false if stale. */
    private fun scanDictionary(index: GestureLayoutIndex, stale: () -> Boolean): Boolean {
        var seen = 0
        for (f in 0 until index.keyCount) {
            if (!startOk[f]) continue
            for (l in 0 until index.keyCount) {
                if (!endOk[l]) continue
                val cell = index.cell(f, l)
                for (p in index.cellBegin(cell) until index.cellEnd(cell)) {
                    if ((++seen and STALE_MASK) == 0 && stale()) return false
                    val w = index.wordAt(p)
                    if (lengthFits(index.pathLengthOf(w)) && index.visitsInOrder(w, reachMask)) {
                        consider(index.templateOf(w), words.keys[w], -1, words.unigram[w])
                    }
                }
            }
        }
        return !stale()
    }

    /** Scores the user-lexicon words that fit; false if stale. */
    private fun considerUserWords(
        index: GestureLayoutIndex,
        lexicon: UserLexicon,
        stale: () -> Boolean,
    ): Boolean {
        val seq = index.scratch()
        for (u in 0 until lexicon.size) {
            val key = lexicon.key(u)
            if ((u and STALE_MASK) == 0 && stale()) return false
            val len = if (words.contains(key)) 0 else index.sequence(key, seq)
            if (len > 0 && startOk[seq[0].toInt()] && endOk[seq[len - 1].toInt()]) {
                val t = index.templateOfSequence(seq, len)
                if (lengthFits(t.length)) consider(t, key, u, 0f)
            }
        }
        return !stale()
    }

    /** Scores [t] and offers it to the shortlist. */
    @Suppress("ReturnCount") // early abandon in the hot loop
    private fun consider(t: Template, key: String, user: Int, unigramOrNaN: Float) {
        val uni = if (user >= 0) userLogProb else unigramOrNaN
        val prior = config.unigramWeight * uni
        val bound = maxOf(worst, prior - config.maxChannelCost)
        val g = gesture
        val tp = t.pts

        // Location channel: weighted mean point distance in key widths (weights sum to 1/unit).
        var sum = 0f
        for (i in 0 until n) {
            sum += weights[i] * PathMath.dist(g[2 * i] - tp[2 * i], g[2 * i + 1] - tp[2 * i + 1])
            if ((i and CHECK_MASK) == CHECK_MASK && prior - sum * sum * locInv < bound) return
        }
        val locCost = sum * sum * locInv

        var shapeCost = 0f
        if (!t.isPoint) {
            val gs = gestureShape
            val ts = t.shape
            var s = 0f
            for (i in 0 until n) {
                val dx = gs[2 * i] - ts[2 * i]
                val dy = gs[2 * i + 1] - ts[2 * i + 1]
                s += sqrt(dx * dx + dy * dy)
                if ((i and CHECK_MASK) == CHECK_MASK) {
                    val m = s / n
                    if (prior - locCost - m * m * shapeInv < bound) return
                }
            }
            val m = s / n
            shapeCost = m * m * shapeInv
        }
        val chan = -(locCost + shapeCost)
        val score = prior + chan
        if (score < bound) return
        offer(key, user, chan, score)
    }

    private fun offer(key: String, user: Int, chan: Float, score: Float) {
        val slot: Int
        if (count < k) {
            slot = count++
        } else {
            var w = 0
            for (i in 1 until k) if (rank[i] < rank[w]) w = i
            if (score <= rank[w]) return
            slot = w
        }
        keys[slot] = key
        userIndex[slot] = user
        channel[slot] = chan
        rank[slot] = score
        if (count == k) {
            var m = rank[0]
            for (i in 1 until k) if (rank[i] < m) m = rank[i]
            worst = m
        }
    }

    /** Resamples and normalises the gesture; false if it is too short to be a swipe. */
    private fun prepareGesture(input: GestureInput, unit: Float): Boolean {
        val pts = input.points
        if (raw.size < 2 * pts.size) raw = FloatArray(2 * pts.size)
        for (i in pts.indices) {
            raw[2 * i] = pts[i].x
            raw[2 * i + 1] = pts[i].y
        }
        PathMath.resample(raw, pts.size, gesture, n)
        gestureLength = PathMath.length(gesture, n)
        if (gestureLength < config.minPathKeyWidths * unit) return false
        PathMath.normalize(gesture, n, gestureShape)
        return true
    }

    private fun buildWeights(unit: Float) {
        weightUnit = unit
        var total = 0f
        val span = config.endSpanPoints.coerceAtLeast(1)
        for (i in 0 until n) {
            val edge = minOf(i, n - 1 - i)
            val bump = (1f - edge.toFloat() / span).coerceAtLeast(0f)
            weights[i] = 1f + (config.endWeight - 1f) * bump
            total += weights[i]
        }
        for (i in 0 until n) weights[i] = weights[i] / total / unit
    }

    private companion object {
        const val MAX_LAYOUTS = 2
        const val STALE_MASK = 255
        const val CHECK_MASK = 7
        const val HALF = 0.5f
    }
}
