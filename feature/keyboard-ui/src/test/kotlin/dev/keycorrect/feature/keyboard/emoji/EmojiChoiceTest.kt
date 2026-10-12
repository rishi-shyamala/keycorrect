package dev.keycorrect.feature.keyboard.emoji

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.emoji.EmojiCatalog
import dev.keycorrect.core.emoji.EmojiGroup
import dev.keycorrect.core.emoji.EmojiVariant
import dev.keycorrect.core.settings.SkinTone
import org.junit.Test

class EmojiChoiceTest {
    private val thumbs = TestEmoji.thumbs

    @Test
    fun aPreferredVariantBeatsTheDefaultTone() {
        assertThat(EmojiChoice.resolve(thumbs, TestEmoji.THUMBS_DARK, SkinTone.Light))
            .isEqualTo(TestEmoji.THUMBS_DARK)
    }

    @Test
    fun theBaseItselfCanBeThePreferredChoice() {
        assertThat(EmojiChoice.resolve(thumbs, TestEmoji.THUMBS_VALUE, SkinTone.Medium))
            .isEqualTo(TestEmoji.THUMBS_VALUE)
    }

    @Test
    fun theDefaultToneAppliesWhereTheVariantExists() {
        assertThat(EmojiChoice.resolve(thumbs, null, SkinTone.Medium))
            .isEqualTo(TestEmoji.THUMBS_MEDIUM)
    }

    @Test
    fun aToneTheEmojiLacksFallsBackToTheBase() {
        // The test thumbs has no medium-dark form (a font may have lacked it).
        assertThat(EmojiChoice.resolve(thumbs, null, SkinTone.MediumDark))
            .isEqualTo(TestEmoji.THUMBS_VALUE)
    }

    @Test
    fun noToneAndNoChoiceIsTheBase() {
        assertThat(EmojiChoice.resolve(thumbs, null, SkinTone.None))
            .isEqualTo(TestEmoji.THUMBS_VALUE)
        assertThat(EmojiChoice.resolve(TestEmoji.pizza, null, SkinTone.Dark))
            .isEqualTo(TestEmoji.PIZZA_VALUE)
    }

    @Test
    fun aStalePreferredValueIsIgnored() {
        assertThat(EmojiChoice.resolve(thumbs, "👎", SkinTone.Light))
            .isEqualTo(TestEmoji.THUMBS_LIGHT)
    }

    @Test
    fun theGlyphFilterDropsMissingEmojiAndVariants() {
        val missing = setOf(TestEmoji.PIZZA_VALUE, TestEmoji.THUMBS_MEDIUM)
        val filtered = EmojiGlyphFilter.filter(TestEmoji.catalog) { it !in missing }
        val all = filtered.groups.flatMap { it.emoji }
        assertThat(all.map { it.name }).containsExactly("grinning face", "thumbs up").inOrder()
        assertThat(all.last().variants.map { it.value })
            .containsExactly(TestEmoji.THUMBS_LIGHT, TestEmoji.THUMBS_DARK)
        assertThat(filtered.kaomoji).containsExactly("(^_^)")
        assertThat(filtered.byValue(TestEmoji.THUMBS_MEDIUM)).isNull()
    }

    @Test
    fun theGlyphFilterKeepsTheCatalogWhenNothingIsMissing() {
        assertThat(EmojiGlyphFilter.filter(TestEmoji.catalog) { true })
            .isSameInstanceAs(TestEmoji.catalog)
    }

    @Test
    fun aGroupWithNothingDrawableDisappears() {
        val catalog =
            EmojiCatalog(
                "1",
                "1",
                listOf(
                    EmojiGroup("A", listOf(TestEmoji.pizza)),
                    EmojiGroup("B", listOf(TestEmoji.grin)),
                ),
                emptyList(),
            )
        val filtered = EmojiGlyphFilter.filter(catalog) { it != TestEmoji.PIZZA_VALUE }
        assertThat(filtered.groups.map { it.name }).containsExactly("B")
        assertThat(EmojiVariant("x", "y").label).isEqualTo("y")
    }
}
