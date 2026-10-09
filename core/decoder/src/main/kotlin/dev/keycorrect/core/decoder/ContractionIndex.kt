package dev.keycorrect.core.decoder

import dev.keycorrect.core.dictionary.Dictionary
import dev.keycorrect.core.dictionary.WordFlags

/**
 * Maps an apostrophe-stripped lowercase key ("dont", "im", "youre") to the best dictionary word
 * that contains an interior apostrophe ("don't", "I'm", "you're"), best being the most frequent.
 * The engine uses it to correct contractions typed without the apostrophe independently of the
 * spatial model's sigma. Built lazily, once per dictionary, on first use.
 */
internal class ContractionIndex(private val dictionary: Dictionary) {
    /** A contraction: the dictionary word [id] with surface form [surface]. */
    class Entry(val id: Int, val surface: String)

    private val byKey: Map<String, Entry> by lazy { build() }

    /** The contraction whose apostrophe-stripped key is [strippedKey], or null. */
    fun lookup(strippedKey: String): Entry? = byKey[strippedKey]

    private fun build(): Map<String, Entry> {
        val best = HashMap<String, Entry>()
        val bestLogProb = HashMap<String, Float>()
        for (id in Dictionary.FIRST_WORD_ID until dictionary.wordCount) {
            if (dictionary.flags(id) and EXCLUDED_FLAGS != 0) continue
            val key = dictionary.keyOf(id) ?: continue
            if (!isContraction(key)) continue
            val stripped = key.filterNot { Text.isApostrophe(it) }
            val logProb = dictionary.logProb(id)
            if (logProb > (bestLogProb[stripped] ?: Float.NEGATIVE_INFINITY)) {
                bestLogProb[stripped] = logProb
                best[stripped] = Entry(id, dictionary.word(id))
            }
        }
        return best
    }

    private companion object {
        const val EXCLUDED_FLAGS = WordFlags.POSSIBLY_OFFENSIVE or WordFlags.NOT_A_WORD

        /** Letters, with apostrophes only between letters ("don't", not "'tis" or "dogs'"). */
        fun isContraction(key: String): Boolean {
            if (key.length < 3 || Text.isApostrophe(key.first()) || Text.isApostrophe(key.last())) {
                return false
            }
            return key.any { Text.isApostrophe(it) } &&
                key.all { it.isLetter() || Text.isApostrophe(it) }
        }
    }
}
