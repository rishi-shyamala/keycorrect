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
 *
 * Words the dictionary flags possibly offensive or not-a-word are left out, so a user word with the
 * same key is not swipeable either: [GestureRescorer] rejects it because every dictionary id of its
 * key is excluded, matching the typed path (its trie search skips flagged words, and
 * `ClassicEngine` leaves user words that are in the dictionary to it). A user word that is an
 * ordinary dictionary word is scored as the dictionary word.
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

/**
 * A word's ideal path: the key centres of its letters, resampled. Cache slots are recycled, so a
 * template is only valid until the next [GestureLayoutIndex] lookup that may evict it.
 */
internal class Template(n: Int) {
    /** Resampled points (interleaved x, y), in layout pixels. */
    val pts = FloatArray(2 * n)

    /** [pts] normalised for position and scale (see [PathMath.normalize]). */
    val shape = FloatArray(2 * n)

    /** Length of the key-centre polyline in pixels; 0 when the word sits on one key. */
    var length = 0f

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

    // LRU cache of dictionary-word templates in preallocated slots (no allocation once warm):
    // [slotOf] maps a word to its slot (-1 = none); slots form a doubly linked list, most recently
    // used at [head], least at [tail]. User-word templates are built into [userTemplate] uncached.
    private val cacheCap = config.templateCacheSize.coerceAtLeast(1)
    private val slotOf = IntArray(words.size) { -1 }
    private val slotWord = IntArray(cacheCap)
    private val slotPrev = IntArray(cacheCap)
    private val slotNext = IntArray(cacheCap)
    private val slotTemplate = Array(cacheCap) { EMPTY }
    private var slotsUsed = 0
    private var head = -1
    private var tail = -1
    private val userTemplate = Template(config.resamplePoints)
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

    /**
     * True if word [w]'s letters can be matched, in order, to gesture samples: [masks] holds for
     * each layout key the set of samples (bit i = sample i) within reach of the key, and each
     * letter needs a sample at or after the one matched to the previous letter. Greedy earliest
     * matching is exact for this subsequence test.
     */
    fun visitsInOrder(w: Int, masks: IntArray): Boolean {
        var from = 0
        for (p in seqOff[w] until seqOff[w + 1]) {
            val m = masks[seqKeys[p].toInt()] and (-1 shl from)
            if (m == 0) return false
            from = Integer.numberOfTrailingZeros(m)
        }
        return true
    }

    /** Template of dictionary word [w]; cached (valid until the next lookup). */
    fun templateOf(w: Int): Template {
        var slot = slotOf[w]
        if (slot >= 0) {
            if (slot != head) {
                unlink(slot)
                pushFront(slot)
            }
            return slotTemplate[slot]
        }
        if (slotsUsed < cacheCap) {
            slot = slotsUsed++
            slotTemplate[slot] = Template(config.resamplePoints)
        } else {
            slot = tail
            unlink(slot)
            slotOf[slotWord[slot]] = -1
        }
        slotWord[slot] = w
        slotOf[w] = slot
        pushFront(slot)
        val t = slotTemplate[slot]
        fill(t, seqKeys, seqOff[w], seqOff[w + 1] - seqOff[w])
        return t
    }

    private fun unlink(s: Int) {
        val p = slotPrev[s]
        val n = slotNext[s]
        if (p >= 0) slotNext[p] = n else head = n
        if (n >= 0) slotPrev[n] = p else tail = p
    }

    private fun pushFront(s: Int) {
        slotPrev[s] = -1
        slotNext[s] = head
        if (head >= 0) slotPrev[head] = s
        head = s
        if (tail < 0) tail = s
    }

    /** Template of an arbitrary word whose sequence is [seq] (length [len]); not cached. */
    fun templateOfSequence(seq: ByteArray, len: Int): Template {
        fill(userTemplate, seq, 0, len)
        return userTemplate
    }

    private fun fill(t: Template, seq: ByteArray, off: Int, len: Int) {
        if (polyline.size < 2 * len) polyline = FloatArray(2 * len)
        for (i in 0 until len) {
            val k = seq[off + i].toInt()
            polyline[2 * i] = keyX[k]
            polyline[2 * i + 1] = keyY[k]
        }
        val n = config.resamplePoints
        PathMath.resample(polyline, len, t.pts, n)
        PathMath.normalize(t.pts, n, t.shape)
        t.length = PathMath.length(polyline, len)
    }

    /** Scratch for callers building a sequence for [templateOfSequence]. */
    fun scratch(): ByteArray = scratchSeq

    companion object {
        private val EMPTY = Template(0)
        const val MAX_SEQ = 64
        const val ASCII = 128
    }
}
