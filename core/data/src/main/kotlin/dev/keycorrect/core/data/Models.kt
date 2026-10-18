package dev.keycorrect.core.data

import java.util.Locale

/** A word the user added or the keyboard learned. [word] keeps the user's casing. */
data class UserWord(
    val word: String,
    val frequency: Int,
    /** BCP-47 language tag, or null when the word applies to every locale. */
    val locale: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** One emoji recent with its bookkeeping, for backup and restore. */
data class EmojiRecentRow(
    val emoji: String,
    val base: String,
    val lastUsed: Long,
    val useCount: Int,
)

/** A remembered variant choice with the time it was set. */
data class EmojiVariantRow(val base: String, val variant: String, val updatedAt: Long)

/** The whole emoji history (recents and variant choices) as plain rows. */
data class EmojiHistoryDump(
    val recents: List<EmojiRecentRow> = emptyList(),
    val variants: List<EmojiVariantRow> = emptyList(),
)

enum class ShortcutMode {
    /** The expansion appears as a chip on the suggestion strip. */
    SUGGEST,

    /** The trigger is replaced by the expansion when space is typed. */
    AUTO_EXPAND,
}

/**
 * A text-expansion shortcut. [expansion] may contain the variables `{date}`, `{time}`,
 * `{clipboard}` and `{cursor}` verbatim; they are resolved by the input layer, never here.
 */
data class Shortcut(
    val trigger: String,
    val expansion: String,
    val mode: ShortcutMode,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Why an entry was rejected. Pure data so UI can map it to a localized message. */
enum class ValidationError {
    EMPTY_WORD,
    WORD_CONTAINS_WHITESPACE,
    WORD_TOO_LONG,
    FREQUENCY_OUT_OF_RANGE,
    EMPTY_TRIGGER,
    TRIGGER_CONTAINS_WHITESPACE,
    TRIGGER_TOO_LONG,
    EMPTY_EXPANSION,
    EXPANSION_TOO_LONG,
}

/** Thrown by store mutators when an entry fails [Validation]. */
class InvalidEntryException(val error: ValidationError) :
    IllegalArgumentException("Invalid entry: $error")

/** Pure validation and normalisation shared by the SQL stores and the in-memory fakes. */
object Validation {
    const val DEFAULT_FREQUENCY = 128
    const val MIN_FREQUENCY = 1
    const val MAX_FREQUENCY = 255
    const val MAX_WORD_LENGTH = 64
    const val MAX_TRIGGER_LENGTH = 64
    const val MAX_EXPANSION_LENGTH = 1000

    /** Words are trimmed but keep their casing (proper nouns, acronyms). */
    fun normalizeWord(raw: String): String = raw.trim()

    /** Triggers are matched case-insensitively, so they are stored trimmed and lowercase. */
    fun normalizeTrigger(raw: String): String = raw.trim().lowercase(Locale.ROOT)

    /** Blank locales mean "all locales". */
    fun normalizeLocale(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    fun checkWord(raw: String, frequency: Int = DEFAULT_FREQUENCY): ValidationError? {
        val word = normalizeWord(raw)
        return when {
            word.isEmpty() -> ValidationError.EMPTY_WORD
            word.any(Char::isWhitespace) -> ValidationError.WORD_CONTAINS_WHITESPACE
            word.length > MAX_WORD_LENGTH -> ValidationError.WORD_TOO_LONG
            frequency !in MIN_FREQUENCY..MAX_FREQUENCY -> ValidationError.FREQUENCY_OUT_OF_RANGE
            else -> null
        }
    }

    fun checkShortcut(rawTrigger: String, expansion: String): ValidationError? {
        val trigger = normalizeTrigger(rawTrigger)
        return when {
            trigger.isEmpty() -> ValidationError.EMPTY_TRIGGER
            trigger.any(Char::isWhitespace) -> ValidationError.TRIGGER_CONTAINS_WHITESPACE
            trigger.length > MAX_TRIGGER_LENGTH -> ValidationError.TRIGGER_TOO_LONG
            expansion.isEmpty() -> ValidationError.EMPTY_EXPANSION
            expansion.length > MAX_EXPANSION_LENGTH -> ValidationError.EXPANSION_TOO_LONG
            else -> null
        }
    }

    internal fun requireValidWord(raw: String, frequency: Int) {
        checkWord(raw, frequency)?.let { throw InvalidEntryException(it) }
    }

    internal fun requireValidShortcut(trigger: String, expansion: String) {
        checkShortcut(trigger, expansion)?.let { throw InvalidEntryException(it) }
    }
}
