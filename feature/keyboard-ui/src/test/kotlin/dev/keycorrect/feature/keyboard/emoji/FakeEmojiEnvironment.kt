package dev.keycorrect.feature.keyboard.emoji

import dev.keycorrect.core.emoji.Emoji
import dev.keycorrect.core.emoji.EmojiCatalog
import dev.keycorrect.core.emoji.EmojiGroup
import dev.keycorrect.core.emoji.EmojiVariant
import dev.keycorrect.core.emoji.KaomojiGroup

/** A small catalog: a few faces, a thumbs up with tones, a runner with tones and genders. */
internal object TestEmoji {
    const val PIZZA_VALUE = "🍕"
    const val THUMBS_VALUE = "👍"
    const val THUMBS_LIGHT = "👍🏻"
    const val THUMBS_MEDIUM = "👍🏽"
    const val THUMBS_DARK = "👍🏿"

    private fun emoji(
        value: String,
        name: String,
        vararg keywords: String,
        variants: List<EmojiVariant> = emptyList(),
    ) = Emoji(value, name, keywords.toList(), variants, "Test")

    val grin = emoji("😀", "grinning face", "face", "happy", "smile")
    val pizza = emoji(PIZZA_VALUE, "pizza", "cheese", "food", "slice")
    val thumbs =
        emoji(
            THUMBS_VALUE,
            "thumbs up",
            "+1",
            "like",
            variants =
                listOf(
                    EmojiVariant(THUMBS_LIGHT, "light skin tone"),
                    EmojiVariant(THUMBS_MEDIUM, "medium skin tone"),
                    EmojiVariant(THUMBS_DARK, "dark skin tone"),
                ),
        )

    val catalog =
        EmojiCatalog(
            "17.0",
            "48.2",
            listOf(EmojiGroup("Test", listOf(grin, pizza, thumbs))),
            listOf(KaomojiGroup("Happy", listOf("(^_^)"))),
        )
}

/** Records every call so tests can see what the keyboard asked of the app. */
internal class FakeEmojiEnvironment(override var catalog: EmojiCatalog? = TestEmoji.catalog) :
    EmojiEnvironment {
    override var recents: List<String> = emptyList()
    val preferred = mutableMapOf<String, String>()
    val used = mutableListOf<Pair<String, String>>()

    override fun preferredVariant(base: String): String? = preferred[base]

    override fun setPreferredVariant(base: String, variant: String?) {
        if (variant == null) preferred.remove(base) else preferred[base] = variant
    }

    override fun onEmojiUsed(value: String, base: String) {
        used += value to base
        recents = listOf(value) + recents.filter { it != value }
    }
}
