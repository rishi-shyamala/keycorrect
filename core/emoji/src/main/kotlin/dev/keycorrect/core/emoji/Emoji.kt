package dev.keycorrect.core.emoji

/**
 * One emoji as shown in the picker grid.
 *
 * @property value the fully-qualified Unicode sequence (what is committed to the editor).
 * @property name the CLDR short name (e.g. "thumbs up"), lowercase for most emoji.
 * @property keywords CLDR search keywords, in CLDR order; may be empty.
 * @property variants skin-tone and gender forms of this emoji, in catalog order; empty if none.
 *   They are not separate grid entries.
 * @property group name of the [EmojiGroup] this emoji is listed in.
 */
data class Emoji(
    val value: String,
    val name: String,
    val keywords: List<String>,
    val variants: List<EmojiVariant>,
    val group: String,
)

/**
 * A skin-tone and/or gender form of a base [Emoji].
 *
 * @property label accessibility description, e.g. "medium skin tone", "woman" or "man, light skin
 *   tone". For multi-person emoji with two tones it lists both tones.
 */
data class EmojiVariant(val value: String, val label: String)

/** A Unicode emoji group (e.g. "Food & Drink") in picker order. */
data class EmojiGroup(val name: String, val emoji: List<Emoji>)

/** A named set of kaomoji (Japanese-style text emoticons), e.g. "Happy". */
data class KaomojiGroup(val name: String, val items: List<String>)
