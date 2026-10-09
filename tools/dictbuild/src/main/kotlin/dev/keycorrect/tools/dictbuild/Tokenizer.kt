package dev.keycorrect.tools.dictbuild

/**
 * Keyboard-oriented tokenizer. A token is a run of letters/digits (plus combining marks) that may
 * contain *internal* apostrophes or hyphens, so "don't", "I'm" and "e-mail" stay single tokens like
 * in the AOSP list; leading/trailing connectors and every other character are separators.
 * Typographic apostrophes (U+2019, U+02BC) are normalised to `'`, and Unicode hyphens (U+2010,
 * U+2011) to `-`.
 */
object Tokenizer {
    fun isWordChar(c: Char): Boolean =
        Character.isLetterOrDigit(c) ||
            Character.getType(c).let {
                it == Character.NON_SPACING_MARK.toInt() ||
                    it == Character.COMBINING_SPACING_MARK.toInt()
            }

    private fun isApostrophe(c: Char) = c == '\'' || c == '’' || c == 'ʼ'

    private fun isHyphen(c: Char) = c == '-' || c == '‐' || c == '‑'

    fun tokenize(text: CharSequence, sink: (String) -> Unit) {
        val n = text.length
        var i = 0
        val sb = StringBuilder()
        while (i < n) {
            if (!isWordChar(text[i])) {
                i++
                continue
            }
            sb.setLength(0)
            while (i < n) {
                val c = text[i]
                if (isWordChar(c)) {
                    sb.append(c)
                    i++
                } else if (
                    (isApostrophe(c) || isHyphen(c)) &&
                        i + 1 < n &&
                        isWordChar(text[i + 1]) &&
                        sb.isNotEmpty()
                ) {
                    sb.append(if (isApostrophe(c)) '\'' else '-')
                    i++
                } else {
                    break
                }
            }
            sink(sb.toString())
        }
    }

    fun tokens(text: CharSequence): List<String> =
        ArrayList<String>().also { tokenize(text, it::add) }

    /** True for tokens with no letters at all ("1990", "3-4"). */
    fun isNumeric(token: String): Boolean = token.none { it.isLetter() }
}
