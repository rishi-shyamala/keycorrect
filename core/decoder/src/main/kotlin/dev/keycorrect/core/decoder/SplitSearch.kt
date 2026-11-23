package dev.keycorrect.core.decoder

import dev.keycorrect.core.dictionary.Dictionary
import dev.keycorrect.core.dictionary.WordFlags

/** A word typed without its space, split into two words ([first] and [second], lowercase keys). */
internal class SplitResult(
    val firstSurface: String,
    val secondSurface: String,
    val firstId: Int,
    val secondId: Int,
    /** `ln P(first | context) + ln P(second | context, first)`, personal n-gram included. */
    val lm: Float,
)

/**
 * Finds the best way to read an unknown word as two known words ("ofthe" -> "of the"): every split
 * point where both halves are dictionary words (or the user's own), scored by the contextual
 * language model; the best one wins. Exact splits only. Stateful scratch-free, one decoder thread.
 */
internal class SplitSearch(
    private val dict: Dictionary,
    private val lm: LanguageModel,
    private val lexicon: UserLexicon,
    private val config: SplitConfig,
    private val userWordLogProb: Float,
) {
    /** The halves' ids of one half-word: the best word at a trie node, or a user word (id -1). */
    private var bestId = -1
    private var bestSurface = ""
    private var bestLp = Float.NEGATIVE_INFINITY

    /** True if the unknown word [key] (a [Text.key]) is worth a split search at all. */
    fun applies(key: String): Boolean =
        key.length in config.minLength..config.maxLength && key.all { it.isLetter() }

    /**
     * The best split of [key], or null; [blocked] vetoes a half (the user's never-suggest list).
     */
    fun best(key: String, blocked: (String) -> Boolean): SplitResult? {
        var result: SplitResult? = null
        for (cut in 1 until key.length) {
            val found = splitAt(key, cut, blocked)
            if (found != null && (result == null || found.lm > result.lm)) result = found
        }
        return result
    }

    private fun splitAt(key: String, cut: Int, blocked: (String) -> Boolean): SplitResult? {
        val head = key.substring(0, cut)
        val tail = key.substring(cut)
        val ok = lengthOk(head) && lengthOk(tail) && tail !in CONTRACTION_TAILS
        val first = if (ok) readFirst(head, blocked) else null
        return first?.let { readSecond(tail, it, blocked) }
    }

    /** The best reading of the first half, as a result with an empty second half; or null. */
    private fun readFirst(head: String, blocked: (String) -> Boolean): SplitResult? {
        scan(head, blocked) { id, _ -> lm.logProb(id) }
        return if (bestLp == Float.NEGATIVE_INFINITY) null
        else SplitResult(bestSurface, "", bestId, -1, bestLp)
    }

    private fun readSecond(
        tail: String,
        first: SplitResult,
        blocked: (String) -> Boolean,
    ): SplitResult? {
        scan(tail, blocked) { id, surface ->
            lm.logProbAfter(first.firstId, first.firstSurface, id, surface)
        }
        return if (bestLp == Float.NEGATIVE_INFINITY) null
        else SplitResult(first.firstSurface, bestSurface, first.firstId, bestId, first.lm + bestLp)
    }

    /** A half is at least [SplitConfig.minHalfLength] chars; one letter only if it is allowed. */
    private fun lengthOk(half: String): Boolean =
        half.length >= config.minHalfLength || (half.length == 1 && half[0] in SINGLE_LETTER_WORDS)

    /** Leaves the best reading of [word] (dictionary word or user word) in the best* fields. */
    private inline fun scan(
        word: String,
        blocked: (String) -> Boolean,
        lp: (Int, String) -> Float,
    ) {
        bestId = -1
        bestSurface = ""
        bestLp = Float.NEGATIVE_INFINITY
        val node = dict.findNode(word)
        val inDictionary = node >= 0 && dict.isTerminal(node)
        if (inDictionary) {
            for (j in 0 until dict.wordIdCountAt(node)) {
                val id = dict.wordIdAt(node, j)
                val surface = dict.word(id)
                if (dict.flags(id) and EXCLUDED_FLAGS == 0 && !blocked(surface)) {
                    consider(id, surface, lp(id, surface))
                }
            }
        } else if (lexicon.containsWord(word) && !blocked(word)) {
            consider(-1, word, lm.userWordLogProb(userWordLogProb, word))
        }
    }

    private fun consider(id: Int, surface: String, lp: Float) {
        if (lp > bestLp) {
            bestId = id
            bestSurface = surface
            bestLp = lp
        }
    }

    private companion object {
        /** The only one-letter halves allowed. */
        const val SINGLE_LETTER_WORDS = "ai"

        /** Contraction endings ("do|nt", "we|ll"): never a second word of a split. */
        val CONTRACTION_TAILS = setOf("nt", "ll", "ve", "re")

        const val EXCLUDED_FLAGS = WordFlags.POSSIBLY_OFFENSIVE or WordFlags.NOT_A_WORD
    }
}
