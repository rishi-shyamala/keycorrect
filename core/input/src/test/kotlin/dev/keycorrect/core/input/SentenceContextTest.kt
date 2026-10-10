package dev.keycorrect.core.input

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Mirrors the decoder's `Text.lastSentenceWords` contract; both must give the same buckets. */
class SentenceContextTest {
    private fun words(s: String, n: Int = 2) = SentenceContext.lastWords(s, n)

    @Test
    fun emptyAndSentenceStartHaveNoContext() {
        assertThat(words("")).isEmpty()
        assertThat(words("Hello. ")).isEmpty()
        assertThat(words("What? ")).isEmpty()
        assertThat(words("Wow! ")).isEmpty()
        assertThat(words("First line\n")).isEmpty()
    }

    @Test
    fun keepsTheLastTwoWordsOfTheSentence() {
        assertThat(words("I will see you ")).containsExactly("see", "you").inOrder()
        assertThat(words("hello ")).containsExactly("hello")
        assertThat(words("one two three", 3)).containsExactly("one", "two", "three").inOrder()
    }

    @Test
    fun aSentenceBoundaryCutsTheContext() {
        assertThat(words("It rained. Then we ")).containsExactly("Then", "we").inOrder()
        assertThat(words("It rained. Then ")).containsExactly("Then")
        assertThat(words("a b.\nc ")).containsExactly("c")
    }

    @Test
    fun anAbbreviationDotWithoutWhitespaceDoesNotEndTheSentence() {
        assertThat(words("see e.g.x and ")).contains("and")
        assertThat(words("v1.2 beta ")).contains("beta")
        assertThat(words("3.14 is "))
            .containsExactly("14", "is")
            .inOrder() // "3.14" splits at the dot
    }

    @Test
    fun commasAndQuotesDoNotCutTheContext() {
        assertThat(words("well, then ")).containsExactly("well", "then").inOrder()
        assertThat(words("he said \"go ")).containsExactly("said", "go").inOrder()
    }

    @Test
    fun apostrophesAreFoldedAndHyphensKept() {
        assertThat(words("I don’t know ")).containsExactly("don't", "know").inOrder()
        assertThat(words("a well-known ")).containsExactly("a", "well-known").inOrder()
        assertThat(words("rock ʼn’ ")).isNotEmpty()
    }

    @Test
    fun caseIsKept() {
        assertThat(words("Visit Paris ")).containsExactly("Visit", "Paris").inOrder()
    }

    @Test
    fun theBeginningOfAWindowedStringDoesNotYieldAHalfWord() {
        val long = "x".repeat(300) + " hello "
        assertThat(words(long)).containsExactly("hello")
        val glued = "y".repeat(400) + " "
        // The 192-char window starts inside the long token; the partial token is skipped.
        assertThat(words(glued)).isEmpty()
    }

    @Test
    fun theLearningContextEqualsTheStringVersionForAnyCharSequence() {
        val sb = StringBuilder("The cat sat. Then the dog ")
        assertThat(SentenceContext.lastWords(sb)).isEqualTo(words(sb.toString()))
    }

    @Test
    fun sentenceEndsAreRecognisedFromTheSeparator() {
        for (s in listOf(".", "!", "?", "…", "\n", ". ")) {
            assertThat(SentenceContext.endsSentence(s)).isTrue()
        }
        assertThat(SentenceContext.endsSentence(null)).isTrue() // Enter
        for (s in listOf(" ", ",", ";", ":", ")")) {
            assertThat(SentenceContext.endsSentence(s)).isFalse()
        }
    }
}
