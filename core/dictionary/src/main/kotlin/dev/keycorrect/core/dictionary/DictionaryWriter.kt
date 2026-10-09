package dev.keycorrect.core.dictionary

import java.io.OutputStream
import java.util.zip.CRC32

/**
 * Offline compiler from [DictionaryData] to the KCD1 binary format (FORMAT.md). Output is a pure
 * function of the input set: the order of words, bigrams and trigrams in the input does not matter.
 */
object DictionaryWriter {
    /**
     * Compiles [data]. With [skipUnknownNgrams] false (default) an n-gram that mentions a word not
     * in the vocabulary throws [IllegalArgumentException]; with true it is silently dropped.
     */
    @JvmStatic
    @JvmOverloads
    fun write(data: DictionaryData, skipUnknownNgrams: Boolean = false): ByteArray =
        Compiler(data, skipUnknownNgrams).compile()

    @JvmStatic
    @JvmOverloads
    fun write(data: DictionaryData, out: OutputStream, skipUnknownNgrams: Boolean = false) {
        out.write(write(data, skipUnknownNgrams))
    }
}

/** Growable little-endian byte sink. */
internal class ByteSink(initial: Int = 256) {
    private var arr = ByteArray(initial)
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > arr.size) arr = arr.copyOf(maxOf(arr.size * 2, size + extra))
    }

    fun u8(v: Int) {
        ensure(1)
        arr[size++] = v.toByte()
    }

    fun u16(v: Int) {
        u8(v)
        u8(v ushr 8)
    }

    fun u24(v: Int) {
        u8(v)
        u8(v ushr 8)
        u8(v ushr 16)
    }

    fun u32(v: Int) {
        u8(v)
        u8(v ushr 8)
        u8(v ushr 16)
        u8(v ushr 24)
    }

    fun u64(v: Long) {
        u32(v.toInt())
        u32((v ushr 32).toInt())
    }

    fun f32(v: Float) = u32(java.lang.Float.floatToIntBits(v))

    fun varint(value: Int) {
        var v = value
        while (v >= 0x80) {
            u8((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        u8(v)
    }

    fun bytes(src: ByteArray, off: Int = 0, len: Int = src.size) {
        ensure(len)
        System.arraycopy(src, off, arr, size, len)
        size += len
    }

    fun bytes(other: ByteSink) = bytes(other.arr, 0, other.size)

    fun toByteArray(): ByteArray = arr.copyOf(size)
}

private class Compiler(val data: DictionaryData, val skipUnknown: Boolean) {
    private class W(val surface: String, val key: String, val lp: Float, val userFlags: Int) {
        var id = 0
        var q = 0
    }

    fun compile(): ByteArray {
        // ---- words, quantization, ids
        val ws = ArrayList<W>(data.words.size)
        val seen = HashSet<String>(data.words.size * 2)
        for (w in data.words) {
            require(w.surface.isNotEmpty()) { "empty surface" }
            require(w.surface.length <= Dictionary.MAX_KEY_LENGTH) {
                "surface longer than ${Dictionary.MAX_KEY_LENGTH} chars: ${w.surface.take(40)}..."
            }
            require(w.surface != Dictionary.BOS_TOKEN && w.surface != Dictionary.EOS_TOKEN) {
                "reserved token as word: ${w.surface}"
            }
            require(w.logProb.isFinite()) { "non-finite logProb for ${w.surface}" }
            require(w.flags and WordFlags.USER_MASK.inv() == 0) {
                "unsupported flags ${w.flags} for ${w.surface}"
            }
            require(seen.add(w.surface)) { "duplicate surface: ${w.surface}" }
            ws.add(W(w.surface, Dictionary.normalizeKey(w.surface), w.logProb, w.flags))
        }
        ws.sortWith(compareByDescending<W> { it.lp }.thenBy { it.surface })
        val wordCount = ws.size + Dictionary.FIRST_WORD_ID
        require(wordCount <= Fmt.MAX_ID) { "too many words" }
        var uMin = 0f
        var uStep = 0f
        if (ws.isNotEmpty()) {
            uMin = ws.minOf { it.lp }
            val uMax = ws.maxOf { it.lp }
            uStep = ((uMax.toDouble() - uMin) / 255.0).toFloat()
        }
        val idOf = HashMap<String, Int>(ws.size * 2 + 4)
        idOf[Dictionary.BOS_TOKEN] = Dictionary.BOS_ID
        idOf[Dictionary.EOS_TOKEN] = Dictionary.EOS_ID
        for ((i, w) in ws.withIndex()) {
            w.id = Dictionary.FIRST_WORD_ID + i
            w.q =
                if (uStep == 0f) 0
                else
                    Math.round((w.lp - uMin).toDouble() / uStep.toDouble()).toInt().coerceIn(0, 255)
            idOf[w.surface] = w.id
        }

        // ---- word info + case patterns
        val info = ByteSink(wordCount * 2)
        info.u8(0)
        info.u8(WordFlags.SPECIAL)
        info.u8(0)
        info.u8(WordFlags.SPECIAL)
        val caseExceptions = ArrayList<W>()
        for (w in ws) {
            val pattern = CaseUtil.detect(w.key, w.surface)
            if (pattern == Fmt.CASE_CUSTOM) caseExceptions.add(w)
            var f = w.userFlags
            if (w.surface != w.key) f = f or WordFlags.HAS_CAPITAL
            info.u8(w.q)
            info.u8(f or (pattern shl 6))
        }
        val casePool = ByteSink()
        val caseIndex = ByteSink()
        for (w in caseExceptions) { // ascending id by construction
            caseIndex.u24(w.id)
            caseIndex.u24(casePool.size)
            val b = w.surface.toByteArray(Charsets.UTF_8)
            casePool.varint(b.size)
            casePool.bytes(b)
        }

        // ---- keys -> ids
        val byKey = HashMap<String, ArrayList<W>>()
        for (w in ws) byKey.getOrPut(w.key) { ArrayList(1) }.add(w) // ascending id
        val keys = byKey.keys.toTypedArray()
        java.util.Arrays.sort(keys)
        val keyCount = keys.size

        val used = BooleanArray(65536)
        for (k in keys) for (c in k) used[c.code] = true
        val alphabet = ArrayList<Char>()
        for (c in 0 until 65536) if (used[c]) alphabet.add(c.toChar())
        require(alphabet.size <= 255) { "alphabet too large: ${alphabet.size} distinct characters" }
        val labelOf = IntArray(65536)
        for ((i, c) in alphabet.withIndex()) labelOf[c.code] = i

        // ---- trie (BFS)
        var qLo = IntArray(1024)
        var qHi = IntArray(1024)
        var qDepth = IntArray(1024)
        var qChar = CharArray(1024)
        var termKey = IntArray(1024)
        var childCnt = IntArray(1024)
        var qSize = 0
        fun push(lo: Int, hi: Int, d: Int, c: Char) {
            if (qSize == qLo.size) {
                val n = qSize * 2
                qLo = qLo.copyOf(n)
                qHi = qHi.copyOf(n)
                qDepth = qDepth.copyOf(n)
                qChar = qChar.copyOf(n)
                termKey = termKey.copyOf(n)
                childCnt = childCnt.copyOf(n)
            }
            qLo[qSize] = lo
            qHi[qSize] = hi
            qDepth[qSize] = d
            qChar[qSize] = c
            qSize++
        }
        push(0, keyCount, 0, '\u0000')
        var head = 0
        while (head < qSize) {
            val lo = qLo[head]
            val hi = qHi[head]
            val d = qDepth[head]
            var i = lo
            if (lo < hi && keys[lo].length == d) {
                termKey[head] = lo
                i = lo + 1
            } else {
                termKey[head] = -1
            }
            var cc = 0
            while (i < hi) {
                val c = keys[i][d]
                var j = i + 1
                while (j < hi && keys[j][d] == c) j++
                push(i, j, d + 1, c)
                cc++
                i = j
            }
            require(cc <= 255) { "too many children" }
            childCnt[head] = cc
            head++
        }
        val nodeCount = qSize
        require(nodeCount < Fmt.NO_NODE) { "trie too large" }
        val firstChild = IntArray(nodeCount)
        var running = 1
        for (i in 0 until nodeCount) {
            firstChild[i] = running
            running += childCnt[i]
        }
        val maxQ = IntArray(nodeCount)
        for (i in nodeCount - 1 downTo 0) {
            var m = 0
            if (termKey[i] >= 0) m = byKey.getValue(keys[termKey[i]]).maxOf { it.q }
            for (c in firstChild[i] until firstChild[i] + childCnt[i]) if (maxQ[c] > m) m = maxQ[c]
            maxQ[i] = m
        }

        val nodes = ByteSink(nodeCount * 3)
        for (i in 0 until nodeCount) {
            nodes.u8(childCnt[i])
            nodes.u8(if (i == 0) 0 else labelOf[qChar[i].code])
            nodes.u8(maxQ[i])
        }
        val samples = ByteSink()
        for (i in 0 until nodeCount step Fmt.SAMPLE_STRIDE) samples.u32(firstChild[i])

        val bitmap = ByteSink()
        val termValues = ByteSink(keyCount * 3)
        val multi = ByteSink()
        val idToNode = IntArray(wordCount) { Fmt.NO_NODE }
        var cum = 0
        var block = 0
        while (block * 64 < nodeCount) {
            var bits = 0L
            val start = block * 64
            for (b in 0 until minOf(64, nodeCount - start)) {
                val node = start + b
                val tk = termKey[node]
                if (tk >= 0) {
                    bits = bits or (1L shl b)
                    val list = byKey.getValue(keys[tk])
                    for (w in list) idToNode[w.id] = node
                    if (list.size == 1) {
                        termValues.u24(list[0].id)
                    } else {
                        require(list.size <= 255) { "too many surface forms for ${keys[tk]}" }
                        termValues.u24(Fmt.MULTI_FLAG or multi.size)
                        multi.u8(list.size)
                        for (w in list) multi.u24(w.id)
                    }
                }
            }
            bitmap.u32(cum)
            bitmap.u64(bits)
            cum += java.lang.Long.bitCount(bits)
            block++
        }
        require(multi.size < Fmt.MULTI_FLAG) { "multi-id section too large" }
        val idNode = ByteSink(wordCount * 3)
        for (n in idToNode) idNode.u24(n)

        // ---- n-grams
        val ng = buildNgrams(idOf)

        // ---- params / meta / alphabet
        val params = ByteSink(Fmt.PARAMS_SIZE)
        params.u32(wordCount)
        params.u32(nodeCount)
        params.u32(keyCount)
        params.u32(caseExceptions.size)
        params.f32(uMin)
        params.f32(uStep)
        params.f32(ng.bgMin)
        params.f32(ng.bgStep)
        params.f32(ng.tgMin)
        params.f32(ng.tgStep)
        params.u32(ng.bigramTable.count)
        params.u32(ng.trigramTable.count)
        params.u32(ng.bigramReal)
        params.u32(ng.trigramReal)
        params.u32(ng.bigramContextOnly)
        params.u32(0)
        check(params.size == Fmt.PARAMS_SIZE)

        val meta = ByteSink()
        meta.u32(data.metadata.dataVersion)
        for (s in
            listOf(data.metadata.languageTag, data.metadata.buildDate, data.metadata.attribution)) {
            val b = s.toByteArray(Charsets.UTF_8)
            meta.u32(b.size)
            meta.bytes(b)
        }
        val alpha = ByteSink()
        alpha.u16(alphabet.size)
        for (c in alphabet) alpha.u16(c.code)

        val sections =
            listOf(
                Fmt.SEC_META to meta,
                Fmt.SEC_PARAMS to params,
                Fmt.SEC_ALPHABET to alpha,
                Fmt.SEC_TRIE_NODES to nodes,
                Fmt.SEC_TRIE_SAMPLES to samples,
                Fmt.SEC_TERM_BITMAP to bitmap,
                Fmt.SEC_TERM_VALUES to termValues,
                Fmt.SEC_MULTI_IDS to multi,
                Fmt.SEC_WORD_INFO to info,
                Fmt.SEC_ID_TO_NODE to idNode,
                Fmt.SEC_CASE_INDEX to caseIndex,
                Fmt.SEC_CASE_POOL to casePool,
                Fmt.SEC_BIGRAM_INDEX to ng.bigramTable.index,
                Fmt.SEC_BIGRAM_DATA to ng.bigramTable.data,
                Fmt.SEC_TRIGRAM_INDEX to ng.trigramTable.index,
                Fmt.SEC_TRIGRAM_DATA to ng.trigramTable.data,
            )
        return assemble(sections)
    }

    private fun assemble(sections: List<Pair<Int, ByteSink>>): ByteArray {
        val tableEnd = Fmt.HEADER_SIZE + sections.size * Fmt.SECTION_ENTRY_SIZE
        val offsets = LongArray(sections.size)
        var pos = ((tableEnd + 7) / 8 * 8).toLong()
        for ((i, s) in sections.withIndex()) {
            offsets[i] = pos
            pos = (pos + s.second.size + 7) / 8 * 8
        }
        require(pos < 0xFFFFFFFFL) { "dictionary exceeds 4 GiB" }
        val result = ByteSink(pos.toInt())
        result.u8('K'.code)
        result.u8('C'.code)
        result.u8('D'.code)
        result.u8('1'.code)
        result.u16(Fmt.VERSION)
        result.u16(0)
        result.u32(pos.toInt())
        result.u32(sections.size)
        result.u32(0) // crc placeholder, patched below
        result.u32(0)
        result.u32(0)
        result.u32(0)
        for ((i, s) in sections.withIndex()) {
            result.u32(s.first)
            result.u32(offsets[i].toInt())
            result.u32(s.second.size)
        }
        for ((i, s) in sections.withIndex()) {
            while (result.size < offsets[i]) result.u8(0)
            result.bytes(s.second)
        }
        while (result.size < pos) result.u8(0)
        val bytes = result.toByteArray()
        val crc = CRC32()
        crc.update(bytes, 0, tableEnd) // crc field is zero here
        for (id in Fmt.CRC_SECTIONS) {
            val i = sections.indexOfFirst { it.first == id }
            crc.update(bytes, offsets[i].toInt(), sections[i].second.size)
        }
        val v = crc.value.toInt()
        for (b in 0 until 4) bytes[Fmt.CRC_OFFSET + b] = (v ushr (8 * b)).toByte()
        return bytes
    }

    // ------------------------------------------------------------------ n-grams

    private class Table {
        val index = ByteSink()
        val data = ByteSink()
        var count = 0
        var cumFlagged = 0
    }

    private class Ngrams(
        val bigramTable: Table,
        val trigramTable: Table,
        val bgMin: Float,
        val bgStep: Float,
        val tgMin: Float,
        val tgStep: Float,
        val bigramReal: Int,
        val trigramReal: Int,
        val bigramContextOnly: Int,
    )

    private fun quant(values: FloatArray, n: Int): Pair<Float, Float> {
        if (n == 0) return 0f to 0f
        var mn = Float.POSITIVE_INFINITY
        var mx = Float.NEGATIVE_INFINITY
        for (i in 0 until n) {
            require(values[i].isFinite()) { "non-finite n-gram logProb" }
            if (values[i] < mn) mn = values[i]
            if (values[i] > mx) mx = values[i]
        }
        return mn to ((mx.toDouble() - mn) / 126.0).toFloat()
    }

    private fun q7(lp: Float, mn: Float, step: Float): Int =
        if (step == 0f) 1
        else (1 + Math.round((lp - mn).toDouble() / step.toDouble()).toInt()).coerceIn(1, 127)

    private fun buildNgrams(idOf: Map<String, Int>): Ngrams {
        // bigrams
        var n = 0
        val b1 = IntArray(data.bigrams.size)
        val b2 = IntArray(data.bigrams.size)
        val blp = FloatArray(data.bigrams.size)
        for (b in data.bigrams) {
            val i1 = idOf[b.w1]
            val i2 = idOf[b.w2]
            if (i1 == null || i2 == null || i1 == Dictionary.EOS_ID || i2 == Dictionary.BOS_ID) {
                require(skipUnknown) { "bigram uses unknown/invalid word: $b" }
                continue
            }
            b1[n] = i1
            b2[n] = i2
            blp[n] = b.logProb
            n++
        }
        val (bgMin, bgStep) = quant(blp, n)
        val bgKeys = LongArray(n) // pair48 shl 8 | q
        for (i in 0 until n) {
            bgKeys[i] =
                (((b1[i].toLong() shl 24) or b2[i].toLong()) shl 8) or
                    q7(blp[i], bgMin, bgStep).toLong()
        }
        bgKeys.sort()
        for (i in 1 until n) {
            require((bgKeys[i] ushr 8) != (bgKeys[i - 1] ushr 8)) {
                "duplicate bigram ${(bgKeys[i] ushr 32)} ${(bgKeys[i] ushr 8) and 0xFFFFFF}"
            }
        }

        // trigrams
        var tn = 0
        val t1 = IntArray(data.trigrams.size)
        val t2 = IntArray(data.trigrams.size)
        val t3 = IntArray(data.trigrams.size)
        val tlp = FloatArray(data.trigrams.size)
        for (t in data.trigrams) {
            val i1 = idOf[t.w1]
            val i2 = idOf[t.w2]
            val i3 = idOf[t.w3]
            if (
                i1 == null ||
                    i2 == null ||
                    i3 == null ||
                    i1 == Dictionary.EOS_ID ||
                    i2 == Dictionary.EOS_ID ||
                    i2 == Dictionary.BOS_ID ||
                    i3 == Dictionary.BOS_ID
            ) {
                require(skipUnknown) { "trigram uses unknown/invalid word: $t" }
                continue
            }
            t1[tn] = i1
            t2[tn] = i2
            t3[tn] = i3
            tlp[tn] = t.logProb
            tn++
        }
        val (tgMin, tgStep) = quant(tlp, tn)
        val pairKeys = LongArray(tn)
        for (i in 0 until tn) pairKeys[i] = (t1[i].toLong() shl 24) or t2[i].toLong()
        val sortedPairs = pairKeys.copyOf().also { it.sort() }
        var pc = 0
        for (i in sortedPairs.indices) {
            if (i == 0 || sortedPairs[i] != sortedPairs[i - 1]) sortedPairs[pc++] = sortedPairs[i]
        }
        val triPairs = sortedPairs.copyOf(pc)
        val tgKeys = LongArray(tn) // pairIdx shl 32 | w3 shl 8 | q
        for (i in 0 until tn) {
            val pi = java.util.Arrays.binarySearch(triPairs, pairKeys[i])
            tgKeys[i] =
                (pi.toLong() shl 32) or (t3[i].toLong() shl 8) or q7(tlp[i], tgMin, tgStep).toLong()
        }
        tgKeys.sort()
        for (i in 1 until tn) {
            require((tgKeys[i] ushr 8) != (tgKeys[i - 1] ushr 8)) { "duplicate trigram" }
        }

        // context-only bigrams for trigram contexts without a bigram
        val realKeys = LongArray(n) { bgKeys[it] ushr 8 } // sorted pair48
        var extra = 0
        val missing = LongArray(pc)
        for (p in triPairs) {
            if (java.util.Arrays.binarySearch(realKeys, p) < 0) missing[extra++] = p
        }
        val all = LongArray(n + extra)
        System.arraycopy(bgKeys, 0, all, 0, n)
        for (i in 0 until extra) all[n + i] = missing[i] shl 8 // q = 0
        all.sort()

        // flag bit + encode bigram lists
        val bigramTable = Table()
        val trigramTable = Table()
        val ids = IntArray(all.size)
        val bytes = IntArray(all.size)
        for (i in all.indices) {
            val pair = all[i] ushr 8
            ids[i] = (pair and 0xFFFFFF).toInt()
            val flag = if (java.util.Arrays.binarySearch(triPairs, pair) >= 0) 0x80 else 0
            bytes[i] = (all[i] and 0x7F).toInt() or flag
        }
        if (all.isNotEmpty()) {
            val maxW1 = (all[all.size - 1] ushr 32).toInt()
            var from = 0
            for (w1 in 0..maxW1) {
                var to = from
                while (to < all.size && (all[to] ushr 32).toInt() == w1) to++
                addList(bigramTable, true, ids, bytes, from, to)
                from = to
            }
        }
        // trigram lists, one per flagged pair in pair order
        if (tn > 0) {
            val tids = IntArray(tn)
            val tbytes = IntArray(tn)
            for (i in 0 until tn) {
                tids[i] = ((tgKeys[i] ushr 8) and 0xFFFFFF).toInt()
                tbytes[i] = (tgKeys[i] and 0x7F).toInt()
            }
            var from = 0
            for (pi in triPairs.indices) {
                var to = from
                while (to < tn && (tgKeys[to] ushr 32).toInt() == pi) to++
                addList(trigramTable, false, tids, tbytes, from, to)
                from = to
            }
        }
        return Ngrams(bigramTable, trigramTable, bgMin, bgStep, tgMin, tgStep, n, tn, extra)
    }

    private fun addList(
        t: Table,
        flagged: Boolean,
        ids: IntArray,
        bytes: IntArray,
        from: Int,
        to: Int,
    ) {
        if (t.count % Fmt.LIST_STRIDE == 0) {
            t.index.u32(t.data.size)
            if (flagged) t.index.u32(t.cumFlagged)
        }
        t.count++
        val n = to - from
        if (n == 0) {
            t.data.u8(0)
            return
        }
        var flaggedCount = 0
        if (flagged) for (i in from until to) if (bytes[i] and 0x80 != 0) flaggedCount++
        val body = ByteSink()
        if (n > 1 && flagged) body.varint(flaggedCount)
        val entries = ByteSink()
        if (n > Fmt.NGRAM_BLOCK) {
            val nb = (n + Fmt.NGRAM_BLOCK - 1) / Fmt.NGRAM_BLOCK
            val skip = ByteSink()
            var cum = 0
            var prev = -1
            for (i in 0 until n) {
                val id = ids[from + i]
                if (i % Fmt.NGRAM_BLOCK == 0) {
                    require(id <= Fmt.MAX_ID && entries.size <= Fmt.MAX_ID && cum <= Fmt.MAX_ID) {
                        "n-gram list too large"
                    }
                    skip.u24(id)
                    skip.u24(entries.size)
                    if (flagged) skip.u24(cum)
                } else {
                    entries.varint(id - prev - 1)
                }
                entries.u8(bytes[from + i])
                if (bytes[from + i] and 0x80 != 0) cum++
                prev = id
            }
            check(skip.size == nb * (if (flagged) 9 else 6))
            body.bytes(skip)
        } else {
            var prev = -1
            for (i in 0 until n) {
                val id = ids[from + i]
                entries.varint(id - prev - 1)
                entries.u8(bytes[from + i])
                prev = id
            }
        }
        body.bytes(entries)
        t.data.varint(n)
        if (n > 1) t.data.varint(body.size)
        t.data.bytes(body)
        t.cumFlagged += flaggedCount
    }
}
