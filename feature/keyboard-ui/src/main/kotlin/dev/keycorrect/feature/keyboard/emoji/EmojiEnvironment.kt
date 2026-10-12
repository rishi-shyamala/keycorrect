package dev.keycorrect.feature.keyboard.emoji

import dev.keycorrect.core.emoji.EmojiCatalog

/**
 * What the keyboard needs for emoji (docs/PHASE4.md, PR B), provided by the app: the catalog, the
 * recently used emoji and the per-emoji variant choices. Every member is called on the main thread
 * and must not block: the host loads the catalog and the history in the background and keeps
 * in-memory copies; writes go to its encrypted store asynchronously, and only when learning is on
 * and incognito off.
 */
interface EmojiEnvironment {
    /** The catalog, or null while it loads (the panel waits; the strip suggests no emoji). */
    val catalog: EmojiCatalog?

    /** Recently used emoji values, most recent first. */
    val recents: List<String>

    /** The variant (skin tone, gender) chosen for grid emoji [base], or null for none. */
    fun preferredVariant(base: String): String?

    /** [variant] was chosen for [base] from its long-press popup; null forgets the choice. */
    fun setPreferredVariant(base: String, variant: String?)

    /** [value] (for grid emoji [base]) was inserted. */
    fun onEmojiUsed(value: String, base: String)

    companion object {
        /** No emoji: no catalog, nothing remembered. */
        val None =
            object : EmojiEnvironment {
                override val catalog: EmojiCatalog? = null
                override val recents: List<String> = emptyList()

                override fun preferredVariant(base: String): String? = null

                override fun setPreferredVariant(base: String, variant: String?) = Unit

                override fun onEmojiUsed(value: String, base: String) = Unit
            }
    }
}
