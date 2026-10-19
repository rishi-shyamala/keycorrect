package dev.keycorrect.sampleplugin

import java.util.Locale
import kotlin.math.ln

/**
 * A toy bigram language model, pure Kotlin (no Android), deterministic and stateless.
 *
 * `P(word | previous) = (c(previous, word) + k * V * P_u(word)) / (c(previous) + k * V)`: add-k
 * smoothing over a fixed vocabulary of V types (the listed words plus one bucket for every unknown
 * word), where the add-k mass is spread by the unigram probability `P_u` instead of evenly. With
 * uniform `P_u` this is the textbook add-k estimate; for a previous word that has no bigrams it
 * falls back to the unigram distribution. The counts are made up from list positions: the unigram
 * count of a word falls with its rank in [vocabulary], and so does the bigram count of a follower
 * in the lines of [bigramLines].
 *
 * The score of a candidate is the sum of `ln P` over its words, chained from the last word of the
 * context, so it is comparable between candidates for the same context (like the neural engine's).
 */
class BigramScorer(
    vocabulary: List<String>,
    bigramLines: String,
    private val k: Double = DEFAULT_K,
) {
    /** The number of listed words. */
    val vocabularySize: Int = vocabulary.size

    /** Types including the unknown-word bucket. */
    private val types = (vocabulary.size + 1).toDouble()

    private val unigramProbability: Map<String, Double>
    private val unknownProbability: Double

    /** previous word -> (follower -> count), and the total per previous word. */
    private val bigramCounts: Map<String, Map<String, Double>>
    private val bigramTotals: Map<String, Double>

    init {
        val counts = vocabulary.mapIndexed { rank, word -> word to UNIGRAM_SCALE / (rank + 1) }
        val total = counts.sumOf { it.second } + types
        unigramProbability = counts.associate { (word, count) -> word to (count + 1) / total }
        unknownProbability = 1 / total

        val followers = LinkedHashMap<String, MutableList<String>>()
        for (line in bigramLines.lineSequence().filter { ':' in it }) {
            val previous = line.substringBefore(':').trim()
            val list = followers.getOrPut(previous) { mutableListOf() }
            line.substringAfter(':').trim().split(WHITESPACE).filter(String::isNotEmpty).forEach {
                if (it !in list) list += it
            }
        }
        bigramCounts = followers.mapValues { (_, list) ->
            list.withIndex().associate { (i, word) -> word to BIGRAM_SCALE / (i + 1) }
        }
        bigramTotals = bigramCounts.mapValues { it.value.values.sum() }
    }

    /**
     * For each candidate, `ln P` of its words after [context], in the same order. [context] is the
     * text before the word being typed; a candidate may hold several words.
     */
    fun score(context: String, candidates: Array<String>): FloatArray {
        val previous = lastWord(context)
        return FloatArray(candidates.size) { logProbability(previous, candidates[it]).toFloat() }
    }

    /** `ln P(candidate | previous)`, where [previous] is a word or [BigramData.START]. */
    internal fun logProbability(previous: String, candidate: String): Double {
        var prev = previous
        var sum = 0.0
        val words = words(candidate)
        if (words.isEmpty()) return ln(probability(prev, null))
        for (word in words) {
            sum += ln(probability(prev, word))
            prev = word
        }
        return sum
    }

    private fun probability(previous: String, word: String?): Double {
        val prior = k * types
        val count = word?.let { bigramCounts[previous]?.get(it) } ?: 0.0
        val unigram = word?.let { unigramProbability[it] } ?: unknownProbability
        return (count + prior * unigram) / ((bigramTotals[previous] ?: 0.0) + prior)
    }

    internal companion object {
        const val DEFAULT_K = 0.1
        private const val UNIGRAM_SCALE = 10_000.0
        private const val BIGRAM_SCALE = 20.0

        private val WHITESPACE = Regex("\\s+")
        private val NOT_WORD = Regex("[^\\p{L}\\p{N}']+")
        private val SENTENCE_END = Regex("[.!?]\\s*$")

        /** The shared instance over the built-in data; the scorer is immutable. */
        val default: BigramScorer by lazy {
            BigramScorer(BigramData.vocabulary, BigramData.bigramLines)
        }

        /** Lower-case words of [text]; apostrophes stay inside words ("don't"). */
        fun words(text: String): List<String> =
            text
                .lowercase(Locale.ROOT)
                .replace('’', '\'')
                .split(NOT_WORD)
                .map { it.trim('\'') }
                .filter(String::isNotEmpty)

        /**
         * The word the next one follows: the last word of [context], or [BigramData.START] for an
         * empty context and after sentence-ending punctuation or a line break.
         */
        fun lastWord(context: String): String {
            if (context.isBlank()) return BigramData.START
            val atBoundary =
                SENTENCE_END.containsMatchIn(context) || context.trimEnd(' ', '\t').endsWith('\n')
            return if (atBoundary) BigramData.START
            else words(context).lastOrNull() ?: BigramData.START
        }
    }
}
