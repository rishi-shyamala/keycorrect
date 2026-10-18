package dev.keycorrect.core.voice

/** A learned "dictated phrase becomes corrected phrase" pair and how often it was seen. */
data class Substitution(val from: String, val to: String, val count: Int)

/**
 * Learns STT substitutions from the user's corrections right after dictating (docs/PLAN.md §5.4),
 * e.g. "cube control" to "kubectl". [learn] aligns the dictated words with what the field then
 * contained and records each changed span of 1..[MAX_WORDS] words per side; [apply] rewrites the
 * pairs seen at least [MIN_COUNT] times. Bounded to [maxPairs], least recently used dropped first.
 * Pure in memory: [export] and [import] carry it to storage.
 */
class SubstitutionLearner(private val maxPairs: Int = DEFAULT_MAX_PAIRS) {
    private data class Key(val from: String, val to: String)

    private val pairs = LinkedHashMap<Key, Int>() // insertion order = least recently used first
    private var index: Map<String, Pair<String, Int>>? =
        null // from -> (to, count) for the usable pairs

    /** Records the substitutions in [corrected] relative to what was [dictated]. */
    @Synchronized
    fun learn(dictated: String, corrected: String) {
        val d = words(dictated).map { it.value }
        val c = words(corrected).map { it.value }
        for ((from, to) in hunks(d, c)) {
            val key = Key(from.joinToString(" ") { it.lowercase() }, to.joinToString(" "))
            val count = (pairs.remove(key) ?: 0) + 1
            pairs[key] = count
        }
        while (pairs.size > maxPairs) pairs.remove(pairs.keys.first())
        index = null
    }

    /**
     * [text] with every learned pair (seen at least [MIN_COUNT] times) replaced, whole words only.
     */
    @Synchronized
    fun apply(text: String): String {
        val table = index ?: buildIndex().also { index = it }
        if (table.isEmpty()) return text
        val tokens = words(text)
        val out = StringBuilder()
        var pos = 0
        var i = 0
        while (i < tokens.size) {
            val hit =
                (MAX_WORDS downTo 1).firstNotNullOfOrNull { n ->
                    matchAt(table, text, tokens, i, n)
                }
            if (hit == null) {
                i++
                continue
            }
            val (n, to, source) = hit
            out.append(text, pos, tokens[i].range.first).append(matchCase(source, to))
            pos = tokens[i + n - 1].range.last + 1
            touch(to, source.lowercaseWords())
            i += n
        }
        return out.append(text, pos, text.length).toString()
    }

    @Synchronized
    fun export(): List<Substitution> = pairs.map { (key, count) ->
        Substitution(key.from, key.to, count)
    }

    /**
     * Replaces the contents with [entries] (as [export] returned them, least recently used first).
     */
    @Synchronized
    fun import(entries: List<Substitution>) {
        pairs.clear()
        for (e in entries) {
            if (e.from.isNotBlank() && e.to.isNotBlank() && e.count > 0) {
                pairs[Key(e.from.lowercase(), e.to)] = e.count
            }
        }
        while (pairs.size > maxPairs) pairs.remove(pairs.keys.first())
        index = null
    }

    private data class Hit(val words: Int, val to: String, val source: String)

    private fun matchAt(
        table: Map<String, Pair<String, Int>>,
        text: String,
        tokens: List<MatchResult>,
        start: Int,
        n: Int,
    ): Hit? {
        val contiguous =
            start + n <= tokens.size &&
                (1 until n).all {
                    val gapStart = tokens[start + it - 1].range.last + 1
                    text.substring(gapStart, tokens[start + it].range.first).isBlank()
                }
        if (!contiguous) return null
        val source = text.substring(tokens[start].range.first, tokens[start + n - 1].range.last + 1)
        return table[source.lowercaseWords()]?.let { Hit(n, it.first, source) }
    }

    private fun String.lowercaseWords() = words(this).joinToString(" ") { it.value.lowercase() }

    private fun touch(to: String, from: String) {
        pairs.remove(Key(from, to))?.let { pairs[Key(from, to)] = it }
    }

    private fun buildIndex(): Map<String, Pair<String, Int>> {
        val best = HashMap<String, Pair<String, Int>>()
        for ((key, count) in pairs) {
            if (count >= MIN_COUNT && count >= (best[key.from]?.second ?: 0))
                best[key.from] = key.to to count
        }
        return best
    }

    private fun matchCase(source: String, to: String): String {
        val letters = source.filter { it.isLetter() }
        return when {
            letters.length > 1 &&
                letters.all { it.isUpperCase() } &&
                to.none { it.isUpperCase() } -> to.uppercase()
            letters.firstOrNull()?.isUpperCase() == true && to.first().isLowerCase() ->
                to.replaceFirstChar { it.uppercaseChar() }
            else -> to
        }
    }

    companion object {
        const val DEFAULT_MAX_PAIRS = 500
        const val MIN_COUNT = 2
        const val MAX_WORDS = 3
        private val WORD = Regex("[\\p{L}\\p{N}]+(?:['’\\-.][\\p{L}\\p{N}]+)*")

        private fun words(text: String) = WORD.findAll(text).toList()

        /**
         * The changed spans (dictated words, corrected words) of an edit-distance alignment, both
         * non-empty and short.
         */
        internal fun hunks(
            dictated: List<String>,
            corrected: List<String>,
        ): List<Pair<List<String>, List<String>>> {
            val same = { i: Int, j: Int -> dictated[i].equals(corrected[j], ignoreCase = true) }
            val dist = distances(dictated.size, corrected.size, same)
            val result = ArrayList<Pair<List<String>, List<String>>>()
            var d = ArrayDeque<String>()
            var c = ArrayDeque<String>()
            fun flush() {
                if (d.size in 1..MAX_WORDS && c.size in 1..MAX_WORDS) {
                    result += d.toList() to c.toList()
                }
                d = ArrayDeque()
                c = ArrayDeque()
            }
            // Walk back from the end, collecting runs of non-matching words.
            var i = dictated.size
            var j = corrected.size
            while (i > 0 || j > 0) {
                when (step(dist, i, j, same)) {
                    Step.Match -> {
                        flush()
                        i--
                        j--
                    }
                    Step.Replace -> {
                        d.addFirst(dictated[--i])
                        c.addFirst(corrected[--j])
                    }
                    Step.Delete -> d.addFirst(dictated[--i])
                    Step.Insert -> c.addFirst(corrected[--j])
                }
            }
            flush()
            return result.reversed()
        }

        private enum class Step {
            Match,
            Replace,
            Delete,
            Insert,
        }

        private fun step(dist: Array<IntArray>, i: Int, j: Int, same: (Int, Int) -> Boolean): Step {
            if (i > 0 && j > 0) {
                val equal = same(i - 1, j - 1)
                if (dist[i][j] == dist[i - 1][j - 1] + if (equal) 0 else 1) {
                    return if (equal) Step.Match else Step.Replace
                }
            }
            return if (i > 0 && dist[i][j] == dist[i - 1][j] + 1) Step.Delete else Step.Insert
        }

        private fun distances(n: Int, m: Int, same: (Int, Int) -> Boolean): Array<IntArray> {
            val dist = Array(n + 1) { IntArray(m + 1) }
            for (i in 0..n) dist[i][0] = i
            for (j in 0..m) dist[0][j] = j
            for (i in 1..n) {
                for (j in 1..m) {
                    val diagonal = dist[i - 1][j - 1] + if (same(i - 1, j - 1)) 0 else 1
                    dist[i][j] = minOf(diagonal, dist[i - 1][j] + 1, dist[i][j - 1] + 1)
                }
            }
            return dist
        }
    }
}
