package dev.keycorrect.core.dictionary

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.util.Random
import org.junit.Test

/**
 * Size budget: a synthetic 200k-word / 1.5M-bigram / 500k-trigram dictionary must stay within 10 MB
 * and still open and answer queries quickly. Slow-ish (generation dominates, ~10 s).
 */
class LargeSyntheticSizeTest {
    @Test
    fun synthetic200kWords1_5MBigramsStaysWithin10MB() {
        val t0 = System.nanoTime()
        val data =
            SyntheticData.make(
                seed = 2026,
                words = 200_000,
                bigrams = 1_500_000,
                trigrams = 500_000,
            )
        val genMs = (System.nanoTime() - t0) / 1e6
        val t1 = System.nanoTime()
        val bytes = DictionaryWriter.write(data)
        val writeMs = (System.nanoTime() - t1) / 1e6

        val buffer = ByteBuffer.wrap(bytes)
        val t2 = System.nanoTime()
        val dict = Dictionary.open(buffer)
        val openMs = (System.nanoTime() - t2) / 1e6

        val mb = bytes.size / 1024.0 / 1024.0
        println(
            "SIZE words=${data.words.size} bigrams=${data.bigrams.size} trigrams=${data.trigrams.size} " +
                "bytes=${bytes.size} (%.2f MiB) gen=%.0fms write=%.0fms open=%.2fms keys=${dict.keyCount}"
                    .format(mb, genMs, writeMs, openMs)
        )
        println("SIZE sections: " + sectionSizes(bytes))
        assertThat(bytes.size).isAtMost(10 * 1024 * 1024)

        // spot-check correctness at scale
        val rnd = Random(5)
        repeat(20_000) {
            val w = data.words[rnd.nextInt(data.words.size)]
            val id = dict.wordId(w.surface)
            assertThat(id).isAtLeast(2)
            assertThat(dict.word(id)).isEqualTo(w.surface)
        }
        repeat(20_000) {
            val b = data.bigrams[rnd.nextInt(data.bigrams.size)]
            assertThat(dict.bigramLogProb(dict.idOfToken(b.w1), dict.idOfToken(b.w2))).isNotNull()
        }
        repeat(20_000) {
            val t = data.trigrams[rnd.nextInt(data.trigrams.size)]
            assertThat(
                    dict.trigramLogProb(
                        dict.idOfToken(t.w1),
                        dict.idOfToken(t.w2),
                        dict.idOfToken(t.w3),
                    )
                )
                .isNotNull()
        }
        assertThat(openMs).isLessThan(100.0)
    }

    private fun sectionSizes(bytes: ByteArray): String {
        val bb = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return (0 until bb.getInt(12)).joinToString(", ") {
            val e = Fmt.HEADER_SIZE + it * Fmt.SECTION_ENTRY_SIZE
            "#${bb.getInt(e)}=${bb.getInt(e + 8)}"
        }
    }
}
