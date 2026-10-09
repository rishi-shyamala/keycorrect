package dev.keycorrect.tools.dictbuild

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.dictionary.Dictionary
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.GZIPOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Estimator and `compile` on a tiny corpus (counts are produced by the real `count`). */
class CompileTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun gz(text: String): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(text.toByteArray()) }
        return bos.toByteArray()
    }

    private fun counted(): File {
        val dir = tmp.newFolder()
        val vocab = dir.resolve("vocab.combined.gz")
        vocab.writeBytes(
            gz(
                """
                dictionary=main:en_us,locale=en_US,description=Test,date=1,version=1
                 word=how,f=200,flags=,originalFreq=200
                 word=are,f=190,flags=,originalFreq=190
                 word=you,f=180,flags=,originalFreq=180
                 word=thank,f=120,flags=,originalFreq=120
                 word=fine,f=100,flags=,originalFreq=100
                 word=rare,f=20,flags=,originalFreq=20
                 word=never,f=5,flags=,originalFreq=5
                """
                    .trimIndent()
            )
        )
        val sentences =
            listOf(
                "how are you",
                "how are you",
                "how are you",
                "how are they",
                "how are you fine",
                "thank you",
                "thank you",
                "thank you fine",
                "you are fine",
                "you are rare",
                "fine thank you",
                "are you fine",
            )
        val tat = dir.resolve("tat.tsv")
        tat.writeText(
            sentences.withIndex().joinToString("\n") { "${it.index + 1}\teng\t${it.value}" }
        )
        val wiki = dir.resolve("wiki.xml")
        wiki.writeText(
            "<mediawiki><page><title>A</title><ns>0</ns><id>1</id><revision><text>" +
                "How are you. Thank you fine. You are rare. How are you fine. How are they." +
                "</text></revision></page></mediawiki>"
        )
        val out = dir.resolve("out")
        CountCommand.execute(
            CountOptions(
                vocab = vocab.toPath(),
                wiki = wiki.toPath(),
                tatoeba = tat.toPath(),
                out = out.toPath(),
                heldoutPercent = 0,
                minBigramCount = 1,
                minTrigramCount = 1,
            )
        )
        return out
    }

    @Test
    fun conditionalProbabilitiesSumToOneBeforePruning() {
        val lm = LmEstimator(LmTables.load(counted().toPath()))
        val n = lm.tables.vocabSize
        var contexts = 0
        for (v in 0..n) {
            if ((1..n).none { lm.bigramRow(v, it) >= 0 }) continue
            contexts++
            val sum = (1..n).sumOf { lm.p2(v, it) }
            assertThat(sum).isWithin(1e-4).of(1.0)
            for (w in 1..n) {
                if (lm.bigramRow(v, w) < 0) continue
                val s3 = (1..n).sumOf { lm.p3(v, w, it) }
                assertThat(s3).isWithin(1e-4).of(1.0)
            }
        }
        assertThat(contexts).isGreaterThan(2)
    }

    @Test
    fun discountingMovesMassToTheBackoff() {
        val lm = LmEstimator(LmTables.load(counted().toPath()))
        // Stored n-gram mass is discounted by D > 0 ...
        assertThat(lm.d2).isGreaterThan(0.0)
        assertThat(lm.d2).isLessThan(1.0)
        assertThat(lm.discounted(4, 0, lm.d2)).isLessThan(4.0)
        // ... and a seen bigram is still more likely than an unseen pair with the same last word.
        val n = lm.tables.vocabSize
        val words = lm.tables.words
        fun id(w: String) = words.indexOf(w)
        val how = id("how")
        val seen = lm.p2(how, id("are"))
        val unseen = lm.p2(how, id("fine"))
        assertThat(n).isAtLeast(7)
        assertThat(seen).isGreaterThan(unseen)
        assertThat(unseen).isGreaterThan(0.0)
    }

    @Test
    fun unigramFitIsMonotone() {
        val levels = intArrayOf(10, 50, 100, 150, 200, 250)
        val lnp = doubleArrayOf(-9.0, -9.5, -7.0, -7.5, -5.0, -4.0) // noisy
        val curve = UnigramFit.fit(levels, lnp, levels.size)
        for (i in 1 until curve.size) assertThat(curve[i]).isAtLeast(curve[i - 1] - 1e-9)
    }

    @Test
    fun compiledDictionaryOpens() {
        val dir = counted()
        val outFile = tmp.newFolder().resolve("en_US.kcd")
        val bytes =
            CompileCommand.execute(
                CompileOptions(
                    input = dir.toPath(),
                    out = outFile.toPath(),
                    prune =
                        PruneParams(
                            minBigramCount = 1,
                            minTrigramCount = 1,
                            bigramGain = 0.0,
                            trigramGain = 0.0,
                        ),
                    buildDate = "2026-10-09",
                )
            )
        assertThat(outFile.length()).isEqualTo(bytes.size.toLong())
        val dict = Dictionary.open(ByteBuffer.wrap(outFile.readBytes()))
        assertThat(dict.metadata.languageTag).isEqualTo("en-US")
        assertThat(dict.metadata.buildDate).isEqualTo("2026-10-09")
        assertThat(dict.metadata.attribution).contains("Tatoeba")
        // vocabulary = every AOSP word, including the one never seen in the corpora
        for (w in listOf("how", "are", "you", "thank", "fine", "rare", "never")) {
            assertThat(dict.wordId(w)).isAtLeast(Dictionary.FIRST_WORD_ID)
        }
        assertThat(dict.logProb(dict.wordId("never"))).isLessThan(dict.logProb(dict.wordId("you")))
        val ids = IntArray(5)
        val lp = FloatArray(5)
        val n = dict.topSuccessors(dict.wordId("how"), 5, ids, lp)
        assertThat(n).isAtLeast(1)
        assertThat(dict.word(ids[0])).isEqualTo("are")
        val start = dict.topSuccessors(Dictionary.BOS_ID, 5, ids, lp)
        assertThat(start).isAtLeast(1)
        assertThat(dict.word(ids[0])).isEqualTo("how")
    }

    @Test
    fun optionsParse() {
        val o =
            CompileOptions.parse(
                listOf("--tatoeba-weight", "2", "--bigram-gain=5", "--max-size-mib", "1")
            )
        assertThat(o.lm.tatoebaWeight).isEqualTo(2.0)
        assertThat(o.prune.bigramGain).isEqualTo(5.0)
        assertThat(o.maxBytes).isEqualTo(1048576L)
        assertThat(EvalOptions.parse(listOf("--limit", "5")).limit).isEqualTo(5)
    }
}
