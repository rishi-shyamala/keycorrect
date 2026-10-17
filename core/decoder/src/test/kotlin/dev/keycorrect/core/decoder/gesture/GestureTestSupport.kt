package dev.keycorrect.core.decoder.gesture

import dev.keycorrect.core.decoder.TestDictionary
import dev.keycorrect.core.decoder.TestKeyboard
import dev.keycorrect.core.dictionary.Dictionary
import dev.keycorrect.core.dictionary.DictionaryData
import dev.keycorrect.core.dictionary.DictionaryWriter
import dev.keycorrect.core.dictionary.WordEntry
import dev.keycorrect.core.engineapi.GestureInput
import dev.keycorrect.core.engineapi.TouchPoint
import java.nio.ByteBuffer
import java.util.Random
import kotlin.math.hypot

/** Test dictionary: [TestDictionary] plus gesture-relevant words. */
internal object GestureTestDictionary {
    private val extra =
        mapOf(
            "keyboard" to 900L,
            "board" to 700L,
            "boar" to 20L,
            "word" to 3000L,
            "words" to 2000L,
            "yard" to 400L,
            "hold" to 800L,
            "held" to 800L,
            "keen" to 300L,
            "keyed" to 100L,
            "heel" to 100L,
            "hollow" to 200L,
            "health" to 900L,
            "hall" to 900L,
            "he" to 8000L,
            "she" to 7000L,
            "tie" to 500L,
            "toe" to 300L,
            "tee" to 100L,
            "thee" to 50L,
            "then" to 5000L,
            "them" to 5000L,
            "world's" to 100L,
        )

    val data: DictionaryData by lazy {
        val base = TestDictionary.data
        val have = base.words.map { it.surface }.toSet()
        val words =
            base.words +
                extra
                    .filterKeys { it !in have }
                    .map { (w, c) -> WordEntry.fromCount(w, c, 2_000_000L, 0) }
        DictionaryData(base.metadata, words, base.bigrams, base.trigrams)
    }

    val dictionary: Dictionary by lazy {
        Dictionary.open(ByteBuffer.wrap(DictionaryWriter.write(data, skipUnknownNgrams = true)))
    }
}

/** Test dictionary: [GestureTestDictionary] plus 60k pseudo-words, to stress the index. */
internal val GestureTestDictionary.large: Dictionary
    get() = LargeHolder.dictionary

private object LargeHolder {
    val dictionary: Dictionary by lazy {
        val rnd = Random(42)
        val base = GestureTestDictionary.data
        val have = base.words.map { it.surface }.toHashSet()
        val fake = ArrayList<WordEntry>()
        val letters = "etaoinshrdlcumwfgypbvkjxqz"
        while (fake.size < 60_000) {
            val len = 3 + rnd.nextInt(7)
            val sb = StringBuilder()
            repeat(len) {
                sb.append(letters[minOf(25, (rnd.nextDouble().let { it * it } * 26).toInt())])
            }
            val w = sb.toString()
            if (have.add(w)) fake.add(WordEntry.fromCount(w, 2L + rnd.nextInt(60), 2_000_000L, 0))
        }
        val data = DictionaryData(base.metadata, base.words + fake, base.bigrams, base.trigrams)
        Dictionary.open(ByteBuffer.wrap(DictionaryWriter.write(data, skipUnknownNgrams = true)))
    }
}

/** Synthetic swipes through the key centres of a word. */
internal object Swipes {
    /**
     * A gesture for [word]: straight segments through the key centres (repeats collapsed), sampled
     * every [stepPx]. [jitter] (key widths) moves each key's waypoint by Gaussian noise;
     * [pointNoise] (key widths) is added to every sampled point.
     */
    fun through(
        word: String,
        seed: Long = 1,
        jitter: Float = 0f,
        pointNoise: Float = 0f,
        stepPx: Float = 8f,
    ): GestureInput {
        val rnd = Random(seed)
        val unit = TestKeyboard.keyWidth
        val way = ArrayList<FloatArray>()
        var prev = ' '
        for (c in word.lowercase()) {
            if (c == prev || !c.isLetter()) continue
            prev = c
            val k = TestKeyboard.layout.keys.first { it.char == c }
            way.add(
                floatArrayOf(
                    k.centerX + rnd.nextGaussian().toFloat() * jitter * unit,
                    k.centerY + rnd.nextGaussian().toFloat() * jitter * unit,
                )
            )
        }
        val pts = ArrayList<TouchPoint>()
        var t = 0L
        fun add(x: Float, y: Float) {
            pts.add(
                TouchPoint(
                    x + rnd.nextGaussian().toFloat() * pointNoise * unit,
                    y + rnd.nextGaussian().toFloat() * pointNoise * unit,
                    t,
                )
            )
            t += 10
        }
        add(way[0][0], way[0][1])
        for (i in 1 until way.size) {
            val a = way[i - 1]
            val b = way[i]
            val d = hypot(b[0] - a[0], b[1] - a[1])
            val steps = maxOf(1, (d / stepPx).toInt())
            for (s in 1..steps) {
                val f = s.toFloat() / steps
                add(a[0] + f * (b[0] - a[0]), a[1] + f * (b[1] - a[1]))
            }
        }
        if (pts.size < 2) add(way[0][0] + 0.5f, way[0][1])
        return GestureInput(pts)
    }
}
