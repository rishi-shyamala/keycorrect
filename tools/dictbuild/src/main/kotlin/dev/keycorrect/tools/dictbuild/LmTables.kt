package dev.keycorrect.tools.dictbuild

import java.io.BufferedReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** One row of `vocab.tsv`. */
class VocabRow(val word: String, val freq: Int, val originalFreq: Int, val flags: Set<String>)

/**
 * Raw (unweighted, per corpus) counts read from `data/processed/`, indexed by *word index*: `0 =
 * <s>`, `1..V` = [words] in `vocab.tsv` order (including `not_a_word` rows, which simply have no
 * counts).
 *
 * The n-gram tables are the *pruned* tables written by `dictbuild count`; the `*Ctx*` arrays carry
 * the statistics of the corresponding **unpruned** tables (context totals and number of distinct
 * successors) that are needed to normalise probabilities, and [cont] the Kneser-Ney continuation
 * counts (number of distinct predecessors of a word).
 */
class LmTables(
    val vocab: List<VocabRow>,
    /** `words[0] = "<s>"`, then the vocabulary surfaces. */
    val words: Array<String>,
    val uniWiki: LongArray,
    val uniTat: LongArray,
    val cont: IntArray,
    val bi: NgramRows,
    /** Per `w1`: total wiki / tatoeba count and number of distinct successors of the full table. */
    val biCtx: ContextStats,
    val tri: NgramRows,
    /** Context (w1, w2) statistics, keyed by [pairKey]. */
    val triCtx: PairContextStats,
    /** Raw count-of-counts (n1..n4) of the unpruned bigram / trigram tables, if known. */
    val biCountOfCounts: LongArray? = null,
    val triCountOfCounts: LongArray? = null,
) {
    val vocabSize: Int
        get() = words.size - 1

    companion object {
        fun pairKey(a: Int, b: Int): Long = (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)

        fun load(dir: Path): LmTables {
            val vocab = ArrayList<VocabRow>()
            readTsv(dir.resolve("vocab.tsv")) { p ->
                vocab.add(
                    VocabRow(
                        p[0],
                        p[1].toInt(),
                        p[2].toInt(),
                        if (p.size > 3 && p[3].isNotEmpty()) p[3].split(',').toSet()
                        else emptySet(),
                    )
                )
            }
            val words =
                Array(vocab.size + 1) { if (it == 0) Vocabulary.START else vocab[it - 1].word }
            val index = HashMap<String, Int>(words.size * 2)
            for (i in words.indices) index[words[i]] = i
            fun id(w: String) = index[w] ?: throw IllegalStateException("unknown word '$w'")

            val uniWiki = LongArray(words.size)
            val uniTat = LongArray(words.size)
            val cont = IntArray(words.size)
            readTsv(dir.resolve("unigrams.tsv")) { p ->
                val i = id(p[0])
                uniWiki[i] = p[2].toLong()
                uniTat[i] = p[3].toLong()
                cont[i] = if (p.size > 4) p[4].toInt() else 0
            }

            val bi = NgramRows.read(dir.resolve("bigrams.tsv"), 2, ::id)
            val triRows = NgramRows.read(dir.resolve("trigrams.tsv"), 3, ::id)

            val biCtx = ContextStats(words.size)
            readTsv(dir.resolve("bigram_contexts.tsv")) { p ->
                val i = id(p[0])
                biCtx.wiki[i] = p[1].toLong()
                biCtx.tat[i] = p[2].toLong()
                biCtx.distinct[i] = p[3].toInt()
                biCtx.tatShare[i] = p[4].toDouble()
            }
            val triCtx = PairContextStats()
            readTsv(dir.resolve("trigram_contexts.tsv")) { p ->
                triCtx.add(
                    id(p[0]),
                    id(p[1]),
                    p[2].toLong(),
                    p[3].toLong(),
                    p[4].toInt(),
                    p[5].toDouble(),
                )
            }
            var bc: LongArray? = null
            var tc: LongArray? = null
            val cocPath = dir.resolve("count_of_counts.tsv")
            if (Files.exists(cocPath)) {
                readTsv(cocPath) { p ->
                    val v = LongArray(4) { p[it + 1].toLong() }
                    if (p[0] == "2") bc = v else tc = v
                }
            }
            return LmTables(vocab, words, uniWiki, uniTat, cont, bi, biCtx, triRows, triCtx, bc, tc)
        }

        /** Streams a TSV file, skipping `#` header lines. */
        fun readTsv(path: Path, row: (List<String>) -> Unit) {
            Files.newBufferedReader(path, StandardCharsets.UTF_8).use { r: BufferedReader ->
                r.forEachLine { line ->
                    if (line.isEmpty() || line[0] == '#') return@forEachLine
                    row(line.split('\t'))
                }
            }
        }
    }
}

/** Column-oriented n-gram rows: `ctx` = first order-1 words, `last` = predicted word. */
class NgramRows(
    val order: Int,
    val w1: IntArray,
    /** Second word (only for trigrams, otherwise unused). */
    val w2: IntArray,
    /** Predicted word. */
    val last: IntArray,
    val wiki: IntArray,
    val tat: IntArray,
) {
    val size: Int
        get() = last.size

    companion object {
        fun read(path: Path, order: Int, id: (String) -> Int): NgramRows {
            val a = IntList()
            val b = IntList()
            val c = IntList()
            val wk = IntList()
            val tt = IntList()
            LmTables.readTsv(path) { p ->
                if (order == 2) {
                    a.add(id(p[0]))
                    b.add(0)
                    c.add(id(p[1]))
                    wk.add(p[3].toInt())
                    tt.add(p[4].toInt())
                } else {
                    a.add(id(p[0]))
                    b.add(id(p[1]))
                    c.add(id(p[2]))
                    wk.add(p[4].toInt())
                    tt.add(p[5].toInt())
                }
            }
            return NgramRows(
                order,
                a.toArray(),
                b.toArray(),
                c.toArray(),
                wk.toArray(),
                tt.toArray(),
            )
        }
    }
}

class ContextStats(n: Int) {
    val wiki = LongArray(n)
    val tat = LongArray(n)
    val distinct = IntArray(n)

    /** Sum over the successors of `tatoeba / (wiki + tatoeba)`. */
    val tatShare = DoubleArray(n)
}

/** Context statistics for (w1, w2) pairs, in parallel arrays addressed through [indexOf]. */
class PairContextStats {
    private val index = LongIntMap(1 shl 16)
    private val wikiL = LongList()
    private val tatL = LongList()
    private val distinctL = IntList()
    private val tatShareL = DoubleList()

    val size: Int
        get() = distinctL.size

    fun add(w1: Int, w2: Int, wiki: Long, tat: Long, distinct: Int, tatShare: Double = 0.0) {
        index.put(LmTables.pairKey(w1, w2), distinctL.size)
        wikiL.add(wiki)
        tatL.add(tat)
        distinctL.add(distinct)
        tatShareL.add(tatShare)
    }

    /** Row of context (w1, w2), or -1. */
    fun indexOf(w1: Int, w2: Int): Int = index.get(LmTables.pairKey(w1, w2))

    fun wiki(i: Int): Long = wikiL[i]

    fun tat(i: Int): Long = tatL[i]

    fun distinct(i: Int): Int = distinctL[i]

    fun tatShare(i: Int): Double = tatShareL[i]
}

/** Open-addressing `Long -> Int` map (values >= 0; -1 = absent), no boxing. */
class LongIntMap(initialCapacity: Int = 1024) {
    private var keys = LongArray(Integer.highestOneBit(maxOf(initialCapacity, 8) - 1) shl 1)
    private var vals = IntArray(keys.size)
    private var used = BooleanArray(keys.size)
    var size = 0
        private set

    private fun slot(key: Long, mask: Int): Int {
        var h = key * -0x61c8864680b583ebL
        h = h xor (h ushr 32)
        return h.toInt() and mask
    }

    fun put(key: Long, value: Int) {
        if ((size + 1) * 10 > keys.size * 7) grow()
        val mask = keys.size - 1
        var i = slot(key, mask)
        while (used[i]) {
            if (keys[i] == key) {
                vals[i] = value
                return
            }
            i = (i + 1) and mask
        }
        used[i] = true
        keys[i] = key
        vals[i] = value
        size++
    }

    fun get(key: Long): Int {
        val mask = keys.size - 1
        var i = slot(key, mask)
        while (used[i]) {
            if (keys[i] == key) return vals[i]
            i = (i + 1) and mask
        }
        return -1
    }

    private fun grow() {
        val ok = keys
        val ov = vals
        val ou = used
        keys = LongArray(ok.size * 2)
        vals = IntArray(ok.size * 2)
        used = BooleanArray(ok.size * 2)
        size = 0
        for (i in ok.indices) if (ou[i]) put(ok[i], ov[i])
    }
}

class IntList {
    private var a = IntArray(1024)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    operator fun get(i: Int): Int = a[i]

    fun toArray(): IntArray = a.copyOf(size)
}

class LongList {
    private var a = LongArray(1024)
    var size = 0
        private set

    fun add(v: Long) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    operator fun get(i: Int): Long = a[i]
}

class DoubleList {
    private var a = DoubleArray(1024)
    var size = 0
        private set

    fun add(v: Double) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    operator fun get(i: Int): Double = a[i]
}
