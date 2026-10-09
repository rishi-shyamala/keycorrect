package dev.keycorrect.core.dictionary

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.util.Random
import org.junit.Test

/** Coarse smoke-level performance checks; bounds are deliberately generous to avoid flakiness. */
class PerformanceTest {
    @Test
    fun lookupsTraversalAndOpenAreFast() {
        val data =
            SyntheticData.make(seed = 11, words = 50_000, bigrams = 200_000, trigrams = 80_000)
        val bytes = DictionaryWriter.write(data)
        val buffer = ByteBuffer.wrap(bytes)

        val tOpen0 = System.nanoTime()
        val dict = Dictionary.open(buffer)
        val openMs = (System.nanoTime() - tOpen0) / 1e6

        val surfaces = data.words.map { it.surface }
        val rnd = Random(1)
        // warm up
        repeat(20_000) { dict.wordId(surfaces[rnd.nextInt(surfaces.size)]) }

        val t0 = System.nanoTime()
        var hits = 0
        repeat(100_000) { if (dict.wordId(surfaces[rnd.nextInt(surfaces.size)]) >= 0) hits++ }
        val lookupMs = (System.nanoTime() - t0) / 1e6
        assertThat(hits).isEqualTo(100_000)

        val t1 = System.nanoTime()
        val nodes = traverse(dict, dict.root)
        val traverseMs = (System.nanoTime() - t1) / 1e6

        val t2 = System.nanoTime()
        var found = 0
        val pairs = data.bigrams.take(100_000)
        for (b in pairs) {
            if (!dict.bigramLogProbOrNaN(dict.idOfToken(b.w1), dict.idOfToken(b.w2)).isNaN())
                found++
        }
        val bigramMs = (System.nanoTime() - t2) / 1e6
        assertThat(found).isEqualTo(pairs.size)

        println(
            "PERF words=50k bytes=${bytes.size} open=%.2fms wordId x100k=%.1fms traversal(%d nodes)=%.1fms bigram x%d(+id lookups)=%.1fms"
                .format(openMs, lookupMs, nodes, traverseMs, pairs.size, bigramMs)
        )
        assertThat(openMs).isLessThan(200.0)
        assertThat(lookupMs).isLessThan(5_000.0)
        assertThat(traverseMs).isLessThan(5_000.0)
        assertThat(bigramMs).isLessThan(10_000.0)
    }

    private fun traverse(dict: Dictionary, node: Int): Int {
        var n = 1
        val fc = dict.firstChild(node)
        for (i in 0 until dict.childCount(node)) n += traverse(dict, fc + i)
        return n
    }
}
