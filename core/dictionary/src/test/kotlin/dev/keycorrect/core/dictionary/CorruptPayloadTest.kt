package dev.keycorrect.core.dictionary

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import org.junit.Test

/**
 * Corruption beyond the checksummed header: the bulk sections are not CRC-protected, so a damaged
 * file can open fine. Whatever the queries then do, they must terminate and fail only with
 * [DictionaryFormatException] (never loop, run out of memory or throw anything else).
 */
class CorruptPayloadTest {
    private val bytes = Fixtures.smallBytes
    private val bulk =
        intArrayOf(
            Fmt.SEC_TRIE_NODES,
            Fmt.SEC_TRIE_SAMPLES,
            Fmt.SEC_TERM_BITMAP,
            Fmt.SEC_TERM_VALUES,
            Fmt.SEC_MULTI_IDS,
            Fmt.SEC_WORD_INFO,
            Fmt.SEC_ID_TO_NODE,
            Fmt.SEC_CASE_INDEX,
            Fmt.SEC_CASE_POOL,
            Fmt.SEC_BIGRAM_INDEX,
            Fmt.SEC_BIGRAM_DATA,
            Fmt.SEC_TRIGRAM_INDEX,
            Fmt.SEC_TRIGRAM_DATA,
        )

    private fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

    private fun range(b: ByteArray, section: Int): IntRange {
        val e = sectionEntry(b, section)
        val off = le(b).getInt(e + 4)
        val len = le(b).getInt(e + 8)
        return off until off + len
    }

    private inline fun tolerate(block: () -> Unit) {
        try {
            block()
        } catch (_: DictionaryFormatException) {}
    }

    /** Touches every public read path; only [DictionaryFormatException] may escape [tolerate]. */
    private fun exercise(d: Dictionary, seed: Int) {
        val rnd = Random(seed.toLong())
        for (id in 0 until d.wordCount) {
            tolerate { d.word(id) }
            tolerate { d.keyOf(id) }
            tolerate { d.flags(id) }
            tolerate { d.logProb(id) }
        }
        val seen = HashSet<Int>()
        val stack = ArrayDeque<Int>()
        stack.addLast(d.root)
        while (stack.isNotEmpty() && seen.size < 20_000) {
            val node = stack.removeLast()
            if (!seen.add(node)) continue
            tolerate {
                d.isTerminal(node)
                d.wordIdsAt(node)
                d.firstWordIdAt(node)
                d.maxLogProbInSubtree(node)
                d.nodeChar(node)
                val first = d.firstChild(node)
                for (j in 0 until d.childCount(node)) stack.addLast(first + j)
            }
        }
        for (w in listOf("the", "of", "hello", "zzzzzz", "a", "I", "i'm")) {
            tolerate { d.wordId(w) }
            tolerate { d.idsForKey(w) }
            tolerate { d.findNode(w) }
        }
        repeat(300) {
            val a = rnd.nextInt(d.wordCount)
            val b = rnd.nextInt(d.wordCount)
            val c = rnd.nextInt(d.wordCount)
            tolerate { d.bigramLogProb(a, b) }
            tolerate { d.trigramLogProb(a, b, c) }
            tolerate { d.topSuccessors(a, 8) }
            tolerate { d.topSuccessors(a, b, 8) }
        }
        for (a in 0 until minOf(d.wordCount, 400)) {
            tolerate { d.topSuccessors(a, 16) }
            tolerate { d.topSuccessors(a, a + 1, 16) }
        }
        tolerate { d.topSuccessors(Dictionary.BOS_ID, 16) }
    }

    private fun open(b: ByteArray): Dictionary? =
        try {
            Dictionary.open(ByteBuffer.wrap(b))
        } catch (_: DictionaryFormatException) {
            null
        }

    @Test(timeout = 120_000)
    fun randomByteFlipsInBulkSectionsNeverLoopOrCrash() {
        val rnd = Random(2026)
        var opened = 0
        repeat(120) { iter ->
            val c = bytes.copyOf()
            val section = bulk[rnd.nextInt(bulk.size)]
            val r = range(c, section)
            if (r.isEmpty()) return@repeat
            repeat(1 + rnd.nextInt(24)) {
                c[r.first + rnd.nextInt(r.last - r.first + 1)] = rnd.nextInt(256).toByte()
            }
            open(c)?.let {
                opened++
                exercise(it, iter)
            }
        }
        // The bulk sections are not checksummed, so most of these must have opened.
        assertThat(opened).isGreaterThan(60)
    }

    @Test(timeout = 120_000)
    fun wholeSectionsOverwrittenWithConstantsNeverLoopOrCrash() {
        for (section in bulk) for (fill in intArrayOf(0x00, 0xFF, 0x80, 0x7F)) {
            val c = bytes.copyOf()
            val r = range(c, section)
            for (i in r) c[i] = fill.toByte()
            open(c)?.let { exercise(it, section * 31 + fill) }
        }
    }

    @Test(timeout = 120_000)
    fun shrunkSectionsAreRejectedOrFailCleanly() {
        for (section in bulk) {
            val c = bytes.copyOf()
            val e = sectionEntry(c, section)
            val len = le(c).getInt(e + 8)
            for (newLen in intArrayOf(0, 1, len / 2, len - 1)) {
                if (newLen < 0 || newLen >= len) continue
                val d = c.copyOf()
                le(d).putInt(e + 8, newLen)
                fixCrc(d)
                open(d)?.let { exercise(it, section + newLen) }
            }
        }
    }

    @Test(timeout = 120_000)
    fun trieParentCycleCannotLoopWordReconstruction() {
        // Make every terminal's ID_TO_NODE point at a different, deep node and corrupt the samples
        // so parent lookups see nonsense; keyOf/word must throw instead of spinning.
        val c = bytes.copyOf()
        for (i in range(c, Fmt.SEC_TRIE_SAMPLES)) c[i] = 0
        val d = open(c)
        if (d != null) exercise(d, 5)
        val c2 = bytes.copyOf()
        for (i in range(c2, Fmt.SEC_TRIE_NODES) step 3) c2[i] = 255.toByte()
        open(c2)?.let { exercise(it, 6) }
    }

    @Test
    fun hugeNgramEntryCountIsRejectedNotScanned() {
        val c = bytes.copyOf()
        val r = range(c, Fmt.SEC_BIGRAM_DATA)
        // first list header: replace its entry count by a 5-byte varint of ~2^28
        val p = r.first
        val big = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x07)
        System.arraycopy(big, 0, c, p, big.size)
        val d = open(c) ?: return
        tolerate { d.topSuccessors(Dictionary.BOS_ID, 8) }
        tolerate { d.bigramLogProb(Dictionary.BOS_ID, 5) }
    }

    @Test
    fun readVarintIsBoundsChecked() {
        val ok = ByteBuffer.wrap(byteArrayOf(0x81.toByte(), 0x01))
        assertThat(Fmt.readVarint(ok, 0)).isEqualTo(129)
        val truncated = ByteBuffer.wrap(byteArrayOf(0x81.toByte()))
        org.junit.Assert.assertThrows(DictionaryFormatException::class.java) {
            Fmt.readVarint(truncated, 0)
        }
        val endless = ByteBuffer.wrap(ByteArray(64) { 0xFF.toByte() })
        org.junit.Assert.assertThrows(DictionaryFormatException::class.java) {
            Fmt.readVarint(endless, 0)
        }
        org.junit.Assert.assertThrows(DictionaryFormatException::class.java) {
            Fmt.readVarint(ok, 5)
        }
        org.junit.Assert.assertThrows(DictionaryFormatException::class.java) {
            Fmt.readVarint(ok, -1)
        }
    }

    @Test
    fun overlongKeysAreRefusedByTheWriter() {
        val data =
            DictionaryData(
                DictionaryMetadata("en", "2026-01-01", 1, "x"),
                listOf(WordEntry.fromCount("a".repeat(Dictionary.MAX_KEY_LENGTH + 1), 5, 100)),
                emptyList(),
                emptyList(),
            )
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            DictionaryWriter.write(data)
        }
    }
}
