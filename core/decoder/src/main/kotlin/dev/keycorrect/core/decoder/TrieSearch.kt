package dev.keycorrect.core.decoder

import dev.keycorrect.core.dictionary.Dictionary
import dev.keycorrect.core.dictionary.WordFlags

/**
 * Best-first search over the dictionary trie that aligns trie paths with the typed chars (§4.4).
 *
 * A state is (trie node, number of typed chars consumed, edits used). Transitions: match or spatial
 * substitution of a child char, insertion (typed char the word lacks), deletion (word char not
 * typed), transposition of two typed chars, and - once all input is consumed - completion down the
 * trie. States are ordered by an admissible bound: their score so far plus `lambda *
 * maxLogProbInSubtree(node)`, so the first [SearchConfig.poolSize] words found are the best by
 * (spatial + edits + completion + lambda * unigram). The caller re-scores them with the contextual
 * language model.
 *
 * Work is bounded by [SearchConfig.maxExpansions] and [SearchConfig.maxStates]; ties break by
 * insertion order, so results are deterministic. All buffers are allocated once; [run] does not
 * allocate. Not thread-safe: one instance per engine.
 */
internal class TrieSearch(
    private val dict: Dictionary,
    private val search: SearchConfig,
    private val edits: EditConfig,
    private val lambda: Float,
    private val completionPenalty: Float,
) {
    // State pool (struct of arrays) and a 4-ary max-heap over state indices.
    private val stNode = IntArray(search.maxStates)
    private val stPos = IntArray(search.maxStates)
    private val stEdits = IntArray(search.maxStates)
    private val stExtra = IntArray(search.maxStates)
    private val stSpatial = FloatArray(search.maxStates)
    private val stPenalty = FloatArray(search.maxStates)
    private val stBound = FloatArray(search.maxStates)
    private val heap = IntArray(search.maxStates)

    /** `stBound` of each [heap] entry, kept alongside so comparisons don't chase indices. */
    private val heapBound = FloatArray(search.maxStates)
    private var stCount = 0
    private var heapSize = 0

    // Best score seen per (node, pos, edits), to drop dominated duplicate paths.
    private val visitedMask = Integer.highestOneBit(search.maxStates * 2) * 2 - 1
    private val visitedKeys = LongArray(visitedMask + 1)
    private val visitedValues = FloatArray(visitedMask + 1)
    private val visitedStamp = IntArray(visitedMask + 1)
    private var stamp = 0

    /** Result words: terminal node and the score components of the best path to it. */
    var resultCount = 0
        private set

    val resultNode = IntArray(search.poolSize)
    val resultSpatial = FloatArray(search.poolSize)
    val resultPenalty = FloatArray(search.poolSize)
    val resultEdits = IntArray(search.poolSize)
    val resultExtra = IntArray(search.poolSize)
    private val resultScore = FloatArray(search.poolSize)
    private var worstScore = Float.NEGATIVE_INFINITY
    private var bestScore = Float.NEGATIVE_INFINITY

    /** Number of states expanded by the last [run]. */
    var expansions = 0
        private set

    private lateinit var scorer: TouchScorer
    private var n = 0
    private var budget = 0
    private var excludedNode = -1

    /**
     * Searches for words matching the input bound in [scorer]. [literalNode] (or -1) is the node of
     * the typed word itself: it is never returned. [isStale] is polled periodically; when it fires
     * the search stops and returns false.
     */
    fun run(scorer: TouchScorer, literalNode: Int, isStale: () -> Boolean): Boolean {
        this.scorer = scorer
        n = scorer.length
        budget = edits.budget(n)
        excludedNode = literalNode
        stCount = 0
        heapSize = 0
        resultCount = 0
        worstScore = Float.NEGATIVE_INFINITY
        bestScore = Float.NEGATIVE_INFINITY
        expansions = 0
        stamp++
        push(dict.root, 0, 0, 0f, 0f, 0)
        while (heapSize > 0 && expansions < search.maxExpansions) {
            val s = pop()
            if (stBound[s] < threshold()) break
            if ((expansions and 31) == 31 && isStale()) return false
            expansions++
            expand(s)
        }
        return true
    }

    private fun threshold(): Float {
        if (resultCount == 0) return Float.NEGATIVE_INFINITY
        val beam = bestScore - search.beamWidth
        return if (resultCount >= search.poolSize) maxOf(worstScore, beam) else beam
    }

    private fun expand(s: Int) {
        val node = stNode[s]
        val pos = stPos[s]
        val used = stEdits[s]
        val spatial = stSpatial[s]
        val penalty = stPenalty[s]
        val extra = stExtra[s]
        val firstChild = dict.firstChild(node)
        val childCount = dict.childCount(node)
        if (pos == n) {
            if (dict.isTerminal(node)) emit(node, spatial, penalty, used, extra)
            if (extra < search.maxCompletionChars) {
                for (j in 0 until childCount) push(
                    firstChild + j,
                    pos,
                    used,
                    spatial,
                    penalty,
                    extra + 1,
                )
            }
            return
        }
        val canEdit = used < budget
        val typed = scorer.typedChar(pos)
        val prev = if (node == dict.root) NO_CHAR else dict.nodeChar(node)
        val first = if (pos == 0) edits.firstCharPenalty else 0f
        // Every edit after the first costs this much more (apostrophe edits are exempt).
        val multi = if (used > 0) edits.multiEditPenalty else 0f
        if (canEdit) {
            // The typed char is spurious.
            val cost =
                when {
                    typed == '\'' -> edits.apostrophePenalty
                    typed == prev -> edits.doubleLetterPenalty + multi
                    else -> edits.insertionPenalty + multi
                }
            push(node, pos + 1, used + 1, spatial, penalty + cost + first, extra)
        }
        val canSwap = canEdit && pos + 1 < n && typed != scorer.typedChar(pos + 1)
        for (j in 0 until childCount) {
            val child = firstChild + j
            val c = dict.nodeChar(child)
            val same = scorer.sameLetter(c, pos)
            if (same) {
                push(child, pos + 1, used, spatial + scorer.ll(c, pos), penalty, extra)
            } else if (canEdit) {
                var ll = scorer.ll(c, pos)
                var cost = edits.substitutionPenalty
                var viable = ll >= edits.substitutionCutoff
                if (edits.phoneticSubstitutions && Phonetic.confusable(typed, c)) {
                    // The user hit the key they meant: score the touch against the typed key.
                    val alt = scorer.exactLogLikelihood(pos)
                    if (!viable || alt + edits.phoneticSubstitutionPenalty > ll + cost) {
                        ll = alt
                        cost = edits.phoneticSubstitutionPenalty
                        viable = true
                    }
                }
                if (viable)
                    push(
                        child,
                        pos + 1,
                        used + 1,
                        spatial + ll,
                        penalty + cost + first + multi,
                        extra,
                    )
            }
            if (!canEdit) continue
            // The word has a char the user didn't type.
            val cost =
                when {
                    c == '\'' -> edits.apostrophePenalty
                    c == prev -> edits.doubleLetterPenalty + multi
                    else -> edits.deletionPenalty + multi
                }
            push(child, pos, used + 1, spatial, penalty + cost + first, extra)
            if (canSwap && !same) {
                val ll1 = scorer.ll(c, pos + 1)
                if (ll1 >= edits.transpositionCutoff) {
                    val swapCost =
                        if (
                            Phonetic.isVowel(typed) && Phonetic.isVowel(scorer.typedChar(pos + 1))
                        ) {
                            edits.vowelTranspositionPenalty
                        } else {
                            edits.transpositionPenalty
                        }
                    swap(child, pos, used, spatial + ll1, penalty + first + swapCost + multi, extra)
                }
            }
        }
    }

    /**
     * Second half of a transposition: [mid] matched the later typed char, now match the earlier.
     */
    private fun swap(mid: Int, pos: Int, used: Int, spatial: Float, penalty: Float, extra: Int) {
        val firstChild = dict.firstChild(mid)
        val childCount = dict.childCount(mid)
        for (j in 0 until childCount) {
            val child = firstChild + j
            val ll2 = scorer.ll(dict.nodeChar(child), pos)
            if (ll2 >= edits.transpositionCutoff) {
                push(
                    child,
                    pos + 2,
                    used + 1,
                    spatial + ll2,
                    penalty,
                    extra,
                )
            }
        }
    }

    private fun emit(node: Int, spatial: Float, penalty: Float, used: Int, extra: Int) {
        if (node == excludedNode) return
        val uni = bestSuggestableLogProb(node)
        if (uni.isNaN()) return
        val score = spatial + penalty + extra * completionPenalty + lambda * uni
        for (k in 0 until resultCount) {
            if (resultNode[k] == node) {
                if (score > resultScore[k]) store(k, node, spatial, penalty, used, extra, score)
                refreshBounds()
                return
            }
        }
        if (resultCount < search.poolSize) {
            store(resultCount++, node, spatial, penalty, used, extra, score)
        } else {
            var worst = 0
            for (k in 1 until resultCount) if (resultScore[k] < resultScore[worst]) worst = k
            if (score <= resultScore[worst]) return
            store(worst, node, spatial, penalty, used, extra, score)
        }
        refreshBounds()
    }

    private fun store(
        k: Int,
        node: Int,
        sp: Float,
        pen: Float,
        used: Int,
        extra: Int,
        score: Float,
    ) {
        resultNode[k] = node
        resultSpatial[k] = sp
        resultPenalty[k] = pen
        resultEdits[k] = used
        resultExtra[k] = extra
        resultScore[k] = score
    }

    private fun refreshBounds() {
        var best = Float.NEGATIVE_INFINITY
        var worst = Float.POSITIVE_INFINITY
        for (k in 0 until resultCount) {
            best = maxOf(best, resultScore[k])
            worst = minOf(worst, resultScore[k])
        }
        bestScore = best
        worstScore = worst
    }

    /** Highest unigram log-probability among the node's surface forms we may suggest. */
    private fun bestSuggestableLogProb(node: Int): Float {
        var best = Float.NaN
        for (k in 0 until dict.wordIdCountAt(node)) {
            val id = dict.wordIdAt(node, k)
            if (dict.flags(id) and EXCLUDED_FLAGS != 0) continue
            val lp = dict.logProb(id)
            if (best.isNaN() || lp > best) best = lp
        }
        return best
    }

    // ------------------------------------------------------------ queue

    private fun push(node: Int, pos: Int, used: Int, spatial: Float, penalty: Float, extra: Int) {
        val total = spatial + penalty + extra * completionPenalty
        val bound = total + lambda * dict.maxLogProbInSubtree(node)
        if (bound < threshold() || stCount >= stNode.size) return
        if (spatial + penalty < -MAX_PATH_COST) return
        if (!markVisited(node, pos, used, total)) return
        val s = stCount++
        stNode[s] = node
        stPos[s] = pos
        stEdits[s] = used
        stExtra[s] = extra
        stSpatial[s] = spatial
        stPenalty[s] = penalty
        stBound[s] = bound
        siftUp(heapSize++, s)
    }

    /** False if an equal-or-better path already reached the same (node, pos, edits). */
    private fun markVisited(node: Int, pos: Int, used: Int, total: Float): Boolean {
        val key = (node.toLong() shl 16) or (pos.toLong() shl 4) or used.toLong()
        var slot = (key * -0x61c8864680b583ebL ushr 40).toInt() and visitedMask
        while (visitedStamp[slot] == stamp) {
            if (visitedKeys[slot] == key) {
                if (visitedValues[slot] >= total) return false
                visitedValues[slot] = total
                return true
            }
            slot = (slot + 1) and visitedMask
        }
        visitedStamp[slot] = stamp
        visitedKeys[slot] = key
        visitedValues[slot] = total
        return true
    }

    /** Max-heap order on the bound; ties go to the older state (smaller index). */
    private fun before(boundA: Float, a: Int, boundB: Float, b: Int): Boolean =
        boundA > boundB || (boundA == boundB && a < b)

    private fun siftUp(start: Int, s: Int) {
        val bound = stBound[s]
        var i = start
        while (i > 0) {
            val parent = (i - 1) ushr 2
            if (!before(bound, s, heapBound[parent], heap[parent])) break
            heap[i] = heap[parent]
            heapBound[i] = heapBound[parent]
            i = parent
        }
        heap[i] = s
        heapBound[i] = bound
    }

    private fun pop(): Int {
        val top = heap[0]
        val size = --heapSize
        val last = heap[size]
        val lastBound = heapBound[size]
        var i = 0
        while (true) {
            val first = 4 * i + 1
            if (first >= size) break
            // Best of up to four children (the order is total, so the pop sequence is the same as
            // with any other heap arity).
            var child = first
            val end = minOf(first + 4, size)
            for (c in first + 1 until end) {
                if (before(heapBound[c], heap[c], heapBound[child], heap[child])) child = c
            }
            if (!before(heapBound[child], heap[child], lastBound, last)) break
            heap[i] = heap[child]
            heapBound[i] = heapBound[child]
            i = child
        }
        if (size > 0) {
            heap[i] = last
            heapBound[i] = lastBound
        }
        return top
    }

    private companion object {
        const val NO_CHAR = '\u0000'

        /** Paths costing more than this (before the LM) are hopeless. */
        const val MAX_PATH_COST = 24f
        const val EXCLUDED_FLAGS = WordFlags.POSSIBLY_OFFENSIVE or WordFlags.NOT_A_WORD
    }
}
