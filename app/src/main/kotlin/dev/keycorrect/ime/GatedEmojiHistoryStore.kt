package dev.keycorrect.ime

import dev.keycorrect.core.data.EmojiHistoryStore

/**
 * Privacy gate for emoji history: nothing is recorded while [recording] is false (learning off,
 * incognito on, or settings not yet known). Reads and removals always pass through, so a pick made
 * in incognito simply isn't remembered and the user can always clear what is stored.
 */
class GatedEmojiHistoryStore(
    private val delegate: EmojiHistoryStore,
    private val recording: () -> Boolean,
) : EmojiHistoryStore {
    override suspend fun recents(limit: Int): List<String> = delegate.recents(limit)

    override suspend fun recordUse(emoji: String, base: String) {
        if (recording()) delegate.recordUse(emoji, base)
    }

    override suspend fun preferredVariant(base: String): String? = delegate.preferredVariant(base)

    override suspend fun preferredVariants(): Map<String, String> = delegate.preferredVariants()

    override suspend fun setPreferredVariant(base: String, variant: String?) {
        if (variant == null || recording()) delegate.setPreferredVariant(base, variant)
    }

    override suspend fun dump() = delegate.dump()

    override suspend fun clearSince(sinceMillis: Long) = delegate.clearSince(sinceMillis)

    override suspend fun clear() = delegate.clear()
}
