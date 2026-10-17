package dev.keycorrect.feature.keyboard

import java.util.Locale

/** A range of chars in some text: [start] until [end]. */
internal data class TextSpan(val start: Int, val end: Int)

/**
 * Where words, sentences and lines begin and end, for the editing panel and the selection strip.
 * Pure functions of the text around the selection, so they are unit-tested without an editor. Each
 * returns null where there is nothing to select. Text is read only from fields that allow it (never
 * sensitive ones); see [EditingController].
 */
internal object TextUnits {
    private const val TERMINATORS = ".!?…"
    private const val CJK_TERMINATORS = "。！？"
    private const val CLOSERS = "\"')]}”’»」』"

    /**
     * The chars of [before] (the text up to the cursor) that deleting [words] whole words backward
     * removes. A word's trailing spaces belong to it, so "foo bar |" loses "bar " for one word.
     */
    fun wordsBackLength(before: CharSequence, words: Int): Int {
        var i = before.length
        repeat(words) {
            while (i > 0 && before[i - 1].isWhitespace()) i--
            while (i > 0 && !before[i - 1].isWhitespace()) i--
        }
        // A window that begins inside a surrogate pair must not cut it in half.
        if (i == 0 && before.isNotEmpty() && before[0].isLowSurrogate()) i = 1
        return before.length - i
    }

    /**
     * The word(s) enclosing [start] until [end]: both ends grow to the edges of their words. A
     * cursor touching no word, only a space after one ("foo |"), takes the word before it.
     */
    fun expandWord(text: CharSequence, start: Int, end: Int): TextSpan? {
        var from = start
        var to = end
        while (from > 0 && isWordChar(text, from - 1)) from--
        while (to < text.length && isWordChar(text, to)) to++
        return when {
            from < to -> TextSpan(from, to)
            start == end -> wordBeforeSpace(text, start)
            else -> null
        }
    }

    /** The word just before [cursor] when only one space separates them ("foo |"), else null. */
    private fun wordBeforeSpace(text: CharSequence, cursor: Int): TextSpan? {
        val back = cursor - 1
        if (back <= 0 || text[back] != ' ' || !isWordChar(text, back - 1)) return null
        var from = back
        while (from > 0 && isWordChar(text, from - 1)) from--
        return TextSpan(from, back)
    }

    /** The sentence(s) enclosing [start] until [end], without the whitespace around them. */
    fun expandSentence(text: CharSequence, start: Int, end: Int): TextSpan? {
        val from = sentenceStart(text, start)
        val to = sentenceEnd(text, end)
        return if (from < to) TextSpan(from, to) else null
    }

    /** The line(s) (up to the line breaks, `\n` or `\r\n`) enclosing [start] until [end]. */
    fun expandLine(text: CharSequence, start: Int, end: Int): TextSpan? {
        var from = start
        var to = end
        while (from > 0 && text[from - 1] != '\n') from--
        while (to < text.length && text[to] != '\n') to++
        if (to > from && text[to - 1] == '\r') to--
        return if (from < to) TextSpan(from, to) else null
    }

    private fun isWordChar(text: CharSequence, i: Int): Boolean {
        val c = text[i]
        return when {
            c.isLetterOrDigit() || c == '_' -> true
            c == '\'' || c == '’' ->
                i > 0 &&
                    i + 1 < text.length &&
                    text[i - 1].isLetterOrDigit() &&
                    text[i + 1].isLetterOrDigit()
            else ->
                Character.getType(c).let {
                    it == Character.NON_SPACING_MARK.toInt() ||
                        it == Character.COMBINING_SPACING_MARK.toInt()
                }
        }
    }

    /**
     * Where the sentence ended by the terminator at [i] stops (after any closing quotes), or -1 if
     * the char at [i] does not end a sentence: ".", "!", "?" and "…" must be followed by white
     * space or the end of the text.
     */
    private fun terminatorEnd(text: CharSequence, i: Int): Int {
        val c = text[i]
        val cjk = CJK_TERMINATORS.indexOf(c) >= 0
        if (!cjk && TERMINATORS.indexOf(c) < 0) return -1
        var k = i + 1
        while (k < text.length && CLOSERS.indexOf(text[k]) >= 0) k++
        return if (cjk || k >= text.length || text[k].isWhitespace()) k else -1
    }

    private fun sentenceStart(text: CharSequence, at: Int): Int {
        for (i in at - 1 downTo 0) {
            val next =
                if (text[i] == '\n') {
                    var k = i + 1
                    while (k < text.length && (text[k] == ' ' || text[k] == '\t')) k++
                    k
                } else {
                    val stop = terminatorEnd(text, i)
                    var k = stop
                    while (k in 0 until text.length && text[k].isWhitespace()) k++
                    if (stop < 0) -1 else k
                }
            if (next in 0..at) return next
        }
        return 0
    }

    private fun sentenceEnd(text: CharSequence, at: Int): Int {
        // The char before [at] may itself end the sentence (a selection that already ends on it).
        for (i in maxOf(at - 1, 0) until text.length) {
            val stop = if (text[i] == '\n') i else terminatorEnd(text, i)
            if (stop >= at) return stop
        }
        var end = text.length
        while (end > 0 && text[end - 1].isWhitespace()) end--
        return end
    }
}

/** The case cycle of the selection strip: all lower, Title Case, UPPER, and back. */
internal object CaseCycle {
    private enum class Stage {
        Lower,
        Title,
        Upper,
    }

    /**
     * [text] in the next case of the cycle. Text in none of the three (mixed case) goes to lower
     * case first. A step that would not change the text (a single capital is both Title and UPPER)
     * is skipped.
     */
    fun next(text: String): String {
        val stages = Stage.entries
        val current =
            when (text) {
                apply(text, Stage.Lower) -> Stage.Lower
                apply(text, Stage.Title) -> Stage.Title
                apply(text, Stage.Upper) -> Stage.Upper
                else -> Stage.Upper // mixed: the next step is lower case
            }
        for (step in 1..stages.size) {
            val candidate = apply(text, stages[(current.ordinal + step) % stages.size])
            if (candidate != text) return candidate
        }
        return text
    }

    private fun apply(text: String, stage: Stage): String =
        when (stage) {
            Stage.Lower -> text.lowercase(Locale.ROOT)
            Stage.Upper -> text.uppercase(Locale.ROOT)
            Stage.Title -> title(text)
        }

    private fun title(text: String): String {
        val out = StringBuilder(text.length)
        var wordStart = true
        for (c in text) {
            if (c.isLetter()) {
                out.append(if (wordStart) c.titlecaseChar() else c.lowercaseChar())
                wordStart = false
            } else {
                out.append(c)
                wordStart = !(c.isDigit() || c == '\'' || c == '’')
            }
        }
        return out.toString()
    }
}
