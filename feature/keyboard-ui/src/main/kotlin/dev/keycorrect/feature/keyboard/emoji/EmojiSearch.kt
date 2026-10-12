package dev.keycorrect.feature.keyboard.emoji

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.keycorrect.core.emoji.Emoji
import dev.keycorrect.core.emoji.EmojiCatalog

/**
 * The emoji search (docs/PHASE4.md, PR B): while [active] the letter keys edit [query] instead of
 * the app's text. Only ever held in memory: nothing typed here is committed, decoded or learned.
 */
internal class EmojiSearch {
    var active: Boolean by mutableStateOf(false)
        private set

    var query: String by mutableStateOf("")
        private set

    /** Starts a search with an empty query. */
    fun open() {
        query = ""
        active = true
    }

    /** Ends the search and forgets the query. */
    fun close() {
        active = false
        query = ""
    }

    /** Appends typed [text] to the query (control characters and leading spaces are ignored). */
    fun type(text: String) {
        if (!active) return
        val clean = text.filterNot { it.isISOControl() }
        if (query.isEmpty() && clean.isBlank()) return
        query = (query + clean).take(MAX_QUERY)
    }

    /** Removes the last character (code point) of the query. */
    fun backspace() {
        if (query.isEmpty()) return
        query = query.substring(0, query.offsetByCodePoints(query.length, -1))
    }

    /** The emoji matching the query: nothing for a blank query or without a [catalog]. */
    fun results(catalog: EmojiCatalog?, limit: Int = RESULT_LIMIT): List<Emoji> {
        val q = query.trim()
        if (catalog == null || q.isEmpty()) return emptyList()
        return catalog.search(q, limit)
    }

    companion object {
        const val MAX_QUERY = 40
        const val RESULT_LIMIT = 40
    }
}

/** Which tab of the emoji panel is open: a [EmojiPanelState.RECENTS], group name or kaomoji key. */
internal class EmojiPanelState {
    /** The selected tab's key; null until the user chooses (then Recents, or Smileys if empty). */
    var tab: String? by mutableStateOf(null)

    companion object {
        const val RECENTS = "recents"
        const val KAOMOJI = "kaomoji"
    }
}
