package dev.keycorrect.feature.keyboard.prediction

import dev.keycorrect.core.engineapi.TouchPoint

/** How a word ended up in the text. */
enum class CommitMethod {
    /** Space, punctuation or Enter, typed as it was. */
    Typed,
    Autocorrect,
    Expansion,
    SuggestionTap,
    PhraseTap,
    LiteralTap,
}

/**
 * One finished word and the touches that produced it, for Phase 3's touch-model learning (§6.2).
 * [reverted] is set when the user undid the autocorrect with backspace (§6.3 rule 6).
 */
class WordRecord(
    val typed: String,
    val committed: String,
    /** One entry per char of [typed]; null where the char didn't come from a single tap. */
    val touches: List<TouchPoint?>,
    /** Id of the [dev.keycorrect.core.engineapi.KeyLayoutModel] the touches are relative to. */
    val layoutId: Long,
    val method: CommitMethod,
    val reverted: Boolean = false,
)

/**
 * Fixed-size, in-memory ring of the most recent [WordRecord]s, oldest dropped first. Nothing is
 * persisted; the owner only records in fields where learning is allowed and clears it when the
 * keyboard goes away. Main-thread only.
 */
class WordTouchLog(val capacity: Int = DEFAULT_CAPACITY) {
    private val ring = arrayOfNulls<WordRecord>(capacity)
    private var next = 0

    var size = 0
        private set

    init {
        require(capacity > 0)
    }

    fun record(word: WordRecord) {
        ring[next] = word
        next = (next + 1) % capacity
        if (size < capacity) size++
    }

    /** Marks the most recent record as reverted if it is for [committed]. */
    fun markLastReverted(committed: String) {
        if (size == 0) return
        val index = (next - 1 + capacity) % capacity
        val last = ring[index] ?: return
        if (last.committed != committed || last.reverted) return
        ring[index] =
            WordRecord(last.typed, last.committed, last.touches, last.layoutId, last.method, true)
    }

    /** The records, oldest first. */
    fun snapshot(): List<WordRecord> =
        List(size) { ring[(next - size + it + capacity) % capacity]!! }

    fun clear() {
        ring.fill(null)
        next = 0
        size = 0
    }

    companion object {
        const val DEFAULT_CAPACITY = 64
    }
}
