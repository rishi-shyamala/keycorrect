package dev.keycorrect.core.dictionary

import com.google.common.truth.Truth.assertThat
import java.util.Random
import org.junit.Test

class TrieTest {
    private val data = Fixtures.smallData
    private val dict = Fixtures.small
    private val expectedKeys: Map<String, List<String>> =
        data.words.groupBy({ it.surface.lowercase() }, { it.surface })

    /** Depth-first traversal using only the allocation-free index API. */
    private fun collect(): Map<String, IntArray> {
        val found = HashMap<String, IntArray>()
        val sb = StringBuilder()
        fun dfs(node: Int) {
            if (dict.isTerminal(node)) found[sb.toString()] = dict.wordIdsAt(node)
            val fc = dict.firstChild(node)
            var prevChar = Char.MIN_VALUE
            for (i in 0 until dict.childCount(node)) {
                val c = fc + i
                val ch = dict.nodeChar(c)
                assertThat(ch).isGreaterThan(prevChar) // strictly ascending
                prevChar = ch
                assertThat(dict.child(node, ch)).isEqualTo(c)
                assertThat(dict.childAt(node, i)).isEqualTo(c)
                sb.append(ch)
                dfs(c)
                sb.setLength(sb.length - 1)
            }
        }
        dfs(dict.root)
        return found
    }

    @Test
    fun traversalVisitsExactlyTheInsertedKeys() {
        val found = collect()
        assertThat(found.keys).isEqualTo(expectedKeys.keys)
        assertThat(found.size).isEqualTo(dict.keyCount)
        for ((key, ids) in found) {
            val surfaces = ids.map { dict.word(it) }
            assertThat(surfaces).containsExactlyElementsIn(expectedKeys.getValue(key))
            assertThat(ids.toList()).isInOrder()
            assertThat(dict.wordIdCountAt(dict.findNode(key))).isEqualTo(ids.size)
            assertThat(dict.firstWordIdAt(dict.findNode(key))).isEqualTo(ids[0])
        }
        // Every word id is reachable exactly once.
        val allIds = found.values.flatMap { it.toList() }
        assertThat(allIds).containsNoDuplicates()
        assertThat(allIds).hasSize(data.words.size)
    }

    @Test
    fun findNodeAndChildMisses() {
        assertThat(dict.findNode("")).isEqualTo(dict.root)
        assertThat(dict.findNode("zzzzzzzzzz")).isEqualTo(-1)
        assertThat(dict.findNode("the")).isAtLeast(1)
        assertThat(dict.isTerminal(dict.findNode("the"))).isTrue()
        assertThat(dict.isTerminal(dict.root)).isFalse()
        assertThat(dict.wordIdCountAt(dict.root)).isEqualTo(0)
        assertThat(dict.firstWordIdAt(dict.root)).isEqualTo(-1)
        assertThat(dict.child(dict.root, '中')).isEqualTo(-1) // outside the alphabet
        assertThat(dict.child(dict.root, '#')).isEqualTo(-1)
        // a long key that is not a key but whose prefix is
        assertThat(dict.findNode("thex")).isEqualTo(-1)
        // leaf has no children
        val leafish = expectedKeys.keys.first { dict.childCount(dict.findNode(it)) == 0 }
        assertThat(dict.child(dict.findNode(leafish), 'a')).isEqualTo(-1)
    }

    @Test
    fun maxLogProbIsExactSubtreeMaximum() {
        // Post-order: the max over a subtree computed from terminal ids must equal the stored
        // bound.
        fun dfs(node: Int): Float {
            var best = Float.NEGATIVE_INFINITY
            for (id in dict.wordIdsAt(node)) best = maxOf(best, dict.logProb(id))
            val fc = dict.firstChild(node)
            for (i in 0 until dict.childCount(node)) best = maxOf(best, dfs(fc + i))
            assertThat(dict.maxLogProbInSubtree(node)).isEqualTo(best)
            return best
        }
        dfs(dict.root)
    }

    @Test
    fun prefixBoundIsTrueUpperBoundForCompletions() {
        val rnd = Random(5)
        val keys = expectedKeys.keys.toList()
        repeat(400) {
            val key = keys[rnd.nextInt(keys.size)]
            val prefix = key.substring(0, 1 + rnd.nextInt(key.length))
            val node = dict.findNode(prefix)
            assertThat(node).isAtLeast(0)
            val bound = dict.maxLogProbInSubtree(node)
            var best = Float.NEGATIVE_INFINITY
            for (k in keys) {
                if (k.startsWith(prefix)) {
                    for (id in dict.idsForKey(k)) {
                        assertThat(dict.logProb(id)).isAtMost(bound)
                        best = maxOf(best, dict.logProb(id))
                    }
                }
            }
            assertThat(bound).isEqualTo(best)
        }
    }

    @Test
    fun parentWalkReconstructsKeys() {
        for (k in expectedKeys.keys.take(2000)) {
            val id = dict.idsForKey(k)[0]
            assertThat(dict.keyOf(id)).isEqualTo(k)
        }
    }
}
