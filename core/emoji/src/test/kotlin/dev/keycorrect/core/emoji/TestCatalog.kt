package dev.keycorrect.core.emoji

/** A small hand-made catalog shared by the unit tests. */
internal object TestCatalog {
    fun emoji(
        value: String,
        name: String,
        vararg keywords: String,
        variants: List<EmojiVariant> = emptyList(),
        group: String = "Test",
    ) = Emoji(value, name, keywords.toList(), variants, group)

    val pizza = emoji("\uD83C\uDF55", "pizza", "cheese", "food", "slice")
    val grin = emoji("\uD83D\uDE00", "grinning face", "face", "happy", "smile")
    val smile = emoji("\uD83D\uDE04", "grinning face with smiling eyes", "face", "happy", "smile")
    val heart = emoji("\u2764\uFE0F", "red heart", "heart", "love", "happy")
    val pizzaParty = emoji("\uD83E\uDD73", "partying face", "party", "pizza", "celebration")
    val thumbs =
        emoji(
            "\uD83D\uDC4D",
            "thumbs up",
            "+1",
            "hand",
            "like",
            variants = listOf(EmojiVariant("\uD83D\uDC4D\uD83C\uDFFB", "light skin tone")),
        )
    val star = emoji("\u2B50", "star", "night", "starry", group = "Other")
    val man = emoji("\uD83D\uDC68", "man", "adult", "uniquekw")

    val catalog =
        EmojiCatalog(
            "17.0",
            "48.2",
            listOf(
                EmojiGroup("Test", listOf(grin, smile, heart, pizzaParty, pizza, thumbs, man)),
                EmojiGroup("Other", listOf(star)),
            ),
            listOf(
                KaomojiGroup("Happy", listOf("(^_^)", "\\(^o^)/")),
                KaomojiGroup("Sad", listOf("(T_T)")),
            ),
        )
}
