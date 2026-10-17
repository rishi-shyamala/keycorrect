package dev.keycorrect.core.decoder.gesture

import dev.keycorrect.core.decoder.GestureConfig
import dev.keycorrect.core.decoder.Text
import dev.keycorrect.core.dictionary.Dictionary
import dev.keycorrect.core.dictionary.DictionaryFormatException
import dev.keycorrect.core.dictionary.WordFlags
import dev.keycorrect.core.engineapi.KeyLayoutModel
import kotlin.math.hypot

/**
 * The most frequent dictionary words as distinct lowercase keys, independent of any layout. [ids]
 * holds the smallest (most frequent) word id of each key and [unigram] its log-probability.
 */
internal class GestureWordList(dict: Dictionary, config: GestureConfig) {
    val keys: Array<String>
    val ids: IntArray
    val unigram: FloatArray
    private val indexOfKey = HashMap<String, Int>()

    init {
        val last = minOf(dict.wordCount, Dictionary.FIRST_WORD_ID + config.maxWords)
        val ks = ArrayList<String>(last)
        val idList = ArrayList<Int>(last)
        for (id in Dictionary.FIRST_WORD_ID until last) {
            val key = if (dict.flags(id) and EXCLUDED != 0) null else keyOrNull(dict, id)
            if (key != null && indexOfKey.putIfAbsent(key, ks.size) == null) {
                ks.add(key)
                idList.add(id)
            }
        }
        keys = ks.toTypedArray()
        ids = IntArray(idList.size) { idList[it] }
        unigram = FloatArray(ids.size) { dict.logProb(ids[it]) }
    }

    val size: Int
        get() = keys.size

    private fun keyOrNull(dict: Dictionary, id: Int): String? =
        try {
            dict.keyOf(id)
        } catch (_: DictionaryFormatException) {
            null
        }

    fun contains(key: String): Boolean = key in indexOfKey

    private companion object {
        const val EXCLUDED = WordFlags.POSSIBLY_OFFENSIVE or WordFlags.NOT_A_WORD
    }
}

/** A word's ideal path: the key centres of its letters, resampled. */
internal class Template(
    /** Resampled points (interleaved x, y), in layout pixels. */
    val pts: FloatArray,
    /** [pts] normalised for position and scale (see [PathMath.normalize]). */
    val shape: FloatArray,
    /** Length of the key-centre polyline in pixels; 0 when the word sits on one key. */
    val length: Float,
) {
    val isPoint: Boolean
        get() = length <= 0f
}

/**
 * Everything the gesture decoder derives from one layout: key geometry, each word's collapsed key
 * sequence, an index from (first key, last key) to words, and a bounded cache of templates.
 */
// Small accessors over flat arrays; splitting the class would only add indirection.
@Suppress("TooManyFunctions")
internal class GestureLayoutIndex(
    val layout: KeyLayoutModel,
    private val words: GestureWordList,
    private val config: GestureConfig,
) {
    val keyCount = layout.keys.size
    val keyX = FloatArray(keyCount) { layout.keys[it].centerX }
    val keyY = FloatArray(keyCount) { layout.keys[it].centerY }
    val keyWidth = FloatArray(keyCount) { layout.keys[it].width }

    /** Mean key width: the unit of the location channel. */
    val unit: Float = if (keyCount == 0) 1f else keyWidth.sum() / keyCount

    private val asciiKey = IntArray(ASCII) { -1 }
    private val otherKey = HashMap<Char, Int>()

    private var seqKeys = ByteArray(0)
    private val seqOff = IntArray(words.size + 1)
    private val pathLength = FloatArray(words.size)
    private val cellStart: IntArray
    private val cellWords: IntArray

    private val templates =
        object : LinkedHashMap<String, Template>(1024, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Template>) =
                size > config.templateCacheSize
        }
    private var polyline = FloatArray(64)
    private val scratchSeq = ByteArray(MAX_SEQ)

    init {
        // First key wins when a layout lists a char twice.
        for (i in layout.keys.indices.reversed()) {
            val c = layout.keys[i].char.lowercaseChar()
            if (c.code < ASCII) asciiKey[c.code] = i else otherKey[c] = i
        }
        var buf = ByteArray(words.size * 6 + 16)
        var n = 0
        val tmp = ByteArray(MAX_SEQ)
        for (w in 0 until words.size) {
            seqOff[w] = n
            val len = sequence(words.keys[w], tmp)
            if (len > 0) {
                if (n + len > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, n + len))
                System.arraycopy(tmp, 0, buf, n, len)
                n += len
                pathLength[w] = polylineLength(tmp, len)
            }
        }
        seqOff[words.size] = n
        seqKeys = buf.copyOf(n)

        val cells = keyCount * keyCount
        val start = IntArray(cells + 1)
        for (w in 0 until words.size) {
            val len = seqOff[w + 1] - seqOff[w]
            if (len > 0)
                start[cell(seqKeys[seqOff[w]].toInt(), seqKeys[seqOff[w + 1] - 1].toInt()) + 1]++
        }
        for (c in 0 until cells) start[c + 1] += start[c]
        val fill = start.copyOf(cells)
        val list = IntArray(start[cells])
        for (w in 0 until words.size) {
            val len = seqOff[w + 1] - seqOff[w]
            if (len > 0) {
                val c = cell(seqKeys[seqOff[w]].toInt(), seqKeys[seqOff[w + 1] - 1].toInt())
                list[fill[c]++] = w
            }
        }
        cellStart = start
        cellWords = list
    }

    fun cell(first: Int, last: Int): Int = first * keyCount + last

    fun cellBegin(cell: Int): Int = cellStart[cell]

    fun cellEnd(cell: Int): Int = cellStart[cell + 1]

    fun wordAt(position: Int): Int = cellWords[position]

    /** Layout key index of [c] (case-insensitive), or -1. */
    fun keyIndexOf(c: Char): Int {
        val l = c.lowercaseChar()
        return if (l.code < ASCII) asciiKey[l.code] else otherKey[l] ?: -1
    }

    /**
     * Writes the collapsed key sequence of [key] (apostrophes ignored, consecutive repeats merged)
     * to [out] and returns its length; 0 if the word has a char that is not on the layout, is
     * empty, or is longer than the buffer.
     */
    fun sequence(key: String, out: ByteArray): Int {
        var n = 0
        for (ch in key) {
            if (Text.isApostrophe(ch)) continue
            val k = keyIndexOf(ch)
            if (k < 0 || k > Byte.MAX_VALUE || n == out.size) return 0
            if (n == 0 || out[n - 1].toInt() != k) out[n++] = k.toByte()
        }
        return n
    }

    private fun polylineLength(seq: ByteArray, len: Int): Float {
        var sum = 0f
        for (i in 1 until len) {
            val a = seq[i - 1].toInt()
            val b = seq[i].toInt()
            sum += hypot(keyX[b] - keyX[a], keyY[b] - keyY[a])
        }
        return sum
    }

    /** First and last key of word [w], or -1 if the word is not usable on this layout. */
    fun firstKey(w: Int): Int = if (seqOff[w + 1] > seqOff[w]) seqKeys[seqOff[w]].toInt() else -1

    fun lastKey(w: Int): Int = seqKeys[seqOff[w + 1] - 1].toInt()

    fun pathLengthOf(w: Int): Float = pathLength[w]

    /** Template of dictionary word [w]; cached. */
    fun templateOf(w: Int): Template =
        templates[words.keys[w]]
            ?: build(words.keys[w], seqKeys, seqOff[w], seqOff[w + 1] - seqOff[w])

    /** Template of an arbitrary [key] whose sequence is [seq] (length [len]); cached by key. */
    fun templateOfSequence(key: String, seq: ByteArray, len: Int): Template =
        templates[key] ?: build(key, seq, 0, len)

    private fun build(key: String, seq: ByteArray, off: Int, len: Int): Template {
        if (polyline.size < 2 * len) polyline = FloatArray(2 * len)
        for (i in 0 until len) {
            val k = seq[off + i].toInt()
            polyline[2 * i] = keyX[k]
            polyline[2 * i + 1] = keyY[k]
        }
        val n = config.resamplePoints
        val pts = FloatArray(2 * n)
        PathMath.resample(polyline, len, pts, n)
        val shape = FloatArray(2 * n)
        PathMath.normalize(pts, n, shape)
        val t = Template(pts, shape, PathMath.length(polyline, len))
        templates[key] = t
        return t
    }

    /** Scratch for callers building a sequence for [templateOfSequence]. */
    fun scratch(): ByteArray = scratchSeq

    companion object {
        const val MAX_SEQ = 64
        const val ASCII = 128
    }
}
