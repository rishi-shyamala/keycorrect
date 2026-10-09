package dev.keycorrect.core.dictionary

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The hardened reader must accept the compiled dictionary that ships in the app. */
class ShippedAssetTest {
    private val file = File("../../app/src/main/assets/dictionaries/en_US.kcd")

    @Test(timeout = 120_000)
    fun shippedDictionaryOpensAndTraversesCleanly() {
        assumeTrue("asset not present", file.isFile)
        val d = Dictionary.open(ByteBuffer.wrap(file.readBytes()))
        assertThat(d.wordCount).isGreaterThan(100_000)

        // Lookups.
        for (w in listOf("the", "hello", "don't", "I'm", "you're")) {
            assertThat(d.wordId(w)).isAtLeast(Dictionary.FIRST_WORD_ID)
        }
        val the = d.wordId("the")
        assertThat(d.word(the)).isEqualTo("the")
        assertThat(d.keyOf(the)).isEqualTo("the")
        assertThat(d.topSuccessors(Dictionary.BOS_ID, 8)).isNotEmpty()
        assertThat(d.topSuccessors(the, 8)).isNotEmpty()
        assertThat(d.bigramLogProb(Dictionary.BOS_ID, the)).isNotNull()

        // Every word id reconstructs to a key found at its own terminal node.
        for (id in Dictionary.FIRST_WORD_ID until d.wordCount) {
            val key = d.keyOf(id)!!
            assertThat(key.length).isAtMost(Dictionary.MAX_KEY_LENGTH)
            assertThat(d.word(id).lowercase()).isEqualTo(key)
            d.flags(id)
            d.logProb(id)
        }

        // Full trie traversal: every node's handles and terminal data are valid.
        var nodes = 0
        var terminals = 0
        val stack = ArrayDeque<Int>()
        stack.addLast(d.root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            nodes++
            d.maxLogProbInSubtree(node)
            if (d.isTerminal(node)) {
                terminals++
                assertThat(d.wordIdsAt(node)).isNotEmpty()
            }
            val count = d.childCount(node)
            if (count > 0) {
                val first = d.firstChild(node)
                for (j in 0 until count) {
                    d.nodeChar(first + j)
                    stack.addLast(first + j)
                }
            }
        }
        assertThat(terminals).isEqualTo(d.keyCount)
        assertThat(nodes).isGreaterThan(d.keyCount)

        // N-gram lists for a sample of words.
        var withLists = 0
        for (id in 0 until minOf(d.wordCount, 5_000)) {
            if (d.topSuccessors(id, 5).isNotEmpty()) withLists++
            d.topSuccessors(id, the, 5)
        }
        assertThat(withLists).isGreaterThan(1_000)
    }

    /**
     * Walks EVERY bigram and trigram list of the real asset through the hardened table: header
     * validation, the full block scan in `top`, and `lookup` (skip table included) for present and
     * absent ids. A [DictionaryFormatException] here is a false rejection of valid data.
     */
    @Test(timeout = 300_000)
    fun everyShippedNgramListIsReadableAndConsistent() {
        assumeTrue("asset not present", file.isFile)
        val d = Dictionary.open(ByteBuffer.wrap(file.readBytes()))
        val ids = IntArray(8)
        val lps = FloatArray(8)
        for ((name, table) in listOf("bigram" to d.bigramTable, "trigram" to d.trigramTable)) {
            var lists = 0
            var entries = 0L
            var largest = 0
            for (ordinal in 0 until table.listCount) {
                val loc = table.locate(ordinal)
                if (loc < 0) continue
                val pos = table.positionOf(loc)
                val n = table.entryCount(pos)
                assertThat(n).isGreaterThan(0)
                lists++
                entries += n
                if (n > largest) largest = n
                val got = table.top(pos, ids.size, ids, lps)
                assertThat(got).isAtMost(ids.size)
                for (i in 0 until got) {
                    assertThat(table.lookup(pos, ids[i])).isAtLeast(0L)
                    assertThat(lps[i]).isFinite()
                }
                // Absent ids at both ends of the id range exercise the skip-table early exits.
                table.lookup(pos, 0)
                table.lookup(pos, d.wordCount + 1)
            }
            assertThat(lists).isGreaterThan(1_000)
            assertThat(entries).isAtLeast(if (name == "bigram") d.bigramCount.toLong() else 1L)
            assertThat(largest).isGreaterThan(0)
        }

        // The public paths over every word and every successor pair.
        val buf = IntArray(8)
        val lp = FloatArray(8)
        for (w1 in 0 until d.wordCount) {
            val n = d.topSuccessors(w1, 8, buf, lp)
            for (i in 0 until n) {
                d.topSuccessors(w1, buf[i], 8, IntArray(8), FloatArray(8))
                d.bigramLogProbOrNaN(w1, buf[i])
            }
        }
    }
}
