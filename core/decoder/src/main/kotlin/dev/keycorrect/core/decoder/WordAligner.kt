package dev.keycorrect.core.decoder

/**
 * Dynamic-programming counterpart of [TrieSearch] for a single known word (user words): aligns the
 * typed input with [align]'s word using the same substitution, insertion, deletion, transposition
 * and completion costs. Buffers are reused; not thread-safe.
 */
internal class WordAligner(
    private val edits: EditConfig,
    private val completionPenalty: Float,
    private val maxCompletionChars: Int,
    private val maxInputChars: Int,
) {
    private val stride = MAX_WORD + 1
    private val spatial = FloatArray((maxInputChars + 1) * stride)
    private val penalty = FloatArray((maxInputChars + 1) * stride)
    private val used = IntArray((maxInputChars + 1) * stride)
    private val reachable = BooleanArray((maxInputChars + 1) * stride)

    var resultSpatial = 0f
        private set

    var resultPenalty = 0f
        private set

    var resultEdits = 0
        private set

    var resultExtra = 0
        private set

    /**
     * Aligns the input bound in [scorer] with [word] (lowercase key). Returns true when a path with
     * at most [budget] edits and at most the allowed completion exists; the best one is left in the
     * `result*` properties.
     */
    fun align(scorer: TouchScorer, word: String, budget: Int): Boolean {
        val n = scorer.length
        val m = word.length
        if (m > MAX_WORD || m == 0 || m < n - budget || m > n + budget + maxCompletionChars)
            return false
        reachable[0] = true
        spatial[0] = 0f
        penalty[0] = 0f
        used[0] = 0
        for (i in 0..n) {
            for (j in 0..m) {
                if (i == 0 && j == 0) continue
                val cell = i * stride + j
                reachable[cell] = false
                if (i > 0 && j > 0) diagonal(scorer, word, i, j, cell, budget)
                if (i > 0) insertion(scorer, word, i, j, cell, budget)
                if (j > 0) deletion(word, i, j, cell, budget)
                if (i > 1 && j > 1) transposition(scorer, word, i, j, cell, budget)
            }
        }
        var bestTotal = Float.NEGATIVE_INFINITY
        var found = false
        for (j in 0..m) {
            val cell = n * stride + j
            val extra = m - j
            if (!reachable[cell] || extra > maxCompletionChars) continue
            val total = spatial[cell] + penalty[cell] + extra * completionPenalty
            if (total > bestTotal) {
                bestTotal = total
                resultSpatial = spatial[cell]
                resultPenalty = penalty[cell]
                resultEdits = used[cell]
                resultExtra = extra
                found = true
            }
        }
        return found
    }

    private fun offer(cell: Int, sp: Float, pen: Float, ed: Int) {
        if (!reachable[cell] || sp + pen > spatial[cell] + penalty[cell]) {
            reachable[cell] = true
            spatial[cell] = sp
            penalty[cell] = pen
            used[cell] = ed
        }
    }

    private fun diagonal(
        scorer: TouchScorer,
        word: String,
        i: Int,
        j: Int,
        cell: Int,
        budget: Int,
    ) {
        val from = (i - 1) * stride + j - 1
        if (!reachable[from]) return
        val c = word[j - 1]
        val pos = i - 1
        val ll = scorer.ll(c, pos)
        if (scorer.sameLetter(c, pos)) {
            offer(cell, spatial[from] + ll, penalty[from], used[from])
        } else if (used[from] < budget && ll >= edits.substitutionCutoff) {
            val cost = edits.substitutionPenalty + if (pos == 0) edits.firstCharPenalty else 0f
            offer(cell, spatial[from] + ll, penalty[from] + cost, used[from] + 1)
        }
    }

    private fun insertion(
        scorer: TouchScorer,
        word: String,
        i: Int,
        j: Int,
        cell: Int,
        budget: Int,
    ) {
        val from = (i - 1) * stride + j
        if (!reachable[from] || used[from] >= budget) return
        val typed = scorer.typedChar(i - 1)
        val cost =
            when {
                typed == '\'' -> edits.apostrophePenalty
                j > 0 && typed == word[j - 1] -> edits.doubleLetterPenalty
                else -> edits.insertionPenalty
            }
        offer(cell, spatial[from], penalty[from] + cost + first(i - 1), used[from] + 1)
    }

    private fun deletion(word: String, i: Int, j: Int, cell: Int, budget: Int) {
        val from = i * stride + j - 1
        if (!reachable[from] || used[from] >= budget) return
        val c = word[j - 1]
        val cost =
            when {
                c == '\'' -> edits.apostrophePenalty
                j > 1 && c == word[j - 2] -> edits.doubleLetterPenalty
                else -> edits.deletionPenalty
            }
        offer(cell, spatial[from], penalty[from] + cost + first(i), used[from] + 1)
    }

    private fun transposition(
        scorer: TouchScorer,
        word: String,
        i: Int,
        j: Int,
        cell: Int,
        budget: Int,
    ) {
        val from = (i - 2) * stride + j - 2
        if (!reachable[from] || used[from] >= budget) return
        if (scorer.typedChar(i - 2) == scorer.typedChar(i - 1)) return
        val ll1 = scorer.ll(word[j - 2], i - 1)
        val ll2 = scorer.ll(word[j - 1], i - 2)
        if (ll1 < edits.transpositionCutoff || ll2 < edits.transpositionCutoff) return
        offer(
            cell,
            spatial[from] + ll1 + ll2,
            penalty[from] + edits.transpositionPenalty + first(i - 2),
            used[from] + 1,
        )
    }

    private fun first(pos: Int): Float = if (pos == 0) edits.firstCharPenalty else 0f

    private companion object {
        const val MAX_WORD = 48
    }
}
