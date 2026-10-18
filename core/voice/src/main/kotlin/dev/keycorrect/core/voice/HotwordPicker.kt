package dev.keycorrect.core.voice

/**
 * Picks the user's personal words worth giving to a recogniser as hotwords (contextual biasing).
 */
object HotwordPicker {
    const val DEFAULT_LIMIT = 100
    private const val MIN_LENGTH = 2
    private const val MAX_LENGTH = 30
    private val LETTERS = Regex("\\p{L}+")

    /**
     * The [limit] heaviest of [words] (word, weight) that are letters only,
     * [MIN_LENGTH]..[MAX_LENGTH] long and not [isCommonWord] (a recogniser knows those anyway).
     * Case-insensitive duplicates keep the heaviest; the result is heaviest first, ties
     * alphabetical.
     */
    fun pick(
        words: List<Pair<String, Double>>,
        limit: Int = DEFAULT_LIMIT,
        isCommonWord: (String) -> Boolean = { false },
    ): List<String> =
        words
            .filter { (word, weight) ->
                weight > 0.0 &&
                    weight.isFinite() &&
                    word.length in MIN_LENGTH..MAX_LENGTH &&
                    LETTERS.matches(word) &&
                    !isCommonWord(word.lowercase())
            }
            .groupBy { it.first.lowercase() }
            .map { (_, same) -> same.maxWith(compareBy({ it.second }, { it.first })) }
            .sortedWith(
                compareByDescending<Pair<String, Double>> { it.second }
                    .thenBy { it.first.lowercase() }
            )
            .take(limit)
            .map { it.first }
}
