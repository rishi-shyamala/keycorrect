package dev.keycorrect.core.dictionary

import kotlin.math.ln

/** Bit flags attached to every word (see FORMAT.md). Returned by [Dictionary.flags]. */
object WordFlags {
    /** The word may be offensive; UIs should not suggest it unless typed in full. */
    const val POSSIBLY_OFFENSIVE = 1

    /** The word is a proper noun. */
    const val PROPER_NOUN = 2

    /**
     * The entry is not a real word to suggest (abbreviation, fragment); kept for correction only.
     */
    const val NOT_A_WORD = 4

    /** Set automatically by the writer when the surface form differs from its lowercase key. */
    const val HAS_CAPITAL = 8

    /** Set automatically on the reserved ids ([Dictionary.BOS_ID], [Dictionary.EOS_ID]). */
    const val SPECIAL = 16

    /** Flags a caller of [WordEntry] may set. */
    internal const val USER_MASK = POSSIBLY_OFFENSIVE or PROPER_NOUN or NOT_A_WORD
}

/**
 * Dictionary metadata stored in the META section.
 *
 * @property languageTag BCP-47 tag, e.g. "en-US".
 * @property buildDate free-form (ISO-8601 recommended). Supplied by the caller so output stays
 *   deterministic.
 * @property dataVersion monotonically increasing version of the source data.
 * @property attribution licence / source attribution text.
 */
data class DictionaryMetadata(
    val languageTag: String,
    val buildDate: String,
    val dataVersion: Int,
    val attribution: String,
)

/**
 * One surface form. [logProb] is the natural log of the unigram probability (any finite value; the
 * writer quantizes it to 8 bits over the observed range).
 */
data class WordEntry(val surface: String, val logProb: Float, val flags: Int = 0) {
    companion object {
        /** Builds an entry from raw counts: `logProb = ln(count / totalCount)`. */
        fun fromCount(surface: String, count: Long, totalCount: Long, flags: Int = 0): WordEntry {
            require(count > 0 && totalCount >= count) { "bad counts: $count / $totalCount" }
            return WordEntry(surface, ln(count.toDouble() / totalCount).toFloat(), flags)
        }
    }
}

/**
 * `P(w2 | w1)` as a natural-log probability. Use [Dictionary.BOS_TOKEN] as w1 for sentence starts.
 */
data class BigramEntry(val w1: String, val w2: String, val logProb: Float) {
    companion object {
        fun fromCount(w1: String, w2: String, count: Long, contextCount: Long): BigramEntry {
            require(count > 0 && contextCount >= count) { "bad counts: $count / $contextCount" }
            return BigramEntry(w1, w2, ln(count.toDouble() / contextCount).toFloat())
        }
    }
}

/** `P(w3 | w1, w2)` as a natural-log probability. */
data class TrigramEntry(val w1: String, val w2: String, val w3: String, val logProb: Float) {
    companion object {
        fun fromCount(
            w1: String,
            w2: String,
            w3: String,
            count: Long,
            contextCount: Long,
        ): TrigramEntry {
            require(count > 0 && contextCount >= count) { "bad counts: $count / $contextCount" }
            return TrigramEntry(w1, w2, w3, ln(count.toDouble() / contextCount).toFloat())
        }
    }
}

/** In-memory input for [DictionaryWriter]. N-grams reference words by surface string. */
class DictionaryData(
    val metadata: DictionaryMetadata,
    val words: List<WordEntry>,
    val bigrams: List<BigramEntry> = emptyList(),
    val trigrams: List<TrigramEntry> = emptyList(),
)
