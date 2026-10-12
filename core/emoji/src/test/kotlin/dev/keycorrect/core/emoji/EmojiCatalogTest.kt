package dev.keycorrect.core.emoji

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.emoji.TestCatalog.catalog
import dev.keycorrect.core.emoji.TestCatalog.grin
import dev.keycorrect.core.emoji.TestCatalog.heart
import dev.keycorrect.core.emoji.TestCatalog.pizza
import dev.keycorrect.core.emoji.TestCatalog.pizzaParty
import dev.keycorrect.core.emoji.TestCatalog.smile
import dev.keycorrect.core.emoji.TestCatalog.star
import dev.keycorrect.core.emoji.TestCatalog.thumbs
import org.junit.Test

class EmojiCatalogTest {
    @Test
    fun exactNameBeatsExactKeywordBeatsPrefix() {
        // "pizza": the pizza emoji by name, the party emoji by keyword (listed earlier).
        assertThat(catalog.search("pizza", 10)).containsExactly(pizza, pizzaParty).inOrder()
        // "smil": prefix of keyword "smile"/"smiling" only: catalog order.
        assertThat(catalog.search("smil", 10)).containsExactly(grin, smile).inOrder()
        // "happy": exact keyword on three emoji, catalog order.
        assertThat(catalog.search("happy", 10)).containsExactly(grin, smile, heart).inOrder()
    }

    @Test
    fun searchIsCaseInsensitiveWordPrefixAndLimited() {
        assertThat(catalog.search("  PIZ ", 10)).containsExactly(pizzaParty, pizza).inOrder()
        assertThat(catalog.search("hea", 10)).containsExactly(heart)
        assertThat(catalog.search("face with smi", 10)).containsExactly(smile)
        assertThat(catalog.search("ace", 10)).isEmpty() // prefix, not substring
        assertThat(catalog.search("smiling", 10)).containsExactly(smile)
        assertThat(catalog.search("face", 1)).hasSize(1)
        assertThat(catalog.search("", 10)).isEmpty()
        assertThat(catalog.search("pizza", 0)).isEmpty()
        assertThat(catalog.search("zzzz", 10)).isEmpty()
    }

    @Test
    fun exactNameWinsEvenWhenLaterInCatalog() {
        val night = TestCatalog.emoji("N", "night with stars", "star")
        val c =
            EmojiCatalog("17.0", "48.2", listOf(EmojiGroup("G", listOf(night, star))), emptyList())
        assertThat(c.search("star", 5)).containsExactly(star, night).inOrder()
    }

    @Test
    fun strongMatchByNameAndUniqueKeyword() {
        assertThat(catalog.strongMatch("pizza")).isEqualTo(pizza)
        assertThat(catalog.strongMatch("Pizza")).isEqualTo(pizza) // name beats the shared keyword
        assertThat(catalog.strongMatch("cheese")).isEqualTo(pizza) // unique keyword
        assertThat(catalog.strongMatch("celebration")).isEqualTo(pizzaParty)
        assertThat(catalog.strongMatch("star")).isEqualTo(star)
    }

    @Test
    fun strongMatchRejectsSharedShortAndStopWords() {
        assertThat(catalog.strongMatch("happy")).isNull() // shared by three emoji
        assertThat(catalog.strongMatch("smile")).isNull()
        assertThat(catalog.strongMatch("+1")).isNull() // too short
        assertThat(catalog.strongMatch("up")).isNull()
        assertThat(catalog.strongMatch("man")).isNull() // stop-listed even though it is a name
        assertThat(catalog.strongMatch("adult")).isEqualTo(TestCatalog.man)
        assertThat(catalog.strongMatch("uniquekw")).isEqualTo(TestCatalog.man)
        assertThat(catalog.strongMatch("the")).isNull()
        assertThat(catalog.strongMatch("")).isNull()
    }

    @Test
    fun byValueFindsBaseAndVariantValues() {
        assertThat(catalog.byValue(pizza.value)).isEqualTo(pizza)
        assertThat(catalog.byValue(thumbs.variants[0].value)).isEqualTo(thumbs)
        assertThat(catalog.byValue("nope")).isNull()
    }

    @Test
    fun kaomojiAreFlattenedInGroupOrder() {
        assertThat(catalog.kaomoji).containsExactly("(^_^)", "\\(^o^)/", "(T_T)").inOrder()
    }
}
