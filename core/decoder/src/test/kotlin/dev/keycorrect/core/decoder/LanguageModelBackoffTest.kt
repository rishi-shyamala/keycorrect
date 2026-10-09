package dev.keycorrect.core.decoder

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.dictionary.Dictionary
import org.junit.Test

class LanguageModelBackoffTest {
    private val dict = TestDictionary.dictionary

    @Test
    fun defaultsMatchTheDictionaryBuild() {
        val scoring = ScoringConfig()
        assertThat(scoring.trigramBackoffPenalty).isEqualTo(-0.8f)
        assertThat(scoring.bigramBackoffPenalty).isEqualTo(-1.3f)
    }

    @Test
    fun trigramHitHasNoPenalty() {
        val lm = LanguageModel(dict, ScoringConfig())
        lm.setContext("look over ")
        val there = dict.wordId("there")
        val tri = dict.logProbInTrigramList(dict.trigramList(lm.w2, lm.w1), there)
        assertThat(tri.isNaN()).isFalse()
        assertThat(lm.logProb(there)).isEqualTo(tri)
    }

    @Test
    fun trigramMissWithBigramHitPaysOnlyTheTrigramPenalty() {
        val lm = LanguageModel(dict, ScoringConfig())
        lm.setContext("look over ")
        val their = dict.wordId("their")
        val bi = dict.logProbInBigramList(dict.bigramList(lm.w1), their)
        assertThat(bi.isNaN()).isFalse()
        assertThat(dict.logProbInTrigramList(dict.trigramList(lm.w2, lm.w1), their).isNaN())
            .isTrue()
        assertThat(lm.logProb(their)).isWithin(1e-5f).of(bi - 0.8f)
    }

    @Test
    fun missingBothPaysBothPenaltiesAndTheyAreConfigurable() {
        val the = dict.wordId("the")
        val default = LanguageModel(dict, ScoringConfig())
        default.setContext("they lost ")
        assertThat(default.logProb(the)).isWithin(1e-5f).of(dict.logProb(the) - 0.8f - 1.3f)

        val custom =
            LanguageModel(
                dict,
                ScoringConfig(trigramBackoffPenalty = -0.3f, bigramBackoffPenalty = -2f),
            )
        custom.setContext("they lost ")
        assertThat(custom.logProb(the)).isWithin(1e-5f).of(dict.logProb(the) - 0.3f - 2f)
    }

    @Test
    fun bigramMissWithoutTrigramContextPaysOnlyTheBigramPenalty() {
        val lm = LanguageModel(dict, ScoringConfig())
        lm.setContext("hello ")
        val the = dict.wordId("the")
        // "hello" has bigram successors but, after a sentence start, no trigram context.
        assertThat(lm.w2).isEqualTo(Dictionary.BOS_ID)
        val expectedBigram = dict.logProbInBigramList(dict.bigramList(lm.w1), the)
        assertThat(expectedBigram.isNaN()).isTrue()
        val tri = dict.trigramList(lm.w2, lm.w1)
        val expected = dict.logProb(the) + (if (tri >= 0) -0.8f else 0f) - 1.3f
        assertThat(lm.logProb(the)).isWithin(1e-5f).of(expected)
    }

    @Test
    fun rightContextBackoffUsesTheBigramPenalty() {
        val lm = LanguageModel(dict, ScoringConfig())
        val keys = dict.wordId("keys")
        assertThat(lm.rightLogProb(-1, keys)).isWithin(1e-5f).of(dict.logProb(keys) - 1.3f)
    }
}
