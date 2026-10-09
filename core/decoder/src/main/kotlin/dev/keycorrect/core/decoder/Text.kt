package dev.keycorrect.core.decoder

/** Character classes and normalisation shared by the context parser and the casing rules. */
internal object Text {
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

    /** The trie key of [text]: lowercase, with typographic apostrophes straightened. */
    fun key(text: CharSequence): String {
        val sb = StringBuilder(text.length)
        for (c in text) sb.append(if (isApostrophe(c)) '\'' else c)
        return sb.toString().lowercase()
    }

    /**
     * True for input that is not prose and must be left alone: digits, e-mail addresses, URLs and
     * paths ("4th", "a@b.c", "https://x", "and/or").
     */
    fun isNonProse(text: CharSequence): Boolean {
        for (i in 0 until text.length) {
            val c = text[i]
            if (c.isDigit() || c == '@' || c == '/') return true
        }
        return false
    }

    /**
     * True if [a] and [b] (compared by [key]) differ by exactly one swap of two adjacent chars
     * ("teh"/"the"); equal or longer-distance differences are false.
     */
    fun isSingleTransposition(a: CharSequence, b: CharSequence): Boolean {
        val x = key(a)
        val y = key(b)
        if (x.length != y.length || x.length < 2) return false
        var i = 0
        while (i < x.length && x[i] == y[i]) i++
        if (i + 1 >= x.length || x[i] != y[i + 1] || x[i + 1] != y[i]) return false
        return x.substring(i + 2) == y.substring(i + 2)
    }

    fun hasLetter(text: CharSequence): Boolean = text.any { it.isLetter() }

    fun capitalizeFirst(s: String): String =
        if (s.isEmpty()) s else s.substring(0, 1).uppercase() + s.substring(1)
}

/** Casing rules (§4.4): output follows the literal's capitalisation, dictionary casing wins. */
internal object Casing {
    /**
     * Shapes dictionary [surface] after the user's [literal]: ALL CAPS (two or more letters) gives
     * upper case; Initial Cap capitalises a plain lowercase surface; anything else keeps the
     * dictionary casing ("iPhone", "I", "McDonald").
     */
    fun apply(surface: String, literal: String): String {
        var letters = 0
        var upper = 0
        for (c in literal) {
            if (c.isLetter()) {
                letters++
                if (c.isUpperCase()) upper++
            }
        }
        val first = literal.firstOrNull { it.isLetter() }
        return when {
            letters >= 2 && upper == letters -> surface.uppercase()
            first != null && first.isUpperCase() && literal[0] == first && upper == 1 ->
                if (surface == surface.lowercase()) Text.capitalizeFirst(surface) else surface
            else -> surface
        }
    }

    /** True for ALL CAPS input of two or more letters (probably an acronym). */
    fun isAllCaps(literal: String): Boolean {
        val letters = literal.count { it.isLetter() }
        return letters >= 2 && literal.all { !it.isLetter() || it.isUpperCase() }
    }
}
