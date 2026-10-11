package dev.keycorrect.feature.keyboard.prediction

import dev.keycorrect.core.engineapi.TypingContext
import dev.keycorrect.core.input.AutoCapitalize
import dev.keycorrect.core.input.AutoCaps
import dev.keycorrect.core.input.Graphemes
import dev.keycorrect.core.input.TextEditor

/**
 * The text around the cursor, read from the editor when input starts and after an external cursor
 * move and then maintained locally by the owner ([PredictiveInput]), which also decides whether
 * reading is allowed at all (the gates on the field and the engine live there). The text before the
 * cursor (≤ [TypingContext.MAX_BEFORE_CHARS]) is read at once; the text after it (≤
 * [TypingContext.MAX_AFTER_CHARS]) lazily, since typing doesn't change it. Main thread only.
 */
internal class TextContextCache(private val editor: TextEditor) {
    private val text = StringBuilder()

    /** The cached text before the cursor; only meaningful while [beforeKnown]. */
    val before: CharSequence
        get() = text

    var beforeKnown = false
        private set

    /** The editor could be asked for the text before the cursor (it is null for some fields). */
    var beforeAvailable = false
        private set

    /** The cache starts mid-text: the editor had more than [TypingContext.MAX_BEFORE_CHARS]. */
    var beforeTruncated = false
        private set

    var after = ""
        private set

    var afterKnown = false
        private set

    /** The text before the cursor is known and was available from the editor. */
    val beforeReadable: Boolean
        get() = beforeKnown && beforeAvailable

    fun ensureBefore() {
        if (beforeKnown) return
        val read = editor.textBeforeCursor(TypingContext.MAX_BEFORE_CHARS)
        text.setLength(0)
        if (read != null) text.append(read)
        beforeAvailable = read != null
        beforeTruncated = read != null && read.length >= TypingContext.MAX_BEFORE_CHARS
        beforeKnown = true
    }

    /** The after-cursor half of reading the context; see [ensureBefore]. */
    fun ensureAfter() {
        if (!afterKnown) {
            after = editor.textAfterCursor(TypingContext.MAX_AFTER_CHARS)?.toString().orEmpty()
            afterKnown = true
        }
    }

    /** Whether a capital is wanted at the cursor, computed from the cached text. */
    fun capitalizeAtCursor(mode: AutoCapitalize): Boolean? =
        AutoCaps.shouldCapitalize(text, mode, startKnown = !beforeTruncated)

    fun appendBefore(added: String) {
        if (!beforeKnown) return
        text.append(added)
        val excess = text.length - TypingContext.MAX_BEFORE_CHARS
        if (excess > 0) {
            text.delete(0, excess)
            beforeTruncated = true
        }
    }

    /** Puts [added] in front of the text after the cursor. */
    fun prependAfter(added: String) {
        after = (added + after).take(TypingContext.MAX_AFTER_CHARS)
    }

    /** Drops the last grapheme before the cursor (a backspace); the cache must be [beforeKnown]. */
    fun dropLastCluster() {
        val n = Graphemes.lastClusterLength(text)
        text.setLength(maxOf(0, text.length - n))
        if (beforeTruncated && text.length < MIN_TRUSTED_BEFORE) beforeKnown = false
    }

    /** Drops the last [n] chars before the cursor. */
    fun dropBefore(n: Int) {
        text.setLength(maxOf(0, text.length - n))
    }

    /** Cuts the text before the cursor back to its first [length] chars. */
    fun truncateBefore(length: Int) {
        text.setLength(length)
    }

    fun cursorInsideWord(): Boolean = afterKnown && after.isNotEmpty() && after[0].isLetterOrDigit()

    /** True at the start of the field, after whitespace, or after opening punctuation. */
    fun atWordBoundary(): Boolean {
        val c = text.lastOrNull() ?: return !beforeTruncated
        return c.isWhitespace() || c in OPENING_PUNCTUATION
    }

    /**
     * Start of the word ending at the cursor in [before], or -1 if there isn't a usable one. Only a
     * word that a letter key would have started composing qualifies: it begins with a letter at a
     * word boundary (the start of the field, if the cached text isn't truncated; whitespace;
     * opening punctuation), so "foo.bar", "user@exa", "#tag" and "42x" are left alone.
     */
    fun trailingWordStart(): Int {
        var i = text.length
        if (i == 0 || !text[i - 1].isLetterOrDigit()) return -1
        while (i > 0 && (text[i - 1].isLetterOrDigit() || isApostrophe(text[i - 1]))) i--
        val boundary = i
        if (boundary == 0) {
            if (beforeTruncated) return -1
        } else {
            val c = text[boundary - 1]
            if (!c.isWhitespace() && c !in OPENING_PUNCTUATION) return -1
        }
        while (i < text.length && isApostrophe(text[i])) i++
        val length = text.length - i
        if (length == 0 || length > MAX_RECOMPOSE_CHARS) return -1
        if (!text[i].isLetter()) return -1
        return i
    }

    /** Forgets what was read; the next [ensureBefore] / [ensureAfter] reads the editor again. */
    fun invalidate() {
        beforeKnown = false
        afterKnown = false
    }

    /** [invalidate], and drops the cached text itself. */
    fun reset() {
        invalidate()
        text.setLength(0)
        beforeTruncated = false
        after = ""
    }

    private companion object {
        const val MAX_RECOMPOSE_CHARS = 32

        /** A truncated cache shorter than this is re-read before it is trusted. */
        const val MIN_TRUSTED_BEFORE = 32

        /** A word may start right after one of these. */
        const val OPENING_PUNCTUATION = "([{\"'“‘¿¡"
    }
}

internal fun isApostrophe(c: Char) = c == '\'' || c == '’' || c == 'ʼ'
