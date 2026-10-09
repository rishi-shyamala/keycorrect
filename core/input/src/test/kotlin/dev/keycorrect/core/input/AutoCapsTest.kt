package dev.keycorrect.core.input

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutoCapsTest {
    private fun s(text: String) = AutoCaps.shouldCapitalize(text, AutoCapitalize.Sentences)

    private fun w(text: String) = AutoCaps.shouldCapitalize(text, AutoCapitalize.Words)

    @Test
    fun sentencesCapitaliseAtTheStartAndAfterATerminatorAndSpace() {
        assertThat(s("")).isTrue()
        assertThat(s("Hello. ")).isTrue()
        assertThat(s("Hello! ")).isTrue()
        assertThat(s("Really? ")).isTrue()
        assertThat(s("Hello.\n")).isTrue()
        assertThat(s("He said \"yes.\" ")).isTrue()
        assertThat(s("Hi. (")).isTrue()
    }

    @Test
    fun noCapitalMidSentenceOrWithoutTheSpace() {
        assertThat(s("Hello")).isFalse()
        assertThat(s("Hello ")).isFalse()
        assertThat(s("Hello.")).isFalse()
        assertThat(s("Hello, ")).isFalse()
    }

    @Test
    fun abbreviationsDoNotEndASentence() {
        assertThat(s("see e.g. ")).isFalse()
        assertThat(s("the U.S. ")).isFalse()
    }

    @Test
    fun wordsCapitaliseAfterAnyWhitespace() {
        assertThat(w("")).isTrue()
        assertThat(w("hello ")).isTrue()
        assertThat(w("hello")).isFalse()
        assertThat(w("hello (")).isTrue()
    }

    @Test
    fun charactersAlwaysAndNoneNever() {
        assertThat(AutoCaps.shouldCapitalize("abc", AutoCapitalize.Characters)).isTrue()
        assertThat(AutoCaps.shouldCapitalize("", AutoCapitalize.None)).isFalse()
    }

    @Test
    fun aTruncatedStartIsUnknownWhenTheAnswerDependsOnIt() {
        assertThat(AutoCaps.shouldCapitalize("  ", AutoCapitalize.Sentences, startKnown = false))
            .isNull()
        assertThat(
                AutoCaps.shouldCapitalize("hi you. ", AutoCapitalize.Sentences, startKnown = false)
            )
            .isTrue()
        assertThat(AutoCaps.shouldCapitalize("abc", AutoCapitalize.Sentences, startKnown = false))
            .isFalse()
    }

    @Test
    fun anAbbreviationCutOffByTruncationIsUnknown() {
        // "e.g. " whose "e." may have been cut to "g. ": a period could lie before the start.
        assertThat(AutoCaps.shouldCapitalize("g. ", AutoCapitalize.Sentences, startKnown = false))
            .isNull()
        // With the start known, the same text is a sentence end.
        assertThat(AutoCaps.shouldCapitalize("g. ", AutoCapitalize.Sentences, startKnown = true))
            .isTrue()
        // A non-letter ends the look-back inside the text: no doubt.
        assertThat(
                AutoCaps.shouldCapitalize("a go. ", AutoCapitalize.Sentences, startKnown = false)
            )
            .isTrue()
        assertThat(
                AutoCaps.shouldCapitalize("a e.g. ", AutoCapitalize.Sentences, startKnown = false)
            )
            .isFalse()
    }

    @Test
    fun typographicQuotesAreSkipped() {
        assertThat(s("He left.\u201D ")).isTrue()
        assertThat(s("He left. \u201C")).isTrue()
        assertThat(s("Yes. \u00BF")).isTrue()
        assertThat(s("He said \u2018")).isFalse()
    }
}
