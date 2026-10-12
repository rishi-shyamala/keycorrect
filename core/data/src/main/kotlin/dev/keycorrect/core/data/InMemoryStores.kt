package dev.keycorrect.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/*
 * In-memory implementations of the stores for JVM tests in other modules (decoder, input,
 * settings UI, eval harness). They live in src/main rather than src/test because Gradle does not
 * expose one module's test sources to another, and AGP test fixtures would need an experimental
 * flag plus extra wiring in every consumer. They are tiny and unreferenced by production code,
 * so R8 removes them from release builds. They share Validation with the SQL stores, so tests
 * written against them see the same errors and normalisation.
 */

class InMemoryUserDictionaryStore(private val clock: () -> Long = System::currentTimeMillis) :
    UserDictionaryStore {
    private val state = MutableStateFlow<Map<String, UserWord>>(emptyMap())

    override val snapshot: UserWordSnapshot
        get() = UserWordSnapshot(state.value)

    /** Nothing to load; present for interface parity. */
    override suspend fun load() = Unit

    override suspend fun add(word: String, frequency: Int, locale: String?): UserWord {
        Validation.requireValidWord(word, frequency)
        val key = Validation.normalizeWord(word)
        val now = clock()
        var result: UserWord? = null
        state.update { current ->
            val created = current[key]?.createdAt ?: now
            val row = UserWord(key, frequency, Validation.normalizeLocale(locale), created, now)
            result = row
            current + (key to row)
        }
        return result!!
    }

    override suspend fun update(word: String, frequency: Int, locale: String?): Boolean {
        Validation.requireValidWord(word, frequency)
        val key = Validation.normalizeWord(word)
        val now = clock()
        var changed = false
        state.update { current ->
            val old = current[key]
            changed = old != null
            if (old == null) current
            else
                current +
                    (key to
                        old.copy(
                            frequency = frequency,
                            locale = Validation.normalizeLocale(locale),
                            updatedAt = now,
                        ))
        }
        return changed
    }

    override suspend fun remove(word: String): Boolean {
        val key = Validation.normalizeWord(word)
        var changed = false
        state.update { current ->
            changed = key in current
            current - key
        }
        return changed
    }

    override suspend fun contains(word: String): Boolean =
        Validation.normalizeWord(word) in state.value

    override fun all(): Flow<List<UserWord>> = state.map { it.values.sortedBy(UserWord::word) }
}

class InMemoryShortcutStore(private val clock: () -> Long = System::currentTimeMillis) :
    ShortcutStore {
    private val state = MutableStateFlow<Map<String, Shortcut>>(emptyMap())

    override val snapshot: ShortcutSnapshot
        get() = ShortcutSnapshot(state.value)

    override suspend fun load() = Unit

    override suspend fun put(trigger: String, expansion: String, mode: ShortcutMode): Shortcut {
        Validation.requireValidShortcut(trigger, expansion)
        val key = Validation.normalizeTrigger(trigger)
        val now = clock()
        var result: Shortcut? = null
        state.update { current ->
            val created = current[key]?.createdAt ?: now
            val row = Shortcut(key, expansion, mode, created, now)
            result = row
            current + (key to row)
        }
        return result!!
    }

    override suspend fun remove(trigger: String): Boolean {
        val key = Validation.normalizeTrigger(trigger)
        var changed = false
        state.update { current ->
            changed = key in current
            current - key
        }
        return changed
    }

    override suspend fun contains(trigger: String): Boolean =
        Validation.normalizeTrigger(trigger) in state.value

    override suspend fun get(trigger: String): Shortcut? =
        state.value[Validation.normalizeTrigger(trigger)]

    override fun all(): Flow<List<Shortcut>> = state.map { it.values.sortedBy(Shortcut::trigger) }
}

class InMemoryEmojiHistoryStore(private val clock: () -> Long = System::currentTimeMillis) :
    EmojiHistoryStore {
    private class Row(val base: String, val lastUsed: Long, val useCount: Int)

    private class State(
        val recents: Map<String, Row> = emptyMap(),
        val variants: Map<String, Pair<String, Long>> = emptyMap(),
    )

    private val state = MutableStateFlow(State())

    override suspend fun recents(limit: Int): List<String> =
        state.value.recents.entries
            .sortedByDescending { it.value.lastUsed }
            .take(limit.coerceIn(0, EmojiHistoryStore.MAX_RECENTS))
            .map { it.key }

    override suspend fun recordUse(emoji: String, base: String) {
        if (!EmojiHistoryStore.isValid(emoji) || !EmojiHistoryStore.isValid(base)) return
        val now = clock()
        state.update { s ->
            // Strictly increasing so "most recent first" holds even within one millisecond.
            val stamp =
                maxOf(now, (s.recents.values.maxOfOrNull { it.lastUsed } ?: Long.MIN_VALUE) + 1)
            val count = (s.recents[emoji]?.useCount ?: 0) + 1
            val grown = s.recents + (emoji to Row(base, stamp, count))
            val kept =
                grown.entries
                    .sortedByDescending { it.value.lastUsed }
                    .take(EmojiHistoryStore.MAX_RECENTS)
                    .associate { it.key to it.value }
            State(kept, s.variants)
        }
    }

    override suspend fun preferredVariant(base: String): String? = state.value.variants[base]?.first

    override suspend fun preferredVariants(): Map<String, String> =
        state.value.variants.mapValues { it.value.first }

    override suspend fun setPreferredVariant(base: String, variant: String?) {
        if (!EmojiHistoryStore.isValid(base)) return
        if (variant != null && !EmojiHistoryStore.isValid(variant)) return
        val now = clock()
        state.update { s ->
            State(
                s.recents,
                if (variant == null) s.variants - base else s.variants + (base to (variant to now)),
            )
        }
    }

    override suspend fun clearSince(sinceMillis: Long) {
        state.update { s ->
            State(
                s.recents.filterValues { it.lastUsed < sinceMillis },
                s.variants.filterValues { it.second < sinceMillis },
            )
        }
    }

    override suspend fun clear() {
        state.value = State()
    }
}
