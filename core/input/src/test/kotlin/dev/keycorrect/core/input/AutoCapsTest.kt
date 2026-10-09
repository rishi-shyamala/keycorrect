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
        assertThat(AutoCaps.shouldCapitalize("a. ", AutoCapitalize.Sentences, startKnown = false))
            .isTrue()
        assertThat(AutoCaps.shouldCapitalize("abc", AutoCapitalize.Sentences, startKnown = false))
            .isFalse()
    }
}
