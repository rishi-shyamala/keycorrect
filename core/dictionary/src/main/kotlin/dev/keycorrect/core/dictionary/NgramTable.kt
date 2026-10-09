package dev.keycorrect.core.dictionary

import java.nio.ByteBuffer

/**
 * Read side of one n-gram table (bigram or trigram): a sequence of successor lists, each sorted by
 * successor id, addressed by ordinal through a sampled index. Stateless; safe for concurrent use.
 * See FORMAT.md "N-gram tables".
 *
 * Entry byte: bits 0..6 = quantized probability q (0 = context-only, no probability), bit 7 = "has
 * trigram list" (bigram table only).
 */
internal class NgramTable(
    private val buf: ByteBuffer,
    private val indexOff: Int,
    private val dataOff: Int,
    /** Absolute end (exclusive) of the data section; every list read is bounded by it. */
    private val dataEnd: Int,
    val listCount: Int,
    private val flagged: Boolean,
    private val minLp: Float,
    private val step: Float,
) {
    private val indexStride = if (flagged) 8 else 4
    private val skipSize = if (flagged) 9 else 6

    private fun corrupt(msg: String): Nothing =
        throw DictionaryFormatException("corrupt n-gram data: $msg")

    /** Runs [block], turning any stray out-of-range read into a [DictionaryFormatException]. */
    private inline fun <T> guard(block: () -> T): T =
        try {
            block()
        } catch (e: IndexOutOfBoundsException) {
            throw DictionaryFormatException("corrupt n-gram data: out-of-bounds read", e)
        }

    private fun checkPos(pos: Int) {
        if (pos < dataOff || pos >= dataEnd) corrupt("list position outside the data section")
    }

    /**
     * Validates a list header at [pos] and returns its entry count; `n` entries need >= n bytes.
     */
    private fun checkedCount(pos: Int): Int {
        checkPos(pos)
        val n = Fmt.readVarint(buf, pos)
        if (n.toLong() > (dataEnd - pos).toLong()) corrupt("entry count exceeds the section")
        return n
    }

    fun decode(q: Int): Float = minLp + (q - 1) * step

    /**
     * Locates list [ordinal]. Returns -1 if out of range or empty, else `(flagsBefore shl 32) or
     * pos` where pos is the absolute offset of the list header and flagsBefore the number of
     * flagged entries in all earlier lists (bigram table only).
     */
    fun locate(ordinal: Int): Long = guard {
        if (ordinal < 0 || ordinal >= listCount) return -1L
        val j = ordinal ushr Fmt.LIST_SHIFT
        val start = buf.getInt(indexOff + j * indexStride)
        if (start < 0 || start >= dataEnd - dataOff)
            corrupt("index offset outside the data section")
        var pos = dataOff + start
        var fl = if (flagged) buf.getInt(indexOff + j * indexStride + 4) else 0
        var t = ordinal and (Fmt.LIST_STRIDE - 1)
        while (t > 0) {
            checkPos(pos)
            val n = Fmt.readVarint(buf, pos)
            val ns = Fmt.varintSize(n)
            when {
                n == 0 -> pos += 1
                n == 1 -> {
                    val idLen = Fmt.varintSize(Fmt.readVarint(buf, pos + ns))
                    if (flagged && Fmt.u8(buf, pos + ns + idLen) and 0x80 != 0) fl++
                    pos += ns + idLen + 1
                }
                else -> {
                    val len = Fmt.readVarint(buf, pos + ns)
                    val ls = Fmt.varintSize(len)
                    if (flagged) fl += Fmt.readVarint(buf, pos + ns + ls)
                    if (len.toLong() > (dataEnd - pos).toLong())
                        corrupt("list length exceeds the section")
                    pos += ns + ls + len
                }
            }
            t--
        }
        checkPos(pos)
        if (buf.get(pos).toInt() == 0) return -1L
        (fl.toLong() shl 32) or pos.toLong()
    }

    fun positionOf(located: Long): Int = located.toInt()

    fun flagsBeforeOf(located: Long): Int = (located ushr 32).toInt()

    fun entryCount(pos: Int): Int = guard { checkedCount(pos) }

    /**
     * Finds [id] in the list at [pos]. Returns -1 if absent, else `(flaggedBeforeInList shl 8) or
     * entryByte`.
     */
    fun lookup(pos: Int, id: Int): Long = guard {
        val n = checkedCount(pos)
        var p = pos + Fmt.varintSize(n)
        if (n > 1) {
            p += Fmt.varintSize(Fmt.readVarint(buf, p)) // byte length
            if (flagged) p += Fmt.varintSize(Fmt.readVarint(buf, p)) // flagged count
        }
        if (n <= Fmt.NGRAM_BLOCK) return@guard scan(p, n, -1, id, 0)
        val nb = (n + Fmt.NGRAM_BLOCK - 1) / Fmt.NGRAM_BLOCK
        if (p.toLong() + nb.toLong() * skipSize > dataEnd) corrupt("skip table exceeds the section")
        if (Fmt.u24(buf, p) > id) return@guard -1L
        var lo = 0
        var hi = nb - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (Fmt.u24(buf, p + mid * skipSize) <= id) lo = mid else hi = mid - 1
        }
        val s = p + lo * skipSize
        val entries = p + nb * skipSize
        val cnt = minOf(Fmt.NGRAM_BLOCK, n - lo * Fmt.NGRAM_BLOCK)
        val fr = if (flagged) Fmt.u24(buf, s + 6) else 0
        val blockStart = entries + Fmt.u24(buf, s + 3)
        if (blockStart >= dataEnd) corrupt("block offset outside the section")
        scan(blockStart, cnt, Fmt.u24(buf, s), id, fr)
    }

    private fun scan(start: Int, cnt: Int, knownFirst: Int, target: Int, flagBase: Int): Long {
        var p = start
        var prev = -1
        var fr = flagBase
        for (i in 0 until cnt) {
            val id: Int
            if (i == 0 && knownFirst >= 0) {
                id = knownFirst
            } else {
                val d = Fmt.readVarint(buf, p)
                p += Fmt.varintSize(d)
                id = prev + 1 + d
            }
            val b = Fmt.u8(buf, p)
            p++
            if (id == target) return (fr.toLong() shl 8) or b.toLong()
            if (id > target) return -1L
            if (b and 0x80 != 0) fr++
            prev = id
        }
        return -1L
    }

    /**
     * Collects the [k] highest-probability entries of the list at [pos] into the output arrays,
     * sorted by probability descending (ties: lower id first). Context-only entries are skipped.
     * Returns the number written.
     */
    fun top(pos: Int, k: Int, outIds: IntArray, outLp: FloatArray): Int = guard {
        val n = checkedCount(pos)
        var p = pos + Fmt.varintSize(n)
        if (n > 1) {
            p += Fmt.varintSize(Fmt.readVarint(buf, p)) // byte length
            if (flagged) p += Fmt.varintSize(Fmt.readVarint(buf, p)) // flagged count
        }
        if (n <= Fmt.NGRAM_BLOCK) return@guard collect(p, n, -1, k, outIds, outLp, 0)
        val nb = (n + Fmt.NGRAM_BLOCK - 1) / Fmt.NGRAM_BLOCK
        if (p.toLong() + nb.toLong() * skipSize > dataEnd) corrupt("skip table exceeds the section")
        val entries = p + nb * skipSize
        var size = 0
        for (b in 0 until nb) {
            val s = p + b * skipSize
            val cnt = minOf(Fmt.NGRAM_BLOCK, n - b * Fmt.NGRAM_BLOCK)
            val blockStart = entries + Fmt.u24(buf, s + 3)
            if (blockStart >= dataEnd) corrupt("block offset outside the section")
            size = collect(blockStart, cnt, Fmt.u24(buf, s), k, outIds, outLp, size)
        }
        size
    }

    private fun collect(
        start: Int,
        cnt: Int,
        knownFirst: Int,
        k: Int,
        ids: IntArray,
        lps: FloatArray,
        sizeIn: Int,
    ): Int {
        var size = sizeIn
        var p = start
        var prev = -1
        for (i in 0 until cnt) {
            val id: Int
            if (i == 0 && knownFirst >= 0) {
                id = knownFirst
            } else {
                val d = Fmt.readVarint(buf, p)
                p += Fmt.varintSize(d)
                id = prev + 1 + d
            }
            val q = Fmt.u8(buf, p) and 0x7F
            p++
            prev = id
            if (q == 0) continue
            val lp = decode(q)
            if (size == k && lp <= lps[k - 1]) continue
            var j = if (size < k) size else k - 1
            while (j > 0 && lp > lps[j - 1]) {
                ids[j] = ids[j - 1]
                lps[j] = lps[j - 1]
                j--
            }
            ids[j] = id
            lps[j] = lp
            if (size < k) size++
        }
        return size
    }
}
