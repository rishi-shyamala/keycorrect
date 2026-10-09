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
}
