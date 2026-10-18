package dev.keycorrect.core.input

import java.text.BreakIterator

/** Grapheme-cluster helpers so cursor edits never split an emoji, ZWJ sequence or accent. */
object Graphemes {
    /**
     * One iterator per thread: [BreakIterator] is stateful and not thread-safe, and creating one
     * loads rule data, which is too slow to repeat on every backspace.
     */
    private val iterators = ThreadLocal.withInitial { BreakIterator.getCharacterInstance() }

    /** Number of UTF-16 chars in the last grapheme cluster of [text], or 0 if empty. */
    fun lastClusterLength(text: CharSequence): Int {
        if (text.isEmpty()) return 0
        val iterator = iterators.get()!!
        iterator.setText(text.toString())
        val end = iterator.last()
        val start = iterator.previous()
        return if (start == BreakIterator.DONE) end else end - start
    }

    /**
     * Number of UTF-16 chars in the grapheme cluster that starts at [from] in [text] (0 at the end
     * of it). [from] should be a cluster boundary, as a cursor position is.
     */
    fun clusterLengthAt(text: String, from: Int): Int {
        if (from < 0 || from >= text.length) return 0
        val iterator = iterators.get()!!
        iterator.setText(text)
        val next = iterator.following(from)
        return if (next == BreakIterator.DONE) text.length - from else next - from
    }
}
