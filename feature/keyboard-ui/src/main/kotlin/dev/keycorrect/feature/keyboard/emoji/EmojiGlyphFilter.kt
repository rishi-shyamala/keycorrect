package dev.keycorrect.feature.keyboard.emoji

import dev.keycorrect.core.emoji.Emoji
import dev.keycorrect.core.emoji.EmojiCatalog
import dev.keycorrect.core.emoji.EmojiGroup

/** Hides the emoji the device font cannot draw (no EmojiCompat; docs/PHASE4.md). */
object EmojiGlyphFilter {
    /**
     * [catalog] without the emoji [hasGlyph] rejects. A base emoji the font lacks goes with all its
     * variants; a variant it lacks is dropped on its own. Empty groups go. Kaomoji are plain text
     * and stay. Returns [catalog] itself when nothing is dropped.
     */
    fun filter(catalog: EmojiCatalog, hasGlyph: (String) -> Boolean): EmojiCatalog {
        var dropped = false
        val groups =
            catalog.groups.mapNotNull { group ->
                val kept =
                    group.emoji.mapNotNull { emoji ->
                        keep(emoji, hasGlyph).also { if (it !== emoji) dropped = true }
                    }
                if (kept.isEmpty()) null else EmojiGroup(group.name, kept)
            }
        if (!dropped) return catalog
        return EmojiCatalog(
            catalog.unicodeVersion,
            catalog.cldrVersion,
            groups,
            catalog.kaomojiGroups,
        )
    }

    private fun keep(emoji: Emoji, hasGlyph: (String) -> Boolean): Emoji? {
        if (!hasGlyph(emoji.value)) return null
        val variants = emoji.variants.filter { hasGlyph(it.value) }
        return if (variants.size == emoji.variants.size) emoji else emoji.copy(variants = variants)
    }
}
