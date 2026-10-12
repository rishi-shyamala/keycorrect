package dev.keycorrect.core.emoji

import java.io.InputStream

/**
 * The emoji picker's data: Unicode groups in CLDR order, search, keyword suggestions and kaomoji.
 * Immutable and thread-safe. Load the compiled asset with [read]; see `core/emoji/FORMAT.md`.
 */
class EmojiCatalog(
    val unicodeVersion: String,
    val cldrVersion: String,
    /** Unicode groups in picker order (the "Component" group is merged away). */
    val groups: List<EmojiGroup>,
    val kaomojiGroups: List<KaomojiGroup>,
) {
    /** All kaomoji, flattened in group order. */
    val kaomoji: List<String> = kaomojiGroups.flatMap { it.items }

    private val index by lazy(LazyThreadSafetyMode.PUBLICATION) { SearchIndex(groups) }

    /**
     * Case-insensitive prefix search over names and keywords, at most [limit] results. A query
     * matches an emoji when it equals or is a prefix of the name or of a keyword, or of any
     * space-separated word in them. Ranking: exact name, then exact keyword, then prefix; ties keep
     * catalog order. Variants are not searched; a blank query returns nothing.
     */
    fun search(query: String, limit: Int): List<Emoji> = index.search(query, limit)

    /**
     * The emoji a typed [word] should suggest, or null. "Strong" means the lowercased word has at
     * least 3 characters, is not in a small stop-list of very common words (and generic names such
     * as "man" or "face"), and either equals an emoji's CLDR short name ("pizza" gives the pizza
     * emoji) or is a keyword of exactly one emoji. A keyword shared by several emoji ("happy")
     * gives null.
     */
    fun strongMatch(word: String): Emoji? = index.strongMatch(word)

    /**
     * The catalog entry whose [Emoji.value] is [value]. A skin-tone or gender variant's value
     * resolves to its base emoji. Null if the value is unknown.
     */
    fun byValue(value: String): Emoji? = index.byValue(value)

    companion object {
        /** Parses a compiled `emoji_en.kce` asset. Throws [EmojiFormatException] if malformed. */
        fun read(input: InputStream): EmojiCatalog = EmojiAssetReader.read(input)
    }
}
