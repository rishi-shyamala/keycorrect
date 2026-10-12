package dev.keycorrect.feature.keyboard.emoji

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EmojiSearchTest {
    private val search = EmojiSearch()

    @Test
    fun typingIsIgnoredUntilOpened() {
        search.type("pi")
        assertThat(search.query).isEmpty()
        assertThat(search.active).isFalse()
    }

    @Test
    fun openTypeBackspaceAndClose() {
        search.open()
        search.type("p")
        search.type("iz")
        assertThat(search.query).isEqualTo("piz")
        search.backspace()
        assertThat(search.query).isEqualTo("pi")
        search.close()
        assertThat(search.active).isFalse()
        assertThat(search.query).isEmpty()
    }

    @Test
    fun reopeningStartsEmpty() {
        search.open()
        search.type("pizza")
        search.open()
        assertThat(search.query).isEmpty()
    }

    @Test
    fun leadingSpacesAndControlCharactersAreIgnored() {
        search.open()
        search.type(" ")
        search.type("\n")
        assertThat(search.query).isEmpty()
        search.type("a b")
        assertThat(search.query).isEqualTo("a b")
    }

    @Test
    fun backspaceRemovesAWholeCodePoint() {
        search.open()
        search.type("a🍕")
        search.backspace()
        assertThat(search.query).isEqualTo("a")
        search.backspace()
        search.backspace()
        assertThat(search.query).isEmpty()
    }

    @Test
    fun theQueryIsCapped() {
        search.open()
        search.type("x".repeat(EmojiSearch.MAX_QUERY + 10))
        assertThat(search.query).hasLength(EmojiSearch.MAX_QUERY)
    }

    @Test
    fun resultsComeFromTheCatalog() {
        search.open()
        assertThat(search.results(TestEmoji.catalog)).isEmpty()
        search.type("pizz")
        assertThat(search.results(TestEmoji.catalog)).containsExactly(TestEmoji.pizza)
        assertThat(search.results(null)).isEmpty()
        search.type("zzz")
        assertThat(search.results(TestEmoji.catalog)).isEmpty()
    }
}
