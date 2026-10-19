package dev.keycorrect.core.llama

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.RandomAccessFile
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Runs the real library on a real model. Skipped unless `KC_LLAMA_LIB` points at the host build
 * (`tools/llama/build-host.sh`) and the SmolLM2 GGUF is on disk (`-Dkc.llama.model` or the default
 * path below).
 */
class LlamaIntegrationTest {
    @Test
    fun plausibleWordOutscoresNonsense() {
        model.newSession().use { session ->
            val scores = session.score(CAT, listOf("mat", "quixotic"), TIMEOUT_MS)!!
            assertThat(scores).hasLength(2)
            assertThat(scores[0]).isGreaterThan(scores[1])
            assertThat(scores[0]).isLessThan(0f)
        }
    }

    @Test
    fun anExtendedContextReusesTheCacheAndAgreesWithAFreshOne() {
        model.newSession().use { warm ->
            model.newSession().use { fresh ->
                val cands = CANDIDATES
                val coldMs = timed { warm.score(LONG_CONTEXT, cands, TIMEOUT_MS) }
                val extended = "$LONG_CONTEXT and then"
                val warmMs = bestOf(3) { warm.score(extended, cands, TIMEOUT_MS) }
                val warmScores = warm.score(extended, cands, TIMEOUT_MS)!!
                val freshScores = fresh.score(extended, cands, TIMEOUT_MS)!!
                assertThat(warmScores).usingTolerance(1e-3).containsExactly(freshScores)
                println("score cold=%.1f ms warm=%.1f ms".format(coldMs, warmMs))
                assertThat(warmMs).isLessThan(coldMs)
            }
        }
    }

    @Test
    fun scoresDoNotDependOnBatchingOrOrder() {
        model.newSession().use { session ->
            val many = (1..20).map { WORDS[it % WORDS.size] + it }
            val all = session.score(CAT, many, TIMEOUT_MS)!!
            val single = many.map { session.score(CAT, listOf(it), TIMEOUT_MS)!![0] }
            assertThat(all.toList()).hasSize(20)
            for (i in many.indices) assertThat(all[i]).isWithin(1e-2f).of(single[i])
        }
    }

    @Test
    fun zeroTimeoutReturnsNullAndTheSessionRecovers() {
        model.newSession().use { session ->
            assertThat(session.score(CAT, CANDIDATES, 0)).isNull()
            assertThat(session.generate(CAT, 4, 0)).isNull()
            val scores = session.score(CAT, listOf("mat", "quixotic"), TIMEOUT_MS)!!
            assertThat(scores[0]).isGreaterThan(scores[1])
        }
    }

    @Test
    fun aTimeoutInsideDecodingDoesNotPoisonTheCache() {
        model.newSession().use { session ->
            val expected = session.score(LONG_CONTEXT, CANDIDATES, TIMEOUT_MS)!!
            // 1 ms is shorter than a cold decode of a long context: usually null, never wrong.
            session.score("$LONG_CONTEXT again and again", CANDIDATES, 1)
            val after = session.score(LONG_CONTEXT, CANDIDATES, TIMEOUT_MS)!!
            assertThat(after).usingTolerance(1e-3).containsExactly(expected)
        }
    }

    @Test
    fun generateReturnsTextAndLeavesTheScoreCacheValid() {
        model.newSession().use { session ->
            val before = session.score(CAT, CANDIDATES, TIMEOUT_MS)!!
            val text = session.generate(CAT, 6, TIMEOUT_MS)
            assertThat(text).isNotNull()
            assertThat(text!!.trim()).isNotEmpty()
            assertThat(text).doesNotContain("\n")
            println("generate: ${text.length} chars")
            val after = session.score(CAT, CANDIDATES, TIMEOUT_MS)!!
            assertThat(after).usingTolerance(1e-3).containsExactly(before)
        }
    }

    @Test
    fun emptyContextAndUnusualTextAreHandled() {
        model.newSession().use { session ->
            val scores = session.score("", listOf("the", "zzzzqx"), TIMEOUT_MS)!!
            assertThat(scores[0]).isGreaterThan(scores[1])
            val odd = session.score("emoji 😀 café", listOf("😀", "naïve", ""), TIMEOUT_MS)
            assertThat(odd).isNotNull()
            assertThat(odd!!.all { it.isFinite() }).isTrue()
            // Lone surrogates must not crash the native side.
            assertThat(session.score("bad \uD83D", listOf("x"), TIMEOUT_MS)).isNotNull()
        }
    }

    @Test
    fun aContextBeyondTheCapStillScores() {
        model.newSession(nCtx = 128).use { session ->
            val long = (1..400).joinToString(" ") { WORDS[it % WORDS.size] }
            val first = session.score(long, CANDIDATES, TIMEOUT_MS)
            val second = session.score("$long more", CANDIDATES, TIMEOUT_MS)
            assertThat(first).isNotNull()
            assertThat(second).isNotNull()
            assertThat(session.generate(long, 20, TIMEOUT_MS)).isNotNull()
        }
    }

    /**
     * Prints the per-call latency of the keyboard's case; no assertions (numbers go in PERF.md).
     */
    @Test
    fun reportLatency() {
        val text =
            "yesterday we walked along the river to the old market where the baker sells fresh " +
                "bread every morning and the cat sat on the"
        val words = text.split(" ")
        val rounds = 40
        model.newSession(nThreads = 4).use { session ->
            repeat(5) { session.score("$text $it", CANDIDATES, TIMEOUT_MS) }
            // Cold: a different context each call, nothing in common with the previous one.
            val cold =
                (1..rounds).map { i ->
                    val ctx = "w$i " + words.take(19).joinToString(" ")
                    timed { session.score(ctx, CANDIDATES, TIMEOUT_MS) }
                }
            // Warm: typing on, one more word each time (the previous context is a prefix). Each
            // round starts at 12 words and grows to 20, so the contexts match the cold ones.
            val warm = mutableListOf<Double>()
            repeat(5) { round ->
                session.score("r$round " + words.take(11).joinToString(" "), CANDIDATES, TIMEOUT_MS)
                for (n in 12..20) {
                    val ctx = "r$round " + words.take(n - 1).joinToString(" ")
                    warm += timed { session.score(ctx, CANDIDATES, TIMEOUT_MS) }
                }
            }
            // The same context again, e.g. a re-rank after the word is replaced: one token decoded.
            val same = (1..rounds).map { timed { session.score(text, CANDIDATES, TIMEOUT_MS) } }
            val gen = (1..10).map { timed { session.generate(text, 12, TIMEOUT_MS) } }
            println("latency cold:  " + stats(cold))
            println("latency warm:  " + stats(warm))
            println("latency same:  " + stats(same))
            println("latency generate: " + stats(gen))
        }
    }

    @Test
    fun describeNamesTheModel() {
        assertThat(model.description).contains("vocab")
    }

    @Test
    fun openFdReadsAModelAtAnyOffset() {
        // 64 is mapped, 70 is read into memory: both must give the same answers.
        for (offset in listOf(0, 64, 70)) {
            val file = File.createTempFile("kc-llama", ".bin")
            try {
                file.outputStream().use { out ->
                    out.write(ByteArray(offset))
                    File(modelPath).inputStream().use { it.copyTo(out) }
                }
                RandomAccessFile(file, "r").use { raf ->
                    LlamaModel.openFd(fdNumber(file), offset.toLong()).use { fdModel ->
                        fdModel.newSession().use { session ->
                            val scores = session.score(CAT, listOf("mat", "quixotic"), TIMEOUT_MS)!!
                            assertThat(scores[0]).isGreaterThan(scores[1])
                        }
                    }
                    assertThat(raf.fd.valid()).isTrue()
                }
            } finally {
                file.delete()
            }
        }
    }

    /** The descriptor number of the open [file] in this process (read from /proc). */
    private fun fdNumber(file: File): Int =
        File("/proc/self/fd")
            .listFiles()!!
            .first { runCatching { it.canonicalPath == file.canonicalPath }.getOrDefault(false) }
            .name
            .toInt()

    companion object {
        private const val CAT = "the cat sat on the"
        private const val TIMEOUT_MS = 30_000L
        private val CANDIDATES =
            listOf("mat", "floor", "table", "roof", "dog", "quixotic", "chair", "bed")
        private val WORDS = listOf("apple", "river", "light", "stone", "paper", "window", "garden")
        private const val LONG_CONTEXT =
            "yesterday we walked along the river to the old market where the baker sells fresh " +
                "bread every morning and the cat sat on the"
        private val modelPath =
            System.getProperty("kc.llama.model").orEmpty().ifEmpty {
                "/home/rishi/Projects/keycorrect/data/raw/neural/SmolLM2-135M-Q8_0.gguf"
            }
        private lateinit var model: LlamaModel

        @BeforeClass
        @JvmStatic
        fun open() {
            val lib = System.getProperty("kc.llama.lib").orEmpty()
            assumeTrue("KC_LLAMA_LIB not set", lib.isNotEmpty() && File(lib).isFile)
            assumeTrue("model missing: $modelPath", File(modelPath).isFile)
            LlamaLibrary.load(lib)
            model = LlamaModel.openFile(modelPath)
        }

        @AfterClass
        @JvmStatic
        fun close() {
            if (::model.isInitialized) model.close()
        }

        private fun timed(block: () -> Unit): Double {
            val start = System.nanoTime()
            block()
            return (System.nanoTime() - start) / 1e6
        }

        private fun stats(ms: List<Double>): String {
            val sorted = ms.sorted()
            fun q(f: Double) = sorted[((sorted.size - 1) * f).toInt()]
            return "p50=%.1f p95=%.1f max=%.1f ms".format(q(0.5), q(0.95), sorted.last())
        }

        private fun bestOf(n: Int, block: () -> Unit): Double = (1..n).minOf { timed(block) }
    }
}
