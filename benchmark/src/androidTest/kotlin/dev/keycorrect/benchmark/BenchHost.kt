package dev.keycorrect.benchmark

import dev.keycorrect.core.input.Graphemes
import dev.keycorrect.core.input.TextEditor
import dev.keycorrect.feature.keyboard.prediction.DecoderLoop
import dev.keycorrect.feature.keyboard.prediction.PredictionBackend
import dev.keycorrect.feature.keyboard.prediction.PredictionEnvironment
import dev.keycorrect.feature.keyboard.prediction.Predictor
import dev.keycorrect.feature.keyboard.prediction.ShortcutExpander
import dev.keycorrect.feature.keyboard.prediction.ShortcutRule
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

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

    override fun cursorCapsMode(): Int = 0
}

/** A backend with a fixed predictor and no shortcuts or user dictionary. */
class BenchBackend(override val predictor: Predictor?) : PredictionBackend {
    override fun shortcutFor(trigger: String): ShortcutRule? = null

    override fun isKnownWord(word: String): Boolean = true

    override fun addWord(word: String) = Unit
}

/**
 * A [PredictionEnvironment] whose decoder runs inline on the calling thread and whose timers never
 * fire. Its constructor and the scheduler types are `internal` to :feature:keyboard-ui, and the
 * public factory ([PredictionEnvironment.create]) is asynchronous, so the internal constructor is
 * reached by reflection (it is public in bytecode) with proxies for the scheduler interfaces.
 */
fun benchEnvironment(backend: PredictionBackend): PredictionEnvironment {
    val loop = DecoderLoop(Executor { it.run() }, { it.run() }) { backend.predictor }
    val pkg = "dev.keycorrect.feature.keyboard"
    val loader = PredictionEnvironment::class.java.classLoader
    val handle =
        Proxy.newProxyInstance(loader, arrayOf(Class.forName("$pkg.TimeoutHandle"))) { _, m, _ ->
            if (m.returnType == Boolean::class.javaPrimitiveType) false else null
        }
    val scheduler =
        Proxy.newProxyInstance(loader, arrayOf(Class.forName("$pkg.TimeoutScheduler"))) { _, m, _ ->
            when (m.name) {
                "schedule" -> handle
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "BenchScheduler"
                else -> null
            }
        }
    val constructor = PredictionEnvironment::class.java.declaredConstructors.single()
    constructor.isAccessible = true
    return constructor.newInstance(backend, loop, scheduler, ShortcutExpander())
        as PredictionEnvironment
}
