package dev.keycorrect.core.transfer

import kotlinx.serialization.Serializable

/*
 * The data model of a `.kcbackup` file (docs/TRANSFER.md). Plain data classes with no Android and
 * no core/data dependency; the app maps its stores to and from these. Every field added after
 * format 1 must have a default so older files still decode.
 */

/** The newest file format this build writes and reads. A larger number in a file is refused. */
const val FORMAT_VERSION = 1

/** The device a backup came from, or the one importing it. */
@Serializable
data class DeviceProfile(
    val model: String,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val densityDpi: Int,
    val smallestWidthDp: Int,
    val keyHeightDp: Int,
    val numberRow: Boolean,
)

@Serializable
data class TransferManifest(
    val formatVersion: Int,
    val appVersion: String,
    val createdAtMillis: Long,
    val device: DeviceProfile,
)

/** What a [SettingValue] holds. */
@Serializable
enum class SettingType {
    Bool,
    Int,
    Float,
    Text,
}

/** One typed setting, stored as text so the JSON is uniform. */
@Serializable
data class SettingValue(val type: SettingType, val value: String) {
    fun asBoolean(): Boolean? =
        if (type == SettingType.Bool) value.toBooleanStrictOrNull() else null

    fun asInt(): Int? = if (type == SettingType.Int) value.toIntOrNull() else null

    fun asFloat(): Float? = if (type == SettingType.Float) value.toFloatOrNull() else null

    fun asText(): String? = if (type == SettingType.Text) value else null

    companion object {
        fun of(v: Boolean) = SettingValue(SettingType.Bool, v.toString())

        fun of(v: Int) = SettingValue(SettingType.Int, v.toString())

        fun of(v: Float) = SettingValue(SettingType.Float, v.toString())

        fun of(v: String) = SettingValue(SettingType.Text, v)
    }
}

/**
 * Settings keyed by their preference-store key (e.g. `key_height_dp`). Unknown keys are carried
 * along; the app ignores the ones it does not know and sanitizes the rest.
 */
@Serializable
data class SettingsSection(val values: Map<String, SettingValue>) {
    /** The keys in this section that depend on the screen ([SCREEN_TIED_KEYS]). */
    val screenTiedKeys: Set<String>
        get() = values.keys.filterTo(HashSet()) { it in SCREEN_TIED_KEYS }

    companion object {
        /** Sizes that only make sense on the screen they were tuned for (decision T-4). */
        val SCREEN_TIED_KEYS: Set<String> =
            setOf(
                "key_height_dp",
                "edge_padding_dp",
                "key_gap_dp",
                "bottom_key_width",
                "mode_enter_key_width",
            )
    }
}

/** A word the user added or accepted (core/data `UserWord`); [frequency] is 1..255. */
@Serializable
data class DictWord(
    val word: String,
    val frequency: Int,
    val locale: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/**
 * The learning model's own user-word row (`UserWordRow`): [reason] is the ordinal of its add
 * reason, [removed] a tombstone (never auto-promoted again).
 */
@Serializable
data class LearnedWord(
    val key: String,
    val word: String,
    val reason: Int = 0,
    val createdAtMillis: Long = 0,
    val removed: Boolean = false,
    val preExisting: Boolean = false,
)

@Serializable data class BlockedWord(val word: String, val createdAtMillis: Long = 0)

/** A text shortcut; [mode] is `SUGGEST` or `AUTO_EXPAND`. */
@Serializable
data class ShortcutEntry(
    val trigger: String,
    val expansion: String,
    val mode: String = "SUGGEST",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class DictionarySection(
    val words: List<DictWord> = emptyList(),
    val learnedWords: List<LearnedWord> = emptyList(),
    val blockedWords: List<BlockedWord> = emptyList(),
    val shortcuts: List<ShortcutEntry> = emptyList(),
)

/** An n-gram of order 1..3; [key] is the words joined by a space. */
@Serializable
data class NgramEntry(
    val order: Int,
    val key: String,
    val weight: Double,
    val lastActiveMillis: Long = 0,
)

/** Correction memory; [bucket] "" is the global cell. */
@Serializable
data class CorrectionEntry(
    val typed: String,
    val target: String,
    val bucket: String = "",
    val score: Double,
    val lastActiveMillis: Long = 0,
)

@Serializable
data class SuppressionEntry(val typed: String, val target: String, val createdAtMillis: Long = 0)

/** A candidate new word the model is still gathering evidence for. */
@Serializable
data class CandidateEntry(
    val key: String,
    val word: String,
    val commitWeight: Double,
    val firstSeenMillis: Long = 0,
    val lastSeenMillis: Long = 0,
)

@Serializable data class CasingEntry(val key: String, val surface: String, val count: Double)

/** Undone-autocorrection counts; [corrected] "" is the count for the typed word overall. */
@Serializable
data class KeepEntry(
    val typed: String,
    val corrected: String = "",
    val count: Int,
    val firstMillis: Long = 0,
    val lastMillis: Long = 0,
)

/** A kept recent sentence (decision D10: 7 days, non-sensitive fields only). */
@Serializable
data class SentenceEntry(val sessionId: Long = 0, val text: String, val timeMillis: Long)

/** An autocorrect/phrase outcome (see core/learning `OutcomeRow`). */
@Serializable
data class OutcomeEntry(val kind: Int, val timeMillis: Long, val a: Int, val b: Int = 0)

@Serializable
data class EmojiRecent(val emoji: String, val base: String, val lastUsed: Long, val useCount: Int)

@Serializable
data class EmojiVariant(val base: String, val variant: String, val updatedAt: Long = 0)

@Serializable
data class LearningSection(
    /** The model's active clock; [NgramEntry.lastActiveMillis] and friends are relative to it. */
    val activeClockMillis: Long = 0,
    val ngrams: List<NgramEntry> = emptyList(),
    val corrections: List<CorrectionEntry> = emptyList(),
    val suppressions: List<SuppressionEntry> = emptyList(),
    val candidates: List<CandidateEntry> = emptyList(),
    val casing: List<CasingEntry> = emptyList(),
    val keeps: List<KeepEntry> = emptyList(),
    val sentences: List<SentenceEntry> = emptyList(),
    val outcomes: List<OutcomeEntry> = emptyList(),
    /** Live ranker weights (checkpoint -1), index order; empty when nothing was learned. */
    val rankerWeights: List<Double> = emptyList(),
    val rankerUpdates: Long = 0,
    val emojiRecents: List<EmojiRecent> = emptyList(),
    val emojiVariants: List<EmojiVariant> = emptyList(),
)

/** Decayed weighted sums for one key (`TouchRow`): mean x = [sumX] / [weight]. */
@Serializable
data class TouchKeyStats(
    /** The key's character as a UTF-16 code. */
    val char: Int,
    val samples: Int,
    val weight: Double,
    val sumX: Double,
    val sumY: Double,
    val sumXX: Double,
    val sumYY: Double,
    val lastActiveMillis: Long = 0,
)

/** One touch-statistics bucket, keyed by the layout's `InputProfile.touchKey`. */
@Serializable
data class TouchBucket(
    val touchKey: String,
    val words: Int = 0,
    val keys: List<TouchKeyStats> = emptyList(),
)

@Serializable data class TouchSection(val buckets: List<TouchBucket> = emptyList())

/** Everything in a backup. */
data class TransferBundle(
    val manifest: TransferManifest,
    val settings: SettingsSection,
    val dictionary: DictionarySection,
    val learning: LearningSection,
    val touch: TouchSection,
)
