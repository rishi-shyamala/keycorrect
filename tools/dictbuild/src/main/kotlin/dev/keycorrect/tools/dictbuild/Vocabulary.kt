package dev.keycorrect.tools.dictbuild

import java.util.Locale

/**
 * Word-id table over the AOSP vocabulary. Ids are `1..size`; id `0` is the sentence-start marker
 * [START] and doubles as "not in vocabulary" in [lookup] results. Entries flagged `not_a_word`
 * (shortcut sources such as lowercase "i") are not vocabulary words and get no id.
 */
class Vocabulary(entries: List<VocabEntry>) {
    private val words = ArrayList<String>().apply { add(START) }
    private val exact = HashMap<String, Int>()
    private val lowerBest = HashMap<String, Int>()
    private val rank = ArrayList<Int>().apply { add(0) }

    val size: Int
        get() = words.size - 1

    init {
        for (e in entries) {
            if (e.notAWord || exact.containsKey(e.word)) continue
            val id = words.size
            words.add(e.word)
            rank.add(e.originalFreq)
            exact[e.word] = id
        }
        // Best case-variant per lowercase key: highest originalFreq, ties prefer the all-lowercase
        // form, then the lexicographically smaller one (deterministic).
        for (id in 1 until words.size) {
            val w = words[id]
            val key = w.lowercase(Locale.ROOT)
            val cur = lowerBest[key]
            if (cur == null || better(id, cur, key)) lowerBest[key] = id
        }
    }

    private fun better(a: Int, b: Int, key: String): Boolean {
        if (rank[a] != rank[b]) return rank[a] > rank[b]
        val aLower = words[a] == key
        val bLower = words[b] == key
        if (aLower != bLower) return aLower
        return words[a] < words[b]
    }

    fun word(id: Int): String = words[id]

    /**
     * Maps a corpus token to a vocabulary id (0 if out of vocabulary): exact surface form first;
     * otherwise case-insensitively to the most frequent variant. A sentence-initial capitalised
     * token ("The", "Will") prefers its exact lowercase form when one exists (unless the
     * capitalised form is clearly a proper noun, see [PROPER_NOUN_MARGIN]), so sentence-initial
     * capitalisation does not produce separate entries for ordinary words.
     */
    fun lookup(token: String, sentenceInitial: Boolean): Int {
        if (sentenceInitial && token.length > 1 && token[0].isUpperCase()) {
            val lower = token.lowercase(Locale.ROOT)
            if (lower != token) {
                val lowerId = exact[lower]
                if (lowerId != null) {
                    // Keep a capitalised form only when it is clearly the proper noun ("Tom"
                    // vs the rare "tom"); otherwise "Will"/"Apple" at sentence start fold into
                    // "will"/"apple".
                    val capId = exact[token]
                    if (capId == null || rank[lowerId] + PROPER_NOUN_MARGIN >= rank[capId]) {
                        return lowerId
                    }
                }
            }
        }
        exact[token]?.let {
            return it
        }
        return lowerBest[token.lowercase(Locale.ROOT)] ?: 0
    }

    companion object {
        const val START = "<s>"

        /**
         * AOSP frequency steps (each ~1.15x) a capitalised form must lead its lowercase twin by.
         */
        const val PROPER_NOUN_MARGIN = 10
    }
}
