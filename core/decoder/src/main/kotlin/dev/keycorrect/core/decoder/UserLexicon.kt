package dev.keycorrect.core.decoder

import dev.keycorrect.core.dictionary.Dictionary

/**
 * The user's own words and text-expansion shortcuts, indexed for the decoder. Both come from
 * provider callbacks that return snapshots maintained elsewhere; the index is rebuilt only when a
 * provider returns a different instance (or, for words, a different size), so snapshots must be
 * replaced rather than mutated in place. Not thread-safe.
 */
internal class UserLexicon(
    private val dict: Dictionary,
    private val config: UserDataConfig,
    private val userWords: () -> Collection<String>,
    private val shortcuts: () -> Map<String, String>,
) {
    private var wordSnapshot: Collection<String>? = null
    private var wordSnapshotSize = -1
    private var keys = emptyArray<String>()
    private var surfaces = emptyArray<String>()
    private var knownToDictionary = BooleanArray(0)
    private val keySet = HashSet<String>()

    private var shortcutSnapshot: Map<String, String>? = null
    private val shortcutByTrigger = HashMap<String, String>()

    /** Number of indexed user words. */
    val size: Int
        get() = keys.size

    fun key(index: Int): String = keys[index]

    fun surface(index: Int): String = surfaces[index]

    /** True if user word [index] is also in the dictionary (then the trie search covers it). */
    fun isInDictionary(index: Int): Boolean = knownToDictionary[index]

    /** Brings the index up to date with the providers; cheap when nothing changed. */
    fun refresh() {
        val words = userWords()
        if (words !== wordSnapshot || words.size != wordSnapshotSize) rebuildWords(words)
        val map = shortcuts()
        if (map !== shortcutSnapshot) rebuildShortcuts(map)
    }

    fun containsWord(key: String): Boolean = key in keySet

    /** Expansion whose trigger equals [key] (a lowercase key), or null. */
    fun shortcutFor(key: String): String? = shortcutByTrigger[key]

    private fun rebuildWords(words: Collection<String>) {
        wordSnapshot = words
        wordSnapshotSize = words.size
        val seen = LinkedHashMap<String, String>()
        for (w in words) {
            if (seen.size >= config.maxWords) break
            val trimmed = w.trim()
            if (trimmed.isNotEmpty()) seen.putIfAbsent(Text.key(trimmed), trimmed)
        }
        keys = seen.keys.toTypedArray()
        surfaces = seen.values.toTypedArray()
        keySet.clear()
        keySet.addAll(seen.keys)
        knownToDictionary =
            BooleanArray(keys.size) {
                val node = dict.findNode(keys[it])
                node >= 0 && dict.isTerminal(node)
            }
    }

    private fun rebuildShortcuts(map: Map<String, String>) {
        shortcutSnapshot = map
        shortcutByTrigger.clear()
        for ((trigger, expansion) in map) {
            if (trigger.isNotBlank() && expansion.isNotEmpty()) {
                shortcutByTrigger.putIfAbsent(Text.key(trigger.trim()), expansion)
            }
        }
    }
}
