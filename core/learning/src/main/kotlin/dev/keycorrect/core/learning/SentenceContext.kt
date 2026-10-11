package dev.keycorrect.core.learning

/**
 * The learning context of a word: the last words of the sentence before it. The keyboard records
 * correction-memory buckets with this and the decoder looks them up with it, so both always agree.
 * The character classes are shared with the decoder's tokenizer for the same reason.
 */
object SentenceContext {
    /** Words kept as context (the learning layer uses at most two). */
    const val CONTEXT_WORDS = 2

    /**
     * The last [n] words of the sentence ending [before] (empty at a sentence start), normalised
     * the way the learning layer records context: apostrophes folded to `'`, case kept. Words may
     * contain inner apostrophes and hyphens ("don't", "well-known"). A sentence ends at a newline
     * or at `.`, `!`, `?`, `…` followed by whitespace (or the end of [before]).
     */
    fun lastWords(before: CharSequence, n: Int = CONTEXT_WORDS): List<String> {
        val out = ArrayDeque<String>(n + 1)
        var i = maxOf(0, before.length - WINDOW)
        while (i > 0 && i < before.length && isWordChar(before[i - 1]) && isWordChar(before[i])) i++
        while (i < before.length) {
            val c = before[i]
            when {
                isWordChar(c) -> {
                    val start = i
                    i++
                    while (i < before.length) {
                        val d = before[i]
                        if (isWordChar(d)) {
                            i++
                        } else if (
                            (isApostrophe(d) || isHyphen(d)) &&
                                i + 1 < before.length &&
                                isWordChar(before[i + 1])
                        ) {
                            i += 2
                        } else {
                            break
                        }
                    }
                    val sb = StringBuilder(i - start)
                    for (k in start until i) sb.append(
                        if (isApostrophe(before[k])) '\'' else before[k]
                    )
                    out.addLast(sb.toString())
                    if (out.size > n) out.removeFirst()
                }
                c == '\n' -> {
                    out.clear()
                    i++
                }
                isSentenceEnd(c) -> {
                    val next = if (i + 1 < before.length) before[i + 1] else ' '
                    if (next.isWhitespace()) out.clear()
                    i++
                }
                else -> i++
            }
        }
        return out.toList()
    }

    /** True if a sentence ends with [separator] (`.`, `!`, `?`, `…` or a line break). */
    fun endsSentence(separator: String?): Boolean =
        separator == null || separator.any { it == '\n' || isSentenceEnd(it) }

    private const val WINDOW = 192

    fun isWordChar(c: Char): Boolean =
        Character.isLetterOrDigit(c) ||
            Character.getType(c).let {
                it == Character.NON_SPACING_MARK.toInt() ||
                    it == Character.COMBINING_SPACING_MARK.toInt()
            }

    fun isApostrophe(c: Char): Boolean = c == '\'' || c == '’' || c == '‘' || c == 'ʼ'

    fun isHyphen(c: Char): Boolean = c == '-' || c == '‐' || c == '‑'

    /** Sentence-final punctuation; it ends a sentence only when followed by whitespace. */
    fun isSentenceEnd(c: Char): Boolean = c == '.' || c == '!' || c == '?' || c == '…'
}
