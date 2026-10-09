package dev.keycorrect.tools.dictbuild

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TokenizerTest {
    @Test
    fun keepsInternalApostrophesAndHyphens() {
        assertThat(Tokenizer.tokens("Don't send an e-mail, I'm busy."))
            .containsExactly("Don't", "send", "an", "e-mail", "I'm", "busy")
            .inOrder()
    }

    @Test
    fun normalisesTypographicApostrophesAndHyphens() {
        assertThat(Tokenizer.tokens("don’t well‐known"))
            .containsExactly("don't", "well-known")
            .inOrder()
    }

    @Test
    fun edgeConnectorsAndPunctuationAreSeparators() {
        assertThat(Tokenizer.tokens("'quoted' dogs' -dash- (paren) \"x\" a--b"))
            .containsExactly("quoted", "dogs", "dash", "paren", "x", "a", "b")
            .inOrder()
    }

    @Test
    fun handlesUnicodeLettersDigitsAndCombiningMarks() {
        assertThat(Tokenizer.tokens("café Zürich MP3 café été"))
            .containsExactly("café", "Zürich", "MP3", "café", "été")
            .inOrder()
    }

    @Test
    fun numericDetection() {
        assertThat(Tokenizer.isNumeric("1990")).isTrue()
        assertThat(Tokenizer.isNumeric("3-4")).isTrue()
        assertThat(Tokenizer.isNumeric("MP3")).isFalse()
    }

    @Test
    fun emptyAndSymbolOnlyInput() {
        assertThat(Tokenizer.tokens("")).isEmpty()
        assertThat(Tokenizer.tokens("... -- !!")).isEmpty()
    }
}
