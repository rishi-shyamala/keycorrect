package dev.keycorrect.core.decoder.gesture

import dev.keycorrect.core.decoder.NeuralSwipeConfig
import dev.keycorrect.core.decoder.Text
import dev.keycorrect.core.dictionary.Dictionary
import dev.keycorrect.core.dictionary.DictionaryFormatException
import dev.keycorrect.core.engineapi.GestureInput
import dev.keycorrect.core.engineapi.KeyLayoutModel
import dev.keycorrect.core.swipenet.SwipeEmissions
import dev.keycorrect.core.swipenet.SwipeEncoder
import dev.keycorrect.core.swipenet.SwipeFrame
import dev.keycorrect.core.swipenet.SwipeLayout

/**
 * The encoder's output for one gesture, ready for the searches in [NeuralSearch]: [logProbs] is
 * [SwipeEmissions.STEPS] x [SwipeEmissions.CLASSES] log-probabilities, already scaled per step by
 * the intention gate ([NeuralSwipeConfig.gate]); [classOf] maps a lowercase letter to its class
 * (the layout slot the encoder was given), -1 when the layout has no such key.
 */
internal class NeuralEmissions(val logProbs: FloatArray, private val classes: KeyClasses) {
    fun classOf(c: Char): Int = classes.of(c)
}

/** Lowercase letter -> encoder class for one [SwipeLayout]. */
internal class KeyClasses(layout: SwipeLayout) {
    private val ascii = IntArray(ASCII) { -1 }
    private val other = HashMap<Char, Int>()

    init {
        layout.chars.forEachIndexed { i, c ->
            if (c.code < ASCII) ascii[c.code] = i else other[c] = i
        }
    }

    fun of(c: Char): Int = if (c.code < ASCII) ascii[c.code] else other[c] ?: -1

    private companion object {
        const val ASCII = 128
    }
}

/**
 * Runs the neural swipe encoder for the engine (docs/PHASE10.md, 10a-3). [encoder] is read per
 * gesture, so a pack that loads later is picked up; null means "no encoder" and the engine decodes
 * with SHARK² alone. Safe from any thread: the layout cache is guarded here, the encoder serialises
 * its own calls. Any failure (no encoder, a layout the encoder can't take, a failed native call)
 * yields null, never an exception, so the caller falls back to SHARK².
 */
internal class NeuralSwipe(
    private val encoder: () -> SwipeEncoder?,
    private val config: NeuralSwipeConfig,
) {
    private class Cached(val id: Long, val layout: SwipeLayout?, val classes: KeyClasses?)

    @Volatile private var cached: Cached? = null

    /** The encoder's emissions for [input] on [layout], or null (see the class comment). */
    @Suppress("TooGenericExceptionCaught") // any failure of the encoder means "use SHARK² alone"
    fun emissions(input: GestureInput, layout: KeyLayoutModel): NeuralEmissions? {
        val enc = encoder()
        val c = if (enc == null) null else cachedFor(layout)
        val swipeLayout = c?.layout
        val classes = c?.classes
        if (enc == null || swipeLayout == null || classes == null) return null
        return try {
            val pts = input.points
            val t0 = pts[0].timeMillis
            val features =
                swipeLayout.features(
                    FloatArray(pts.size) { pts[it].x },
                    FloatArray(pts.size) { pts[it].y },
                    FloatArray(pts.size) { (pts[it].timeMillis - t0).toFloat() },
                )
            enc.emissions(features, swipeLayout)?.let { NeuralEmissions(gated(it), classes) }
        } catch (_: RuntimeException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    /** The log-probabilities, each step scaled by `1 - gate + gate * lambda`. */
    private fun gated(e: SwipeEmissions): FloatArray {
        val out = e.logEmissions
        val g = config.gate
        if (g == 0f) return out
        for (t in 0 until SwipeEmissions.STEPS) {
            val w = 1f - g + g * e.lambda[t].coerceIn(0f, 1f)
            for (k in 0 until SwipeEmissions.CLASSES) {
                out[t * SwipeEmissions.CLASSES + k] *= w
            }
        }
        return out
    }

    private fun cachedFor(layout: KeyLayoutModel): Cached {
        cached?.let { if (it.id == layout.id) return it }
        val swipeLayout =
            SwipeLayout.of(
                layout.keys.map {
                    it.char to
                        SwipeFrame(
                            it.centerX - it.width / 2,
                            it.centerY - it.height / 2,
                            it.centerX + it.width / 2,
                            it.centerY + it.height / 2,
                        )
                }
            )
        return Cached(layout.id, swipeLayout, swipeLayout?.let { KeyClasses(it) }).also {
            cached = it
        }
    }
}

/**
 * Searches of the encoder's emissions against the dictionary. Both score a word by its best
 * alignment (Viterbi, not the CTC sum): the word's keys, with apostrophes skipped and a repeated
 * key collapsed (a swipe can't show "ll"), are emitted in order, each for one or more steps, with
 * blanks before, between and after them; a step may also repeat the key just emitted.
 * - [beam] is a prefix beam search over the dictionary trie: [NeuralSwipeConfig.beamWidth] prefixes
 *   survive each step, and the best complete words are returned.
 * - [align] scores one given word exactly (for SHARK²'s candidates the beam didn't keep).
 *
 * Not thread-safe (scratch buffers); the engine calls it under its lock. Allocation-free per step
 * once the buffers have grown.
 */
// Small private steps of one search; splitting the class would only add indirection.
@Suppress("TooManyFunctions")
internal class NeuralSearch(private val dict: Dictionary, config: NeuralSwipeConfig) {
    private val width = config.beamWidth.coerceAtLeast(1)

    // The beam: trie node, the class of its last key (-1 at the root) and the score.
    private val node = IntArray(width)
    private val cls = IntArray(width)
    private val score = FloatArray(width)
    private var size = 0

    // Next step's hypotheses, merged by node through an open-addressing table (generation-cleared).
    private var nNode = IntArray(INITIAL)
    private var nCls = IntArray(INITIAL)
    private var nScore = FloatArray(INITIAL)
    private var nSize = 0
    private var table = IntArray(INITIAL * 2)
    private var tableGen = IntArray(INITIAL * 2)
    private var gen = 0
    private var sortScratch = FloatArray(INITIAL)

    // [align] scratch.
    private val seq = IntArray(MAX_KEYS)
    private var dp = FloatArray(MAX_KEYS + 1)
    private var next = FloatArray(MAX_KEYS + 1)

    /**
     * The best complete words of the beam search over [e], best first, into [out] (lowercase trie
     * keys); returns how many (at most `out.size`). [stale] is polled once per step; a stale search
     * returns -1.
     */
    fun beam(e: NeuralEmissions, out: Array<String?>, stale: () -> Boolean): Int {
        size = 1
        node[0] = dict.root
        cls[0] = -1
        score[0] = 0f
        var t = 0
        var gaveUp = false
        while (t < SwipeEmissions.STEPS && size > 0 && !gaveUp) {
            gaveUp = stale()
            if (!gaveUp) step(e, t * SwipeEmissions.CLASSES)
            t++
        }
        return if (gaveUp) -1 else finish(out)
    }

    /** One encoder step: extend every prefix, merge by node, close over free edges, prune. */
    private fun step(e: NeuralEmissions, row: Int) {
        val lp = e.logProbs
        val blank = lp[row + SwipeEmissions.BLANK]
        newStep()
        for (h in 0 until size) {
            val n = node[h]
            val c = cls[h]
            val s = score[h]
            // Stay: a blank, or the same key again.
            push(n, c, s + if (c >= 0) maxOf(blank, lp[row + c]) else blank)
            val count = dict.childCount(n)
            if (count == 0) continue
            val first = dict.firstChild(n)
            for (i in 0 until count) {
                val child = first + i
                val cc = e.classOf(dict.nodeChar(child))
                // A repeated key or an apostrophe is a free edge (closure below); off-layout: dead.
                if (cc >= 0 && cc != c) push(child, cc, s + lp[row + cc])
            }
        }
        closeFreeEdges(e)
        prune()
    }

    /**
     * Adds, at no cost, the children of every hypothesis reached by an apostrophe or by its own
     * last key again ("hel" -> "hell"), so "hello" and "don't" align like "helo" and "dont".
     */
    private fun closeFreeEdges(e: NeuralEmissions) {
        var h = 0
        while (h < nSize) {
            val n = nNode[h]
            val c = nCls[h]
            val count = if (c >= 0) dict.childCount(n) else 0
            if (count > 0) {
                val first = dict.firstChild(n)
                for (i in 0 until count) {
                    val child = first + i
                    val ch = dict.nodeChar(child)
                    if (Text.isApostrophe(ch) || e.classOf(ch) == c) push(child, c, nScore[h])
                }
            }
            h++
        }
    }

    /**
     * Keeps the best [width] of the next step's hypotheses as the beam: everything above the
     * width-th best score, then hypotheses tied with it (in insertion order) until the beam is
     * full.
     */
    private fun prune() {
        var cut = Float.NEGATIVE_INFINITY
        if (nSize > width) {
            if (sortScratch.size < nSize) sortScratch = FloatArray(nNode.size)
            System.arraycopy(nScore, 0, sortScratch, 0, nSize)
            java.util.Arrays.sort(sortScratch, 0, nSize)
            cut = sortScratch[nSize - width]
        }
        size = 0
        for (h in 0 until nSize) if (nScore[h] > cut) keep(h)
        for (h in 0 until nSize) {
            if (size == width) break
            if (nScore[h] == cut && cut > Float.NEGATIVE_INFINITY) keep(h)
        }
    }

    private fun keep(h: Int) {
        node[size] = nNode[h]
        cls[size] = nCls[h]
        score[size] = nScore[h]
        size++
    }

    private fun newStep() {
        nSize = 0
        gen++
        if (gen == Int.MAX_VALUE) {
            tableGen.fill(0)
            gen = 1
        }
    }

    /** Adds a hypothesis for the next step, or raises the score of the one at [n]. */
    private fun push(n: Int, c: Int, s: Float) {
        if (nSize * 2 >= table.size) grow()
        val mask = table.size - 1
        var i = mix(n) and mask
        while (tableGen[i] == gen) {
            val h = table[i]
            if (nNode[h] == n) {
                if (s > nScore[h]) nScore[h] = s
                return
            }
            i = (i + 1) and mask
        }
        tableGen[i] = gen
        table[i] = nSize
        nNode[nSize] = n
        nCls[nSize] = c
        nScore[nSize] = s
        nSize++
    }

    private fun grow() {
        val cap = nNode.size * 2
        nNode = nNode.copyOf(cap)
        nCls = nCls.copyOf(cap)
        nScore = nScore.copyOf(cap)
        table = IntArray(cap * 2)
        tableGen = IntArray(cap * 2)
        // Rehash the current step's entries.
        for (h in 0 until nSize) {
            val mask = table.size - 1
            var i = mix(nNode[h]) and mask
            while (tableGen[i] == gen) i = (i + 1) and mask
            tableGen[i] = gen
            table[i] = h
        }
    }

    /** The complete words of the final beam, best first (ties by node, for determinism). */
    private fun finish(out: Array<String?>): Int {
        var n = 0
        // Selection by repeated maximum: the beam and [out] are small.
        val taken = BooleanArray(size)
        while (n < out.size) {
            var best = -1
            for (h in 0 until size) {
                if (taken[h] || !dict.isTerminal(node[h])) continue
                if (best < 0 || better(h, best)) best = h
            }
            if (best < 0) break
            taken[best] = true
            keyAt(node[best])?.let { out[n++] = it }
        }
        return n
    }

    private fun better(a: Int, b: Int) =
        score[a] > score[b] || (score[a] == score[b] && node[a] < node[b])

    private fun keyAt(n: Int): String? =
        try {
            if (dict.wordIdCountAt(n) == 0) null else dict.keyOf(dict.wordIdAt(n, 0))
        } catch (_: DictionaryFormatException) {
            null
        }

    /**
     * The best-alignment log-likelihood of [key] (a lowercase word) under [e], or negative infinity
     * when a letter has no key or the word has more keys than the encoder has steps.
     */
    fun align(key: String, e: NeuralEmissions): Float {
        val len = keySequence(key, e)
        if (len <= 0) return Float.NEGATIVE_INFINITY
        dp.fill(Float.NEGATIVE_INFINITY, 0, len + 1)
        dp[0] = 0f
        val lp = e.logProbs
        for (t in 0 until SwipeEmissions.STEPS) {
            val row = t * SwipeEmissions.CLASSES
            val blank = lp[row + SwipeEmissions.BLANK]
            next[0] = dp[0] + blank
            for (j in 1..len) {
                val emit = lp[row + seq[j - 1]]
                next[j] = maxOf(dp[j] + maxOf(blank, emit), dp[j - 1] + emit)
            }
            val swap = dp
            dp = next
            next = swap
        }
        return dp[len]
    }

    /**
     * Writes [key]'s classes to [seq] (apostrophes skipped, repeats collapsed) and returns how
     * many; -1 if a letter has no key or there are more keys than the encoder has steps.
     */
    private fun keySequence(key: String, e: NeuralEmissions): Int {
        var len = 0
        for (ch in key) {
            val c = if (Text.isApostrophe(ch)) APOSTROPHE else e.classOf(ch)
            when {
                c == APOSTROPHE -> Unit
                c < 0 || len == MAX_KEYS -> return -1
                len == 0 || seq[len - 1] != c -> seq[len++] = c
            }
        }
        return len
    }

    private companion object {
        const val INITIAL = 256
        const val APOSTROPHE = -2

        /** Fibonacci hashing: 2^32 / golden ratio, as a signed Int. */
        const val GOLDEN = -0x61c88647
        const val HALF_BITS = 16

        /** Longest key sequence [align] takes (more than the encoder's steps anyway). */
        const val MAX_KEYS = SwipeEmissions.STEPS

        fun mix(n: Int): Int {
            val x = n * GOLDEN
            return x xor (x ushr HALF_BITS)
        }
    }
}
