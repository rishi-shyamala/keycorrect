package dev.keycorrect.core.decoder

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.engineapi.CandidateSource
import dev.keycorrect.core.engineapi.ComposingInput
import dev.keycorrect.core.engineapi.FieldClass
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.TypingContext
import org.junit.Test

class AutocorrectSafetyTest {
    private val dictionary = TestDictionary.dictionary

    private fun engine(userWords: List<String> = emptyList()) =
        ClassicEngine(dictionary, ClassicConfig(), { userWords })

    private fun autocorrect(
        typed: ComposingInput,
        before: String,
        userWords: List<String> = emptyList(),
    ): String? =
        engine(userWords)
            .predictNow(
                PredictRequest(
                    TypingContext(before, "", FieldClass.Text),
                    typed,
                    TestKeyboard.layout,
                    3,
                    0,
                    true,
                )
            )
            .autocorrect
            ?.text

    // ------------------------------------------------------------ capitalised words

    @Test
    fun lowercaseTypoIsStillCorrectedMidSentence() {
        assertThat(autocorrect(TestKeyboard.typed("frend"), "I met a ")).isEqualTo("friend")
    }

    @Test
    fun sentenceInitialCapitalTypoIsCorrected() {
        assertThat(autocorrect(TestKeyboard.typed("Helli"), "")).isEqualTo("Hello")
        assertThat(autocorrect(TestKeyboard.typed("Helli"), "I left. ")).isEqualTo("Hello")
    }

    @Test
    fun unknownCapitalisedWordMidSentenceIsLeftAlone() {
        // "Helli" looks like a typo of "hello" but could be a name.
        assertThat(autocorrect(TestKeyboard.typed("Helli"), "I said ")).isNull()
        assertThat(autocorrect(TestKeyboard.typed("Frend"), "I met ")).isNull()
    }

    @Test
    fun capitalisedSingleTranspositionMidSentenceIsStillCorrected() {
        assertThat(autocorrect(TestKeyboard.typed("Teh"), "I said ")).isEqualTo("The")
    }

    @Test
    fun capitalisedWordAfterUnknownWordCountsAsMidSentence() {
        assertThat(autocorrect(TestKeyboard.typed("Helli"), "qzxv ")).isNull()
    }

    @Test
    fun knownCapitalisedWordIsNeverTurnedIntoALowercaseWordMidSentence() {
        val breakdown = ScoreBreakdown(0f, -5f, 0f, -1f, 1, 0, CandidateSource.Dictionary)
        val candidate = RankedCandidate("Hello", -6f, breakdown, 7, "hello")
        fun input(sentenceInitial: Boolean, form: String = "hello") =
            AutocorrectInput(
                literal = "Hellp",
                literalScore = -40f,
                literalIsDictionaryWord = true,
                literalIsUserWord = false,
                candidates = listOf(RankedCandidate("Hello", -6f, breakdown, 7, form)),
                field = FieldClass.Text,
                allowed = true,
                sentenceInitial = sentenceInitial,
            )
        val policy = AutocorrectPolicy()
        assertThat(policy.choose(input(sentenceInitial = true))).isNotNull()
        assertThat(policy.choose(input(sentenceInitial = false))).isNull()
        // A dictionary word that itself has capitals ("McDonald") may still be the target.
        assertThat(policy.choose(input(sentenceInitial = false, form = "Hello"))).isNotNull()
        assertThat(candidate.dictionaryForm).isEqualTo("hello")
    }

    @Test
    fun userWordsAreKnownCaseInsensitivelyAndWithTypographicApostrophes() {
        assertThat(autocorrect(TestKeyboard.typed("helli"), "I said ", listOf("HELLI"))).isNull()
        // The curly apostrophe literal must resolve to the same key as the user's straight one.
        assertThat(Text.key("Zo’e")).isEqualTo(Text.key("zo'e"))
        assertThat(Text.key("Zo‘e")).isEqualTo("zo'e")
    }

    @Test
    fun transpositionHelperRecognisesOnlySingleAdjacentSwaps() {
        assertThat(Text.isSingleTransposition("teh", "the")).isTrue()
        assertThat(Text.isSingleTransposition("Teh", "The")).isTrue()
        assertThat(Text.isSingleTransposition("the", "the")).isFalse()
        assertThat(Text.isSingleTransposition("tge", "the")).isFalse()
        assertThat(Text.isSingleTransposition("hte", "eht")).isFalse()
        assertThat(Text.isSingleTransposition("a", "a")).isFalse()
    }

    // ------------------------------------------------------------ contractions

    @Test
    fun apostropheLessContractionsAreCorrected() {
        val cases =
            mapOf(
                "dont" to "don't",
                "im" to "I'm",
                "cant" to "can't",
                "wont" to "won't",
                "isnt" to "isn't",
                "youre" to "you're",
                "didnt" to "didn't",
            )
        for ((typed, expected) in cases) {
            // "can" and "won" outscore "can't"/"won't" at a sentence start (bigram from <s>).
            if (typed != "cant" && typed != "wont") {
                assertThat(autocorrect(TestKeyboard.typed(typed), "")).isEqualTo(expected)
            }
            assertThat(autocorrect(TestKeyboard.typed(typed), "so ")).isEqualTo(expected)
        }
    }

    @Test
    fun contractionsAreOfferedAsCandidates() {
        val r =
            engine()
                .predictNow(
                    PredictRequest(
                        TypingContext("", "", FieldClass.Text),
                        TestKeyboard.typed("youre"),
                        TestKeyboard.layout,
                        3,
                        0,
                        true,
                    )
                )
        assertThat(r.candidates.map { it.text }).contains("you're")
    }

    @Test
    fun capitalisedContractionKeepsItsCapital() {
        assertThat(autocorrect(TestKeyboard.typed("Dont"), "")).isEqualTo("Don't")
    }
}
