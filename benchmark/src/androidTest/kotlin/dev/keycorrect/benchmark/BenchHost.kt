package dev.keycorrect.benchmark

import dev.keycorrect.core.input.EditAction
import dev.keycorrect.core.input.Graphemes
import dev.keycorrect.core.input.Surrounding
import dev.keycorrect.core.input.TextEditor
import dev.keycorrect.feature.keyboard.prediction.PredictionBackend
import dev.keycorrect.feature.keyboard.prediction.PredictionEnvironment
import dev.keycorrect.feature.keyboard.prediction.Predictor
import dev.keycorrect.feature.keyboard.prediction.ShortcutRule

/** An in-memory text field keeping text, cursor and composing region, like a real one would. */
class BenchEditor : TextEditor {
    private val text = StringBuilder()
    private var cursor = 0
    private var compStart = -1
    private var compEnd = -1

    val length: Int
        get() = text.length

    fun reset() {
        text.setLength(0)
        cursor = 0
        compStart = -1
        compEnd = -1
    }

    private fun replaceRegion(s: CharSequence): Int {
        val start = if (compStart >= 0) compStart else cursor
        val end = if (compStart >= 0) compEnd else cursor
        text.replace(start, end, s.toString())
        return start
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int) {
        val start = replaceRegion(text)
        compStart = -1
        compEnd = -1
        cursor =
            if (newCursorPosition > 0) start + text.length + newCursorPosition - 1
            else start + newCursorPosition
    }

    override fun setComposingText(text: CharSequence) {
        val start = replaceRegion(text)
        compStart = start
        compEnd = start + text.length
        cursor = compEnd
    }

    override fun finishComposingText() {
        compStart = -1
        compEnd = -1
    }

    override fun deleteSurroundingText(before: Int, after: Int) {
        val from = maxOf(0, cursor - before)
        val to = minOf(text.length, cursor + after)
        text.delete(from, to)
        cursor = from
        compStart = -1
        compEnd = -1
    }

    override fun textBeforeCursor(maxChars: Int): CharSequence =
        text.substring(maxOf(0, cursor - maxChars), cursor)

    override fun textAfterCursor(maxChars: Int): CharSequence =
        text.substring(cursor, minOf(text.length, cursor + maxChars))

    override fun deleteBackward(sensitive: Boolean) {
        if (cursor > 0) {
            val n = Graphemes.lastClusterLength(text.substring(maxOf(0, cursor - 32), cursor))
            text.delete(cursor - n, cursor)
            cursor -= n
        }
        compStart = -1
        compEnd = -1
    }

    override fun performEnter() = Unit

    override fun setSelection(start: Int, end: Int) {
        cursor = end
        compStart = -1
        compEnd = -1
    }

    override fun sendKey(keyCode: Int, metaState: Int) = Unit

    override fun contextMenuAction(action: EditAction): Boolean = false

    override fun selectedText(maxChars: Int): CharSequence? = null

    override fun surroundingText(beforeLength: Int, afterLength: Int): Surrounding? = null

    override fun cursorCapsMode(): Int = 0
}

/** A backend with a fixed predictor and no shortcuts or user dictionary. */
class BenchBackend(override val predictor: Predictor?) : PredictionBackend {
    override fun shortcutFor(trigger: String): ShortcutRule? = null

    override fun isKnownWord(word: String): Boolean = true

    override fun addWord(word: String) = Unit
}

/** A [PredictionEnvironment] whose decoder runs inline on the calling thread. */
fun benchEnvironment(backend: PredictionBackend): PredictionEnvironment =
    PredictionEnvironment.inline(backend)
