package dev.keycorrect.core.dictionary

import com.google.common.truth.Truth.assertThat
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Test

class ConcurrencyTest {
    @Test
    fun manyThreadsReadOneInstanceConsistently() {
        val data = Fixtures.smallData
        val dict = Fixtures.small
        // Single-threaded expectations
        val surfaces = data.words.map { it.surface }
        val ids = IntArray(surfaces.size) { dict.wordId(surfaces[it]) }
        val bigrams = data.bigrams.take(5000)
        val expectedBigram = bigrams.map {
            dict.bigramLogProbOrNaN(dict.idOfToken(it.w1), dict.idOfToken(it.w2))
        }
        val expectedTop = (0 until 200).map { dict.topSuccessors(it, 5) }
        val expectedNodes = countNodes(dict)

        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val barrier = CyclicBarrier(threads)
        try {
            val futures =
                (0 until threads).map { t ->
                    pool.submit(
                        Callable {
                            barrier.await()
                            val rnd = Random(t.toLong())
                            var errors = 0
                            repeat(20000) {
                                val i = rnd.nextInt(surfaces.size)
                                if (dict.wordId(surfaces[i]) != ids[i]) errors++
                                if (dict.word(ids[i]) != surfaces[i]) errors++
                                val j = rnd.nextInt(bigrams.size)
                                val b = bigrams[j]
                                val lp =
                                    dict.bigramLogProbOrNaN(
                                        dict.idOfToken(b.w1),
                                        dict.idOfToken(b.w2),
                                    )
                                if (lp != expectedBigram[j]) errors++
                                val w = rnd.nextInt(expectedTop.size)
                                if (dict.topSuccessors(w, 5) != expectedTop[w]) errors++
                            }
                            if (countNodes(dict) != expectedNodes) errors++
                            errors
                        }
                    )
                }
            for (f in futures) assertThat(f.get(60, TimeUnit.SECONDS)).isEqualTo(0)
        } finally {
            pool.shutdownNow()
        }
    }

    /** Iterative DFS with explicit child cursors (no recursion, no allocation per node). */
    private fun countNodes(dict: Dictionary): Int {
        val stack = IntArray(256)
        val cursor = IntArray(256)
        var sp = 1
        var count = 1
        stack[0] = dict.root
        while (sp > 0) {
            val node = stack[sp - 1]
            val cur = cursor[sp - 1]
            if (cur < dict.childCount(node)) {
                cursor[sp - 1] = cur + 1
                stack[sp] = dict.childAt(node, cur)
                cursor[sp] = 0
                sp++
                count++
            } else {
                sp--
            }
        }
        return count
    }
}
