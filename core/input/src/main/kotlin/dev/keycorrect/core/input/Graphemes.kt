package dev.keycorrect.core.input

import java.text.BreakIterator

/** Grapheme-cluster helpers so cursor edits never split an emoji, ZWJ sequence or accent. */
object Graphemes {
    /** Number of UTF-16 chars in the last grapheme cluster of [text], or 0 if empty. */
    fun lastClusterLength(text: CharSequence): Int {
        if (text.isEmpty()) return 0
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text.toString())
        val end = iterator.last()
        val start = iterator.previous()
        return if (start == BreakIterator.DONE) end else end - start
    }
}
