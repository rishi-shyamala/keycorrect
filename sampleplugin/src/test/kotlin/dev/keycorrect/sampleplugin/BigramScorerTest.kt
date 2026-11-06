package dev.keycorrect.sampleplugin

import com.google.common.truth.Truth.assertThat
import kotlin.math.exp
import org.junit.Test

class BigramScorerTest {
    private val scorer = BigramScorer.default

    private fun best(context: String, vararg candidates: String): String {
        val scores = scorer.score(context, arrayOf(*candidates))
        return candidates[scores.indices.maxBy { scores[it] }]
    }

    @Test
    fun theDataIsConsistent() {
        val vocabulary = BigramData.vocabulary.toSet()
        assertThat(BigramData.vocabulary).containsNoDuplicates()
        assertThat(vocabulary.size).isAtLeast(250)

        val pairs =
            BigramData.bigramLines
                .lines()
                .filter { ':' in it }
                .flatMap { line ->
                    val previous = line.substringBefore(':').trim()
                    line.substringAfter(':').trim().split(" ").map { previous to it }
                }
        assertThat(pairs.size).isAtLeast(300)
        pairs.forEach { (previous, word) ->
            if (previous != BigramData.START) assertThat(vocabulary).contains(previous)
            assertThat(vocabulary).contains(word)
        }
    }

    @Test
    fun aKnownBigramBeatsAnUnrelatedWord() {
        assertThat(best("thank", "you", "banana", "the")).isEqualTo("you")
        assertThat(best("see", "you", "when", "dog")).isEqualTo("you")
        assertThat(best("I hope you are doing good", "morning", "tomorrow", "xyzzy"))
            .isEqualTo("morning")
    }

    @Test
    fun contextChangesTheAnswer() {
        assertThat(best("good", "morning", "idea")).isEqualTo("morning")
        assertThat(best("great", "morning", "idea")).isEqualTo("idea")
    }

    @Test
    fun theLastWordOfTheContextIsUsedCaseInsensitively() {
        val a = scorer.score("Well, THANK", arrayOf("you"))[0]
        val b = scorer.score("thank", arrayOf("you"))[0]

        assertThat(a).isEqualTo(b)
    }

    @Test
    fun aSentenceEndOrEmptyContextUsesTheSentenceStart() {
        assertThat(BigramScorer.lastWord("")).isEqualTo(BigramData.START)
        assertThat(BigramScorer.lastWord("   ")).isEqualTo(BigramData.START)
        assertThat(BigramScorer.lastWord("See you.")).isEqualTo(BigramData.START)
        assertThat(BigramScorer.lastWord("See you. ")).isEqualTo(BigramData.START)
        assertThat(BigramScorer.lastWord("hello\n")).isEqualTo(BigramData.START)
        assertThat(BigramScorer.lastWord("See you")).isEqualTo("you")
        assertThat(BigramScorer.lastWord("It’s")).isEqualTo("it's")
        assertThat(best("", "thank", "banana")).isEqualTo("thank")
    }

    @Test
    fun theDistributionIsNormalized() {
        // Unknown words share one bucket, so summing the listed words and the bucket gives 1.
        for (previous in listOf("thank", "good", "zzz", BigramData.START)) {
            val listed = BigramData.vocabulary.sumOf { exp(scorer.logProbability(previous, it)) }
            val unknown = exp(scorer.logProbability(previous, "qqqqqq"))

            assertThat(listed + unknown).isWithin(1e-9).of(1.0)
        }
    }

    @Test
    fun anUnknownPreviousWordFallsBackToTheUnigrams() {
        // "the" is more frequent than "banana" (unknown) after a word with no bigrams.
        assertThat(best("zzz", "banana", "the")).isEqualTo("the")
    }

    @Test
    fun multiWordCandidatesAreChained() {
        val both = scorer.logProbability("thank", "you very")
        val parts = scorer.logProbability("thank", "you") + scorer.logProbability("you", "very")

        assertThat(both).isWithin(1e-12).of(parts)
    }

    @Test
    fun scoresAreFiniteNegativeAndOneToOne() {
        val candidates = arrayOf("you", "", "!!!", "x", "don't", "thank you", "été")
        val scores = scorer.score("thank", candidates)

        assertThat(scores).hasLength(candidates.size)
        scores.forEach {
            assertThat(it.isFinite()).isTrue()
            assertThat(it).isLessThan(0f)
        }
    }

    @Test
    fun scoringIsDeterministic() {
        val candidates = arrayOf("you", "the", "banana")

        assertThat(scorer.score("thank", candidates))
            .isEqualTo(
                BigramScorer(BigramData.vocabulary, BigramData.bigramLines)
                    .score("thank", candidates)
            )
    }
}
