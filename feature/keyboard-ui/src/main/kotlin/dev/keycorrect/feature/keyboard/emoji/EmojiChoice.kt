package dev.keycorrect.feature.keyboard.emoji

import dev.keycorrect.core.emoji.Emoji
import dev.keycorrect.core.settings.SkinTone

/** Which form of a grid emoji a tap inserts (docs/PHASE4.md, PR B). */
internal object EmojiChoice {
    /**
     * The value to insert for [emoji]: the [preferred] variant the user chose for it (the base
     * itself counts), else the form in the default skin [tone] where the emoji has one, else the
     * base. A [preferred] value that is no longer one of its forms is ignored.
     */
    fun resolve(emoji: Emoji, preferred: String?, tone: SkinTone): String {
        if (preferred != null && isForm(emoji, preferred)) return preferred
        val label = toneLabel(tone)
        val form = label?.let { l -> emoji.variants.firstOrNull { it.label == l } }
        return form?.value ?: emoji.value
    }

    private fun isForm(emoji: Emoji, value: String): Boolean =
        value == emoji.value || emoji.variants.any { it.value == value }

    /** The catalog's label of the single-tone form of a person emoji; null for [SkinTone.None]. */
    private fun toneLabel(tone: SkinTone): String? =
        when (tone) {
            SkinTone.None -> null
            SkinTone.Light -> "light skin tone"
            SkinTone.MediumLight -> "medium-light skin tone"
            SkinTone.Medium -> "medium skin tone"
            SkinTone.MediumDark -> "medium-dark skin tone"
            SkinTone.Dark -> "dark skin tone"
        }
}
