package dev.keycorrect.benchmark

import androidx.test.platform.app.InstrumentationRegistry
import dev.keycorrect.core.dictionary.Dictionary
import dev.keycorrect.core.engineapi.ComposingInput
import dev.keycorrect.core.engineapi.FieldClass
import dev.keycorrect.core.engineapi.KeyLayoutModel
import dev.keycorrect.core.engineapi.KeySpec
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.TouchPoint
import dev.keycorrect.core.engineapi.TypingContext
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.layout.KeyboardGeometry
import dev.keycorrect.core.layout.KeyboardSet
import java.io.FileInputStream
import java.nio.channels.FileChannel
import kotlin.random.Random

/** The shipped dictionary, mapped from the test APK's assets exactly as the app maps it. */
object BenchDictionary {
    const val ASSET = "dictionaries/en_US.kcd"

    val dictionary: Dictionary by lazy {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        assets.openFd(ASSET).use { fd ->
            FileInputStream(fd.fileDescriptor).use { stream ->
                Dictionary.open(
                    stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length)
                )
            }
        }
    }
}

/** The real QWERTY letters layer at tablet-ish size as a [KeyLayoutModel]. */
object BenchKeyboard {
    private val geometry =
        KeyboardGeometry(
            KeyboardComposer(BuiltInLayouts.repository())
                .compose(KeyboardSet.Default, KeyboardSet.LETTERS),
            width = 1080f,
            height = 520f,
        )

    val layout: KeyLayoutModel =
        KeyLayoutModel(
            id = 1L,
            keys =
                geometry.slots
                    .filter { (it.key.action as? KeyAction.Text)?.text?.length == 1 }
                    .map {
                        KeySpec(
                            (it.key.action as KeyAction.Text).text[0],
                            it.centerX,
                            it.centerY,
                            it.right - it.left,
                            it.bottom - it.top,
                        )
                    },
        )

    private val byChar = layout.keys.associateBy { it.char }

    fun key(c: Char): KeySpec? = byChar[c.lowercaseChar()]
}

/** A deterministic batch of decoder requests: exact words, typos and prefixes, with tap noise. */
object BenchInputs {
    private const val SEED = 20_261_011
    private const val MAX_PHRASE_WORDS = 3

    private val words =
        ("the be to of and a in that have it for not on with he as you do at this but his by from " +
                "they we say her she or an will my one all would there their what so up out if " +
                "about who get which go me when make can like time no just him know take people " +
                "into year your good some could them see other than then now look only come its " +
                "over think also back after use two how our work first well way even new want " +
                "because any these give day most us hello world weekend morning because friend " +
                "tomorrow beautiful probably definitely thinking wonderful")
            .split(' ')

    private val contexts =
        listOf("some text ", "i was thinking about the ", "thank you for ", "", "see you ")

    private const val NEIGHBOURS = "qwertyuiopasdfghjklzxcvbnm"

    /** How the word is turned into input; cycles so each kind appears equally often. */
    private enum class Shape {
        Exact,
        Swap,
        Drop,
        Substitute,
        Prefix,
    }

    val requests: List<PredictRequest> by lazy { build(200) }

    /** The correctly typed [words], in order, for the learning simulation. */
    val vocabulary: List<String>
        get() = words

    fun build(count: Int): List<PredictRequest> {
        val rnd = Random(SEED)
        val shapes = Shape.entries
        return List(count) { i ->
            val word = words[(i * 7) % words.size]
            val typed =
                when (shapes[i % shapes.size]) {
                    Shape.Exact -> word
                    Shape.Swap -> swap(word, 1 + rnd.nextInt(maxOf(1, word.length - 1)))
                    Shape.Drop ->
                        if (word.length > 2) drop(word, rnd.nextInt(word.length)) else word
                    Shape.Substitute -> substitute(word, rnd.nextInt(word.length))
                    Shape.Prefix -> word.take(maxOf(1, minOf(word.length - 1, 2 + rnd.nextInt(3))))
                }
            request(contexts[i % contexts.size], typed, rnd)
        }
    }

    /** Taps at key centres with a small deterministic offset (up to a quarter of a key). */
    fun request(before: String, typed: String, rnd: Random): PredictRequest {
        val touches = typed.mapIndexed { i, c ->
            val k = BenchKeyboard.key(c)
            if (k == null) {
                null
            } else {
                TouchPoint(
                    k.centerX + (rnd.nextFloat() - 0.5f) * 0.5f * k.width,
                    k.centerY + (rnd.nextFloat() - 0.5f) * 0.5f * k.height,
                    i * 120L,
                )
            }
        }
        return PredictRequest(
            TypingContext(before, "", FieldClass.Text),
            ComposingInput(typed, touches),
            BenchKeyboard.layout,
            maxCandidates = 3,
            maxPhraseWords = MAX_PHRASE_WORDS,
        )
    }

    private fun swap(w: String, at: Int): String {
        if (w.length < 2) return w
        val i = at.coerceIn(1, w.length - 1)
        val c = w.toCharArray()
        c[i - 1] = w[i]
        c[i] = w[i - 1]
        return String(c)
    }

    private fun drop(w: String, at: Int) = w.removeRange(at, at + 1)

    private fun substitute(w: String, at: Int): String {
        val c = w.toCharArray()
        val j = NEIGHBOURS.indexOf(c[at])
        if (j >= 0) c[at] = NEIGHBOURS[(j + 1) % NEIGHBOURS.length]
        return String(c)
    }
}
