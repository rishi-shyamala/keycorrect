package dev.keycorrect.tools.dictbuild

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CorpusCountsTest {
    private val vocab =
        Vocabulary(
            listOf("the", "cat", "sat", "on", "mat", "I", "don't", "well", "known", "dog").map {
                VocabEntry(it, 100, 100, emptySet())
            } + VocabEntry("i", 0, 0, setOf(VocabEntry.NOT_A_WORD))
        )
    private val id = { w: String -> vocab.lookup(w, false) }

    private fun bigram(c: CorpusCounts, a: Int, b: Int, ch: Int) =
        c.bigrams.count((a.toLong() shl 21) or b.toLong(), ch)

    private fun trigram(c: CorpusCounts, a: Int, b: Int, d: Int, ch: Int) =
        c.trigrams.count((a.toLong() shl 42) or (b.toLong() shl 21) or d.toLong(), ch)

    @Test
    fun countsUnigramsBigramsTrigramsWithSentenceStart() {
        val c = CorpusCounts(vocab)
        c.addSentence("The cat sat on the mat.", CorpusCounts.WIKI)
        val s = Vocabulary.START
        assertThat(s).isEqualTo("<s>")
        assertThat(c.unigrams[0][id("the")]).isEqualTo(2) // sentence-initial "The" folds into "the"
        assertThat(c.unigrams[0][0]).isEqualTo(1)
        assertThat(bigram(c, 0, id("the"), 0)).isEqualTo(1) // <s> the
        assertThat(bigram(c, id("the"), id("cat"), 0)).isEqualTo(1)
        assertThat(bigram(c, id("on"), id("the"), 0)).isEqualTo(1)
        assertThat(trigram(c, 0, id("the"), id("cat"), 0)).isEqualTo(1) // <s> the cat
        assertThat(trigram(c, id("the"), id("cat"), id("sat"), 0)).isEqualTo(1)
        assertThat(trigram(c, id("on"), id("the"), id("mat"), 0)).isEqualTo(1)
        assertThat(c.trigrams.size).isEqualTo(5)
        assertThat(c.bigrams.size).isEqualTo(6)
        assertThat(bigram(c, id("the"), id("cat"), 1)).isEqualTo(0) // other corpus untouched
    }

    @Test
    fun oovTokenBreaksTheChain() {
        val c = CorpusCounts(vocab)
        c.addSentence("The cat zzyzx sat on the mat", CorpusCounts.TATOEBA)
        assertThat(bigram(c, id("cat"), id("sat"), 1)).isEqualTo(0)
        assertThat(trigram(c, id("the"), id("cat"), id("sat"), 1)).isEqualTo(0)
        assertThat(bigram(c, 0, id("sat"), 1)).isEqualTo(0) // not sentence-initial anymore
        assertThat(bigram(c, id("sat"), id("on"), 1)).isEqualTo(1)
        assertThat(trigram(c, id("sat"), id("on"), id("the"), 1)).isEqualTo(1)
        assertThat(c.stats[1].tokens).isEqualTo(7)
        assertThat(c.stats[1].oov).isEqualTo(1)
        assertThat(c.stats[1].inVocab).isEqualTo(6)
    }

    @Test
    fun oovFirstTokenMeansNoSentenceStartContext() {
        val c = CorpusCounts(vocab)
        c.addSentence("Zzyzx the cat", CorpusCounts.WIKI)
        assertThat(bigram(c, 0, id("the"), 0)).isEqualTo(0)
        assertThat(bigram(c, id("the"), id("cat"), 0)).isEqualTo(1)
        assertThat(c.trigrams.size).isEqualTo(0)
    }

    @Test
    fun numbersAreCountedSeparatelyAsOov() {
        val c = CorpusCounts(vocab)
        c.addSentence("The cat sat in 1990", CorpusCounts.WIKI)
        assertThat(c.stats[0].oovNumeric).isEqualTo(1)
        assertThat(c.stats[0].oovOther).isEqualTo(1) // "in" is not in the tiny test vocabulary
    }

    @Test
    fun caseFoldingApostrophesPossessivesAndHyphens() {
        val c = CorpusCounts(vocab)
        c.addSentence("i don’t know. The cat's mat is well-known.", CorpusCounts.WIKI)
        assertThat(bigram(c, 0, id("I"), 0)).isEqualTo(1) // lowercase "i" -> "I"
        assertThat(bigram(c, id("I"), id("don't"), 0)).isEqualTo(1)
        assertThat(bigram(c, id("the"), id("cat"), 0)).isEqualTo(1) // possessive 's -> base word
        assertThat(bigram(c, id("cat"), id("mat"), 0)).isEqualTo(1)
        assertThat(bigram(c, id("well"), id("known"), 0)).isEqualTo(1) // hyphen compound split
        assertThat(c.stats[0].oov).isEqualTo(2) // "know", "is"
    }

    @Test
    fun combiningMarksAreNormalisedToNfc() {
        val v = Vocabulary(listOf(VocabEntry("café", 100, 100, emptySet())))
        val c = CorpusCounts(v)
        c.addSentence("café", CorpusCounts.WIKI)
        assertThat(c.unigrams[0][1]).isEqualTo(1)
    }
}
