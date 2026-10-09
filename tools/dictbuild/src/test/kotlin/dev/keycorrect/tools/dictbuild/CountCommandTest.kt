package dev.keycorrect.tools.dictbuild

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** End-to-end run of `count` on tiny fixtures (no network, no real dumps). */
class CountCommandTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun gz(text: String): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(text.toByteArray()) }
        return bos.toByteArray()
    }

    @Test
    fun endToEnd() {
        val dir = tmp.newFolder()
        val vocab = dir.resolve("vocab.combined.gz")
        vocab.writeBytes(
            gz(
                """
                dictionary=main:en_us,locale=en_US,description=Test,date=1,version=1
                 word=the,f=200,flags=,originalFreq=200
                 word=cat,f=150,flags=,originalFreq=150
                 word=sat,f=140,flags=,originalFreq=140
                 word=mat,f=130,flags=,originalFreq=130
                  shortcut=m,f=whitelist
                """
                    .trimIndent()
            )
        )
        val wiki = dir.resolve("wiki.xml")
        wiki.writeText(
            """
            <mediawiki><page><title>Cat</title><ns>0</ns><id>1</id><revision>
            <text>The cat sat on the mat.{{x}} The cat sat.</text></revision></page>
            <page><title>R</title><ns>0</ns><id>2</id><redirect title="Cat"/><revision><text>#REDIRECT [[Cat]]</text></revision></page>
            <page><title>User:X</title><ns>2</ns><id>3</id><revision><text>The cat sat.</text></revision></page>
            </mediawiki>
            """
                .trimIndent()
        )
        // 40 sentences with distinct ids so that some are held out at 25%.
        val tsv =
            (1..40).joinToString("\n") { "$it\teng\tThe cat sat on the mat $it." } +
                "\n7\tdeu\tDie Katze\n"
        val tat = dir.resolve("tat.tsv")
        tat.writeText(tsv)
        val out = dir.resolve("out")

        val stats =
            CountCommand.execute(
                CountOptions(
                    vocab = vocab.toPath(),
                    wiki = wiki.toPath(),
                    tatoeba = tat.toPath(),
                    out = out.toPath(),
                    heldoutPercent = 25,
                    minBigramCount = 1,
                    minTrigramCount = 1,
                )
            )

        val heldout = Files.readAllLines(out.resolve("heldout.txt").toPath())
        val expectedHeld = (1..40).filter { Tatoeba.isHeldOut(it.toLong(), 25) }
        assertThat(expectedHeld).isNotEmpty()
        assertThat(heldout)
            .containsExactlyElementsIn(expectedHeld.map { "The cat sat on the mat $it." })

        val uni =
            Files.readAllLines(out.resolve("unigrams.tsv").toPath()).associate {
                it.split('\t').let { p -> p[0] to p }
            }
        val trainTat = 40 - expectedHeld.size
        // wiki: "the" x4 (2 sentences: 2 + 1 ... see below); tatoeba: 2 per sentence
        assertThat(uni.getValue("cat")[2]).isEqualTo("2") // wiki counts: article has 2 "cat"
        assertThat(uni.getValue("cat")[3]).isEqualTo("$trainTat")
        assertThat(uni.getValue("cat")[1]).isEqualTo("${2 + 3 * trainTat}") // weight 3.0
        assertThat(uni.getValue("the")[3]).isEqualTo("${2 * trainTat}")

        val bigrams =
            Files.readAllLines(out.resolve("bigrams.tsv").toPath()).drop(1).map { it.split('\t') }
        val theCat = bigrams.first { it[0] == "the" && it[1] == "cat" }
        assertThat(theCat[3]).isEqualTo("2")
        assertThat(theCat[4]).isEqualTo("$trainTat")
        // "on" is out of this vocabulary, so nothing spans it
        assertThat(bigrams.none { it[0] == "sat" && it[1] == "the" }).isTrue()
        val trigrams =
            Files.readAllLines(out.resolve("trigrams.tsv").toPath()).drop(1).map { it.split('\t') }
        assertThat(trigrams.any { it[0] == "<s>" && it[1] == "the" && it[2] == "cat" }).isTrue()

        val vocabTsv = Files.readAllLines(out.resolve("vocab.tsv").toPath())
        assertThat(vocabTsv).hasSize(5)
        assertThat(vocabTsv[1]).isEqualTo("the\t200\t200\t")

        @Suppress("UNCHECKED_CAST") val sources = stats["sources"] as Map<String, Map<String, Any?>>
        assertThat(sources.getValue("wikipedia")["articlesCounted"]).isEqualTo(1L)
        assertThat(sources.getValue("wikipedia")["redirectsSkipped"]).isEqualTo(1L)
        assertThat(sources.getValue("wikipedia")["otherNamespacePagesSkipped"]).isEqualTo(1L)
        assertThat(sources.getValue("tatoeba")["sentencesRead"]).isEqualTo(40L)
        assertThat(Files.readString(out.resolve("stats.json").toPath())).contains("\"oovRate\"")
    }

    @Test
    fun maxSuccessorsKeepsTheMostFrequent() {
        val dir = tmp.newFolder()
        val vocab = dir.resolve("v.combined")
        vocab.writeText(
            "dictionary=main:en\n word=a,f=10\n word=b,f=10\n word=c,f=10\n word=d,f=10\n"
        )
        val tat = dir.resolve("t.tsv")
        tat.writeText(
            listOf("a b", "a b", "a b", "a c", "a c", "a d").withIndex().joinToString("\n") {
                "${it.index + 1000}\teng\t${it.value}"
            }
        )
        val out = dir.resolve("out")
        CountCommand.execute(
            CountOptions(
                vocab = vocab.toPath(),
                skipWiki = true,
                tatoeba = tat.toPath(),
                out = out.toPath(),
                heldoutPercent = 0,
                minBigramCount = 1,
                maxBigramSuccessors = 2,
                tatoebaWeight = 1.0,
            )
        )
        val rows =
            Files.readAllLines(out.resolve("bigrams.tsv").toPath()).drop(1).map { it.split('\t') }
        val afterA = rows.filter { it[0] == "a" }
        assertThat(afterA.map { it[1] }).containsExactly("b", "c").inOrder()
        assertThat(afterA.map { it[2] }).containsExactly("3", "2").inOrder()
    }

    @Test
    fun optionParsing() {
        val o =
            CountOptions.parse(
                listOf("--tatoeba-weight", "2.5", "--wiki=none", "--max-trigram-successors", "4")
            )
        assertThat(o.tatoebaWeight).isEqualTo(2.5)
        assertThat(o.skipWiki).isTrue()
        assertThat(o.maxTrigramSuccessors).isEqualTo(4)
        assertThat(CountOptions().tatoebaWeight).isEqualTo(3.0)
    }
}
