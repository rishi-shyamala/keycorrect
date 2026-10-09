package dev.keycorrect.core.data

import kotlinx.coroutines.flow.Flow

/**
 * Immutable, lock-free view of the user dictionary for the decoder thread. Reading it never touches
 * SQLite. A new instance replaces the old one atomically whenever the store changes.
 */
class UserWordSnapshot(val words: Map<String, UserWord>) {
    operator fun contains(word: String): Boolean = word in words

    operator fun get(word: String): UserWord? = words[word]

    val size: Int
        get() = words.size

    companion object {
        val EMPTY = UserWordSnapshot(emptyMap())
    }
}

/** Immutable snapshot of shortcuts keyed by normalised trigger. */
class ShortcutSnapshot(val shortcuts: Map<String, Shortcut>) {
    /** Looks up by raw typed text; applies [Validation.normalizeTrigger]. */
    operator fun get(trigger: String): Shortcut? = shortcuts[Validation.normalizeTrigger(trigger)]

    val size: Int
        get() = shortcuts.size

    companion object {
        val EMPTY = ShortcutSnapshot(emptyMap())
    }
}

interface UserDictionaryStore {
    /**
     * Current in-memory view, safe to read from any thread on every keystroke. Empty until [load]
     * has completed (or the first write).
     */
    val snapshot: UserWordSnapshot

    /** Reads the table into [snapshot]. Call once at startup, off the main thread. */
    suspend fun load()

    /** Inserts or replaces [word] (keeps the original creation time). Returns the stored row. */
    suspend fun add(
        word: String,
        frequency: Int = Validation.DEFAULT_FREQUENCY,
        locale: String? = null,
    ): UserWord

    /** Updates an existing word. Returns false when [word] is not present. */
    suspend fun update(word: String, frequency: Int, locale: String? = null): Boolean

    /** Returns true when a row was removed. */
    suspend fun remove(word: String): Boolean

    suspend fun contains(word: String): Boolean

    /** Emits the full list (sorted by word) now and after every change. */
    fun all(): Flow<List<UserWord>>
}

interface ShortcutStore {
    val snapshot: ShortcutSnapshot

    suspend fun load()

    /** Inserts or replaces the shortcut for the normalised [trigger]. Returns the stored row. */
    suspend fun put(
        trigger: String,
        expansion: String,
        mode: ShortcutMode = ShortcutMode.SUGGEST,
    ): Shortcut

    suspend fun remove(trigger: String): Boolean

    suspend fun contains(trigger: String): Boolean

    suspend fun get(trigger: String): Shortcut?

    /** Emits the full list (sorted by trigger) now and after every change. */
    fun all(): Flow<List<Shortcut>>
}
