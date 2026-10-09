package dev.keycorrect.core.dictionary

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** A `(word id, natural-log probability)` pair returned by successor queries. */
data class Successor(val id: Int, val logProb: Float)

/**
 * Read-only view over a compiled KCD1 dictionary (see FORMAT.md).
 *
 * [open] validates the small header structures only; bulk data is read lazily from the buffer with
 * absolute little-endian gets, so a memory-mapped buffer is never copied. Instances hold no mutable
 * state and are safe for concurrent readers.
 *
 * Word ids: 0 = `<s>` ([BOS_ID]), 1 = `</s>` ([EOS_ID]), real words from [FIRST_WORD_ID], ordered
 * by descending unigram probability (so a smaller id is a more frequent word).
 *
 * Trie nodes are `Int` handles (BFS indices); [root] is 0 and -1 means "no node". Children of a
 * node are contiguous: `firstChild(node) until firstChild(node) + childCount(node)`, ordered by
 * character, so a hot loop can iterate them without allocation.
 */
class Dictionary
private constructor(
    private val buf: ByteBuffer,
    val metadata: DictionaryMetadata,
    /** Number of word ids, including the two reserved ones. */
    val wordCount: Int,
    private val nodeCount: Int,
    /** Number of distinct lowercase keys (terminal trie nodes). */
    val keyCount: Int,
    private val caseCount: Int,
    private val uniMin: Float,
    private val uniStep: Float,
    /** Number of bigrams carrying a probability (excludes context-only entries). */
    val bigramCount: Int,
    /** Number of trigrams. */
    val trigramCount: Int,
    private val alphabet: CharArray,
    private val nodesOff: Int,
    private val samplesOff: Int,
    private val bitmapOff: Int,
    private val termOff: Int,
    private val multiOff: Int,
    private val multiEnd: Int,
    private val infoOff: Int,
    private val idNodeOff: Int,
    private val caseIdxOff: Int,
    private val casePoolOff: Int,
    private val casePoolEnd: Int,
    private val bigrams: NgramTable,
    private val trigrams: NgramTable,
) {
    private val labelTable = IntArray(256) { -1 }

    init {
        for (i in alphabet.indices) if (alphabet[i].code < 256) labelTable[alphabet[i].code] = i
    }

    // ---------------------------------------------------------------- trie

    /** The root node (empty prefix). */
    val root: Int
        get() = 0

    /** Number of children of [node]. */
    fun childCount(node: Int): Int {
        checkNode(node)
        return Fmt.u8(buf, nodesOff + node * Fmt.NODE_RECORD)
    }

    /** Handle of the first child of [node]; children are `firstChild + 0 until childCount`. */
    fun firstChild(node: Int): Int {
        checkNode(node)
        val j = node ushr 4
        var fc = buf.getInt(samplesOff + j * 4)
        var t = j shl 4
        while (t < node) {
            fc += Fmt.u8(buf, nodesOff + t * Fmt.NODE_RECORD)
            t++
        }
        // The children must exist: a corrupt sample/count would otherwise yield handles off the
        // trie.
        if (fc < 0 || fc.toLong() + Fmt.u8(buf, nodesOff + node * Fmt.NODE_RECORD) > nodeCount) {
            throw DictionaryFormatException("corrupt trie: children of node $node out of range")
        }
        return fc
    }

    /** Child handle by index (`0 until childCount(node)`); recomputes [firstChild] every call. */
    fun childAt(node: Int, index: Int): Int = firstChild(node) + index

    /** The character labelling the edge into [node] (undefined for [root]). */
    fun nodeChar(node: Int): Char {
        checkNode(node)
        val label = Fmt.u8(buf, nodesOff + node * Fmt.NODE_RECORD + 1)
        if (label >= alphabet.size) throw DictionaryFormatException("corrupt trie: bad label")
        return alphabet[label]
    }

    /** Trie handles come from this dictionary's own data; a handle outside the trie is corrupt. */
    private fun checkNode(node: Int) {
        if (node < 0 || node >= nodeCount) {
            throw DictionaryFormatException("corrupt trie: node $node outside 0 until $nodeCount")
        }
    }

    /** Child of [node] reached by [c], or -1. */
    fun child(node: Int, c: Char): Int {
        val label = labelOf(c)
        if (label < 0) return -1
        val cnt = childCount(node)
        if (cnt == 0) return -1
        val fc = firstChild(node)
        var lo = 0
        var hi = cnt - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val l = Fmt.u8(buf, nodesOff + (fc + mid) * Fmt.NODE_RECORD + 1)
            if (l < label) lo = mid + 1 else if (l > label) hi = mid - 1 else return fc + mid
        }
        return -1
    }

    /** Node for [prefix] (a normalized lowercase key prefix), or -1. */
    fun findNode(prefix: CharSequence): Int {
        var node = 0
        for (i in 0 until prefix.length) {
            node = child(node, prefix[i])
            if (node < 0) return -1
        }
        return node
    }

    /** True if some key ends exactly at [node]. */
    fun isTerminal(node: Int): Boolean {
        checkNode(node)
        val bits = buf.getLong(bitmapOff + (node ushr 6) * Fmt.BITMAP_BLOCK_BYTES + 4)
        return (bits ushr (node and 63)) and 1L != 0L
    }

    /** Number of surface forms (word ids) of the key ending at [node]; 0 if not terminal. */
    fun wordIdCountAt(node: Int): Int {
        val v = terminalValue(node)
        return when {
            v < 0 -> 0
            v and Fmt.MULTI_FLAG != 0 -> multiCount(v)
            else -> 1
        }
    }

    /** Number of ids of the multi-id record referenced by terminal value [v] (bounds-checked). */
    private fun multiCount(v: Int): Int {
        val base = multiOff + (v and 0x7FFFFF)
        if (base < multiOff || base >= multiEnd)
            throw DictionaryFormatException("corrupt multi-id offset")
        val count = Fmt.u8(buf, base)
        if (base + 1 + count * 3 > multiEnd)
            throw DictionaryFormatException("corrupt multi-id record")
        return count
    }

    private fun checkedWordId(id: Int): Int {
        if (id < FIRST_WORD_ID || id >= wordCount) {
            throw DictionaryFormatException("corrupt dictionary: word id $id out of range")
        }
        return id
    }

    /** The [index]-th word id at [node]; ids are ascending (most probable surface first). */
    fun wordIdAt(node: Int, index: Int): Int {
        val v = terminalValue(node)
        require(v >= 0) { "node $node is not terminal" }
        if (v and Fmt.MULTI_FLAG == 0) {
            require(index == 0) { "index $index out of range" }
            return checkedWordId(v)
        }
        val count = multiCount(v)
        require(index in 0 until count) { "index $index out of range" }
        return checkedWordId(Fmt.u24(buf, multiOff + (v and 0x7FFFFF) + 1 + index * 3))
    }

    /** Word id of the most probable surface form at [node], or -1 if not terminal. */
    fun firstWordIdAt(node: Int): Int {
        val v = terminalValue(node)
        return when {
            v < 0 -> -1
            v and Fmt.MULTI_FLAG != 0 ->
                if (multiCount(v) == 0) -1
                else checkedWordId(Fmt.u24(buf, multiOff + (v and 0x7FFFFF) + 1))
            else -> checkedWordId(v)
        }
    }

    /** All word ids at [node] (allocates; use the count/at pair in hot paths). */
    fun wordIdsAt(node: Int): IntArray {
        val n = wordIdCountAt(node)
        return IntArray(n) { wordIdAt(node, it) }
    }

    /**
     * Upper bound on [logProb] over every word whose key has the prefix of [node] (exact: it is the
     * maximum quantized value in the subtree, decoded identically to [logProb]).
     */
    fun maxLogProbInSubtree(node: Int): Float {
        checkNode(node)
        return uniMin + Fmt.u8(buf, nodesOff + node * Fmt.NODE_RECORD + 2) * uniStep
    }

    private fun terminalValue(node: Int): Int {
        val blockOff = bitmapOff + (node ushr 6) * Fmt.BITMAP_BLOCK_BYTES
        val bits = buf.getLong(blockOff + 4)
        val bit = node and 63
        if ((bits ushr bit) and 1L == 0L) return -1
        val rank = buf.getInt(blockOff) + java.lang.Long.bitCount(bits and ((1L shl bit) - 1))
        if (rank < 0 || rank >= keyCount) throw DictionaryFormatException("corrupt terminal rank")
        return Fmt.u24(buf, termOff + rank * 3)
    }

    private fun labelOf(c: Char): Int {
        if (c.code < 256) return labelTable[c.code]
        val i = java.util.Arrays.binarySearch(alphabet, c)
        return if (i >= 0) i else -1
    }

    private fun parentOf(node: Int): Int {
        var lo = 0
        var hi = (nodeCount + Fmt.SAMPLE_STRIDE - 1) / Fmt.SAMPLE_STRIDE - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (buf.getInt(samplesOff + mid * 4) <= node) lo = mid else hi = mid - 1
        }
        var fc = buf.getInt(samplesOff + lo * 4)
        var i = lo shl 4
        val end = minOf(nodeCount, i + Fmt.SAMPLE_STRIDE)
        var best = i
        while (i < end && fc <= node) {
            best = i
            fc += Fmt.u8(buf, nodesOff + i * Fmt.NODE_RECORD)
            i++
        }
        return best
    }

    /**
     * The key of [node], walking up to the root. A valid parent always has a smaller handle and a
     * key is at most [MAX_KEY_LENGTH] chars, so corrupt parent links cannot loop or run away.
     */
    private fun keyOfNode(node: Int): String {
        checkNode(node)
        val sb = StringBuilder()
        var n = node
        while (n != 0) {
            if (sb.length >= MAX_KEY_LENGTH) {
                throw DictionaryFormatException("corrupt trie: key longer than $MAX_KEY_LENGTH")
            }
            sb.append(nodeChar(n))
            val parent = parentOf(n)
            if (parent >= n) throw DictionaryFormatException("corrupt trie: parent link cycle")
            n = parent
        }
        return sb.reverse().toString()
    }

    /** Terminal node of word [id]'s key from ID_TO_NODE, validated. */
    private fun nodeOfId(id: Int): Int {
        val node = Fmt.u24(buf, idNodeOff + id * 3)
        if (node >= nodeCount)
            throw DictionaryFormatException("corrupt dictionary: node of word $id")
        return node
    }

    // ---------------------------------------------------------------- words

    /** Word id of the exact [surface] form (case-sensitive), or -1. */
    fun wordId(surface: String): Int {
        val key = normalizeKey(surface)
        val node = findNode(key)
        if (node < 0) return -1
        val n = wordIdCountAt(node)
        for (i in 0 until n) {
            val id = wordIdAt(node, i)
            if (surfaceFor(id, key) == surface) return id
        }
        return -1
    }

    /** Word ids of all surface forms of [lowercaseKey] (empty if absent). */
    fun idsForKey(lowercaseKey: String): IntArray {
        val node = findNode(lowercaseKey)
        return if (node < 0) IntArray(0) else wordIdsAt(node)
    }

    /** Surface string of [id] (`<s>`/`</s>` for the reserved ids). */
    fun word(id: Int): String {
        checkId(id)
        if (id == BOS_ID) return BOS_TOKEN
        if (id == EOS_ID) return EOS_TOKEN
        val pattern = patternOf(id)
        if (pattern == Fmt.CASE_CUSTOM) return customSurface(id)
        return CaseUtil.apply(keyOfNode(nodeOfId(id)), pattern)
    }

    /** Lowercase key under which [id] is stored in the trie (null for reserved ids). */
    fun keyOf(id: Int): String? {
        checkId(id)
        if (id < FIRST_WORD_ID) return null
        return keyOfNode(nodeOfId(id))
    }

    /**
     * Dequantized natural-log unigram probability (see FORMAT.md; reserved ids return the minimum).
     */
    fun logProb(id: Int): Float {
        checkId(id)
        return uniMin + Fmt.u8(buf, infoOff + id * 2) * uniStep
    }

    /** [WordFlags] bitset for [id]. */
    fun flags(id: Int): Int {
        checkId(id)
        return Fmt.u8(buf, infoOff + id * 2 + 1) and 0x1F
    }

    private fun patternOf(id: Int): Int = Fmt.u8(buf, infoOff + id * 2 + 1) ushr 6

    private fun surfaceFor(id: Int, key: String): String {
        val pattern = patternOf(id)
        return if (pattern == Fmt.CASE_CUSTOM) customSurface(id) else CaseUtil.apply(key, pattern)
    }

    private fun customSurface(id: Int): String {
        var lo = 0
        var hi = caseCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val e = caseIdxOff + mid * 6
            val v = Fmt.u24(buf, e)
            if (v < id) lo = mid + 1
            else if (v > id) hi = mid - 1
            else {
                var p = casePoolOff + Fmt.u24(buf, e + 3)
                if (p >= casePoolEnd) throw DictionaryFormatException("corrupt case pool offset")
                val len = Fmt.readVarint(buf, p)
                p += Fmt.varintSize(len)
                if (len > casePoolEnd - p)
                    throw DictionaryFormatException("corrupt case pool length")
                val bytes = ByteArray(len)
                for (i in 0 until len) bytes[i] = buf.get(p + i)
                return String(bytes, Charsets.UTF_8)
            }
        }
        throw DictionaryFormatException("missing custom surface for word $id")
    }

    private fun checkId(id: Int) {
        if (id < 0 || id >= wordCount) throw IndexOutOfBoundsException("word id $id")
    }

    // ---------------------------------------------------------------- n-grams

    /** Raw n-gram tables, for tests that must walk every list. */
    internal val bigramTable: NgramTable
        get() = bigrams

    internal val trigramTable: NgramTable
        get() = trigrams

    /** `ln P(w2 | w1)` or null when the bigram is not stored (caller backs off). */
    fun bigramLogProb(w1: Int, w2: Int): Float? {
        val v = bigramLogProbOrNaN(w1, w2)
        return if (v.isNaN()) null else v
    }

    /** Allocation-free [bigramLogProb]: NaN when absent. */
    fun bigramLogProbOrNaN(w1: Int, w2: Int): Float = logProbInBigramList(bigramList(w1), w2)

    /** `ln P(w3 | w1, w2)` or null when the trigram is not stored. */
    fun trigramLogProb(w1: Int, w2: Int, w3: Int): Float? {
        val v = trigramLogProbOrNaN(w1, w2, w3)
        return if (v.isNaN()) null else v
    }

    /** Allocation-free [trigramLogProb]: NaN when absent. */
    fun trigramLogProbOrNaN(w1: Int, w2: Int, w3: Int): Float =
        logProbInTrigramList(trigramList(w1, w2), w3)

    /**
     * Handle (opaque Int, -1 = none) of the successor list of [w1]. Resolve once, then query many
     * candidates with [logProbInBigramList].
     */
    fun bigramList(w1: Int): Int {
        val loc = bigrams.locate(w1)
        return if (loc < 0) -1 else bigrams.positionOf(loc)
    }

    /** Handle of the successor list of the pair ([w1], [w2]), or -1 if it has no trigrams. */
    fun trigramList(w1: Int, w2: Int): Int {
        val loc = bigrams.locate(w1)
        if (loc < 0) return -1
        val r = bigrams.lookup(bigrams.positionOf(loc), w2)
        if (r < 0 || (r and 0x80L) == 0L) return -1
        val ordinal = bigrams.flagsBeforeOf(loc) + (r ushr 8).toInt()
        val t = trigrams.locate(ordinal)
        return if (t < 0) -1 else trigrams.positionOf(t)
    }

    /** `ln P(w2 | w1)` using a [bigramList] handle; NaN when absent. */
    fun logProbInBigramList(list: Int, w2: Int): Float {
        if (list < 0) return Float.NaN
        val r = bigrams.lookup(list, w2)
        if (r < 0) return Float.NaN
        val q = (r and 0x7F).toInt()
        return if (q == 0) Float.NaN else bigrams.decode(q)
    }

    /** `ln P(w3 | w1, w2)` using a [trigramList] handle; NaN when absent. */
    fun logProbInTrigramList(list: Int, w3: Int): Float {
        if (list < 0) return Float.NaN
        val r = trigrams.lookup(list, w3)
        if (r < 0) return Float.NaN
        val q = (r and 0x7F).toInt()
        return if (q == 0) Float.NaN else trigrams.decode(q)
    }

    /**
     * Allocation-free top-successor query: fills [outIds]/[outLogProbs] (sorted by probability
     * descending, ties by smaller id) with up to `min(k, outIds.size, outLogProbs.size)` bigram
     * successors of [w1] and returns the count. Use `w1 = BOS_ID` for sentence-initial words.
     */
    fun topSuccessors(w1: Int, k: Int, outIds: IntArray, outLogProbs: FloatArray): Int {
        val cap = minOf(k, outIds.size, outLogProbs.size)
        val list = bigramList(w1)
        if (cap <= 0 || list < 0) return 0
        return bigrams.top(list, cap, outIds, outLogProbs)
    }

    /** Trigram analogue of [topSuccessors]: successors of the pair ([w1], [w2]). */
    fun topSuccessors(w1: Int, w2: Int, k: Int, outIds: IntArray, outLogProbs: FloatArray): Int {
        val cap = minOf(k, outIds.size, outLogProbs.size)
        val list = trigramList(w1, w2)
        if (cap <= 0 || list < 0) return 0
        return trigrams.top(list, cap, outIds, outLogProbs)
    }

    /** Up to [k] most probable bigram successors of [w1] (allocates). */
    fun topSuccessors(w1: Int, k: Int): List<Successor> {
        if (k <= 0) return emptyList()
        val ids = IntArray(k)
        val lps = FloatArray(k)
        val n = topSuccessors(w1, k, ids, lps)
        return List(n) { Successor(ids[it], lps[it]) }
    }

    /** Up to [k] most probable trigram successors of ([w1], [w2]) (allocates). */
    fun topSuccessors(w1: Int, w2: Int, k: Int): List<Successor> {
        if (k <= 0) return emptyList()
        val ids = IntArray(k)
        val lps = FloatArray(k)
        val n = topSuccessors(w1, w2, k, ids, lps)
        return List(n) { Successor(ids[it], lps[it]) }
    }

    override fun toString(): String =
        "Dictionary(${metadata.languageTag} v${metadata.dataVersion}, words=${wordCount - FIRST_WORD_ID}, " +
            "keys=$keyCount, nodes=$nodeCount, bigrams=$bigramCount, trigrams=$trigramCount)"

    companion object {
        /** Reserved id of the sentence-start token; use as `w1` in bigram queries. */
        const val BOS_ID = 0

        /** Reserved id of the sentence-end token. */
        const val EOS_ID = 1

        /** First id assigned to a real word. */
        const val FIRST_WORD_ID = 2

        const val BOS_TOKEN = "<s>"
        const val EOS_TOKEN = "</s>"

        /** Longest trie key (in chars) the reader will reconstruct; the writer enforces it too. */
        const val MAX_KEY_LENGTH = 256

        /** Normalizes a surface string to the trie key: Unicode lowercase, accents kept. */
        @JvmStatic fun normalizeKey(surface: String): String = surface.lowercase()

        /**
         * Opens a dictionary over [buffer] (from its current position to its limit) without
         * copying. The buffer's position/limit/order are never modified. Throws
         * [DictionaryFormatException] for a bad magic, unsupported version, truncation,
         * out-of-bounds sections or a header checksum mismatch.
         */
        @JvmStatic
        fun open(buffer: ByteBuffer): Dictionary {
            val buf = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
            try {
                return parse(buf)
            } catch (e: DictionaryFormatException) {
                throw e
            } catch (e: IndexOutOfBoundsException) {
                throw DictionaryFormatException("corrupt dictionary: out-of-bounds read", e)
            } catch (e: NegativeArraySizeException) {
                throw DictionaryFormatException("corrupt dictionary: negative length", e)
            }
        }

        private fun fail(msg: String): Nothing = throw DictionaryFormatException(msg)

        private fun parse(buf: ByteBuffer): Dictionary {
            val size = buf.limit()
            if (size < Fmt.HEADER_SIZE) fail("buffer too small for header ($size bytes)")
            if (
                buf.get(0) != 'K'.code.toByte() ||
                    buf.get(1) != 'C'.code.toByte() ||
                    buf.get(2) != 'D'.code.toByte() ||
                    buf.get(3) != '1'.code.toByte()
            ) {
                fail("bad magic (not a KCD1 dictionary)")
            }
            val version = Fmt.u16(buf, 4)
            if (version != Fmt.VERSION) fail("unsupported format version $version")
            val total = buf.getInt(8).toLong() and 0xFFFFFFFFL
            if (total > size) fail("truncated: header says $total bytes, buffer has $size")
            val sectionCount = buf.getInt(12)
            if (sectionCount !in 1..Fmt.MAX_SECTIONS) fail("bad section count $sectionCount")
            val tableEnd = Fmt.HEADER_SIZE + sectionCount * Fmt.SECTION_ENTRY_SIZE
            if (tableEnd > total) fail("section table exceeds dictionary length")
            buf.limit(total.toInt())

            val secOff = IntArray(Fmt.MAX_SECTIONS + 1) { -1 }
            val secLen = IntArray(Fmt.MAX_SECTIONS + 1)
            for (i in 0 until sectionCount) {
                val e = Fmt.HEADER_SIZE + i * Fmt.SECTION_ENTRY_SIZE
                val id = buf.getInt(e)
                val off = buf.getInt(e + 4).toLong() and 0xFFFFFFFFL
                val len = buf.getInt(e + 8).toLong() and 0xFFFFFFFFL
                if (off < tableEnd || off + len > total) fail("section $id out of bounds")
                if (id in 1..Fmt.MAX_SECTIONS) {
                    if (secOff[id] >= 0) fail("duplicate section $id")
                    secOff[id] = off.toInt()
                    secLen[id] = len.toInt()
                }
            }
            fun need(id: Int, expectedLen: Long): Int {
                if (secOff[id] < 0) fail("missing section $id")
                if (expectedLen >= 0 && secLen[id].toLong() != expectedLen) {
                    fail("section $id has length ${secLen[id]}, expected $expectedLen")
                }
                return secOff[id]
            }

            // Header checksum.
            val crc = CRC32()
            val head = ByteArray(tableEnd)
            for (i in 0 until tableEnd) head[i] = buf.get(i)
            for (i in 0 until 4) head[Fmt.CRC_OFFSET + i] = 0
            crc.update(head)
            for (id in Fmt.CRC_SECTIONS) {
                need(id, -1)
                val b = ByteArray(secLen[id])
                for (i in b.indices) b[i] = buf.get(secOff[id] + i)
                crc.update(b)
            }
            if (crc.value.toInt() != buf.getInt(Fmt.CRC_OFFSET)) fail("header checksum mismatch")

            // Params.
            val p = need(Fmt.SEC_PARAMS, Fmt.PARAMS_SIZE.toLong())
            val wordCount = buf.getInt(p)
            val nodeCount = buf.getInt(p + 4)
            val keyCount = buf.getInt(p + 8)
            val caseCount = buf.getInt(p + 12)
            val uniMin = buf.getFloat(p + 16)
            val uniStep = buf.getFloat(p + 20)
            val bgMin = buf.getFloat(p + 24)
            val bgStep = buf.getFloat(p + 28)
            val tgMin = buf.getFloat(p + 32)
            val tgStep = buf.getFloat(p + 36)
            val bgLists = buf.getInt(p + 40)
            val tgLists = buf.getInt(p + 44)
            val bgCount = buf.getInt(p + 48)
            val tgCount = buf.getInt(p + 52)
            if (
                wordCount < Dictionary.FIRST_WORD_ID ||
                    wordCount > Fmt.MAX_ID ||
                    nodeCount < 1 ||
                    keyCount < 0 ||
                    keyCount > nodeCount ||
                    caseCount < 0 ||
                    bgLists < 0 ||
                    tgLists < 0 ||
                    bgCount < 0 ||
                    tgCount < 0
            ) {
                fail("implausible parameters")
            }

            // Alphabet.
            val a = need(Fmt.SEC_ALPHABET, -1)
            val alphaN = Fmt.u16(buf, a)
            if (secLen[Fmt.SEC_ALPHABET] != 2 + 2 * alphaN || alphaN > 255) fail("bad alphabet")
            val alphabet = CharArray(alphaN) { buf.getChar(a + 2 + it * 2) }
            for (i in 1 until alphaN) if (alphabet[i] <= alphabet[i - 1]) fail("bad alphabet order")
            if (
                !uniMin.isFinite() ||
                    !uniStep.isFinite() ||
                    !bgMin.isFinite() ||
                    !bgStep.isFinite() ||
                    !tgMin.isFinite() ||
                    !tgStep.isFinite()
            ) {
                fail("non-finite quantization parameters")
            }

            val n = nodeCount.toLong()
            val nodesOff = need(Fmt.SEC_TRIE_NODES, n * Fmt.NODE_RECORD)
            val samplesOff = need(Fmt.SEC_TRIE_SAMPLES, ((n + 15) / 16) * 4)
            val bitmapOff = need(Fmt.SEC_TERM_BITMAP, ((n + 63) / 64) * Fmt.BITMAP_BLOCK_BYTES)
            val termOff = need(Fmt.SEC_TERM_VALUES, keyCount.toLong() * 3)
            val multiOff = need(Fmt.SEC_MULTI_IDS, -1)
            val infoOff = need(Fmt.SEC_WORD_INFO, wordCount.toLong() * 2)
            val idNodeOff = need(Fmt.SEC_ID_TO_NODE, wordCount.toLong() * 3)
            val caseIdxOff = need(Fmt.SEC_CASE_INDEX, caseCount.toLong() * 6)
            val casePoolOff = need(Fmt.SEC_CASE_POOL, -1)
            val bgIdx =
                need(
                    Fmt.SEC_BIGRAM_INDEX,
                    ((bgLists.toLong() + Fmt.LIST_STRIDE - 1) / Fmt.LIST_STRIDE) * 8,
                )
            val bgData = need(Fmt.SEC_BIGRAM_DATA, -1)
            val tgIdx =
                need(
                    Fmt.SEC_TRIGRAM_INDEX,
                    ((tgLists.toLong() + Fmt.LIST_STRIDE - 1) / Fmt.LIST_STRIDE) * 4,
                )
            val tgData = need(Fmt.SEC_TRIGRAM_DATA, -1)
            // Every list takes at least one byte.
            if (secLen[Fmt.SEC_BIGRAM_DATA] < bgLists || secLen[Fmt.SEC_TRIGRAM_DATA] < tgLists) {
                fail("n-gram data shorter than its list count")
            }

            // META.
            val m = need(Fmt.SEC_META, -1)
            val metaEnd = m + secLen[Fmt.SEC_META]
            var mp = m
            fun readStr(): String {
                if (mp + 4 > metaEnd) fail("bad metadata")
                val len = buf.getInt(mp)
                mp += 4
                if (len < 0 || mp.toLong() + len > metaEnd) fail("bad metadata")
                val bytes = ByteArray(len)
                for (i in 0 until len) bytes[i] = buf.get(mp + i)
                mp += len
                return String(bytes, Charsets.UTF_8)
            }
            if (m + 4 > metaEnd) fail("bad metadata")
            val dataVersion = buf.getInt(mp)
            mp += 4
            val lang = readStr()
            val date = readStr()
            val attribution = readStr()

            return Dictionary(
                buf = buf,
                metadata = DictionaryMetadata(lang, date, dataVersion, attribution),
                wordCount = wordCount,
                nodeCount = nodeCount,
                keyCount = keyCount,
                caseCount = caseCount,
                uniMin = uniMin,
                uniStep = uniStep,
                bigramCount = bgCount,
                trigramCount = tgCount,
                alphabet = alphabet,
                nodesOff = nodesOff,
                samplesOff = samplesOff,
                bitmapOff = bitmapOff,
                termOff = termOff,
                multiOff = multiOff,
                multiEnd = multiOff + secLen[Fmt.SEC_MULTI_IDS],
                infoOff = infoOff,
                idNodeOff = idNodeOff,
                caseIdxOff = caseIdxOff,
                casePoolOff = casePoolOff,
                casePoolEnd = casePoolOff + secLen[Fmt.SEC_CASE_POOL],
                bigrams =
                    NgramTable(
                        buf,
                        bgIdx,
                        bgData,
                        bgData + secLen[Fmt.SEC_BIGRAM_DATA],
                        bgLists,
                        true,
                        bgMin,
                        bgStep,
                    ),
                trigrams =
                    NgramTable(
                        buf,
                        tgIdx,
                        tgData,
                        tgData + secLen[Fmt.SEC_TRIGRAM_DATA],
                        tgLists,
                        false,
                        tgMin,
                        tgStep,
                    ),
            )
        }
    }
}
