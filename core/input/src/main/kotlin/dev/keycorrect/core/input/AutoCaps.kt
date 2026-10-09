package dev.keycorrect.core.input

/**
 * Auto-capitalisation computed from the text before the cursor, so the keyboard needs no editor
 * round trip (`getCursorCapsMode`) per key while it already holds that text. Follows the rules of
 * `TextUtils.getCapsMode`: a capital at the start of the field or a line, after a sentence end
 * (`.`, `!`, `?` plus whitespace, ignoring closing and opening quotes and brackets) and, for
 * [AutoCapitalize.Words], after any whitespace. A period inside an abbreviation such as "e.g. "
 * does not end a sentence.
 */
object AutoCaps {
    /**
     * Whether the next character should be a capital after [before], the text before the cursor.
     * [startKnown] is whether [before] begins at the start of the field; when it does not and the
     * answer depends on what lies beyond its first character, the result is null (ask the editor).
     */
    fun shouldCapitalize(
        before: CharSequence,
        mode: AutoCapitalize,
        startKnown: Boolean = true,
    ): Boolean? {
        when (mode) {
            AutoCapitalize.None -> return false
            AutoCapitalize.Characters -> return true
            else -> Unit
        }
        // Back over opening punctuation.
        var i = before.length
        while (i > 0 && isOpening(before[i - 1])) i--
        // Start of a paragraph, with optional blanks.
        var j = i
        while (j > 0 && (before[j - 1] == ' ' || before[j - 1] == '\t')) j--
        if (j == 0) return if (startKnown) true else null
        if (before[j - 1] == '\n') return true
        if (mode == AutoCapitalize.Words) return i != j
        // Sentences need a blank between the terminator and the cursor.
        if (i == j) return false
        // Back over closing punctuation.
        while (j > 0 && isClosing(before[j - 1])) j--
        if (j == 0) return if (startKnown) true else null
        val terminator = before[j - 1]
        if (terminator != '.' && terminator != '?' && terminator != '!') return false
        if (terminator == '.') {
            // "e.g. " and "U.S. " are not sentence ends: a period earlier in the same token.
            var k = j - 2
            while (k >= 0) {
                val c = before[k]
                if (c == '.') return false
                if (!c.isLetter()) break
                k--
            }
        }
        return true
    }

    private fun isOpening(c: Char) =
        c == '"' ||
            c == '\'' ||
            Character.getType(c) == Character.START_PUNCTUATION.toInt() ||
            c == '“' ||
            c == '‘' ||
            c == '¿' ||
            c == '¡'

    private fun isClosing(c: Char) =
        c == '"' ||
            c == '\'' ||
            Character.getType(c) == Character.END_PUNCTUATION.toInt() ||
            c == '”' ||
            c == '’'
}
