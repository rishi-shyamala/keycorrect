package dev.keycorrect.transfer

import dev.keycorrect.core.data.EmojiHistoryDump
import dev.keycorrect.core.data.EmojiRecentRow
import dev.keycorrect.core.data.EmojiVariantRow
import dev.keycorrect.core.data.Shortcut
import dev.keycorrect.core.data.ShortcutMode
import dev.keycorrect.core.data.UserWord
import dev.keycorrect.core.learning.model.BlockRow
import dev.keycorrect.core.learning.model.CandidateWordRow
import dev.keycorrect.core.learning.model.CasingRow
import dev.keycorrect.core.learning.model.CorrectionRow
import dev.keycorrect.core.learning.model.KeepRow
import dev.keycorrect.core.learning.model.NgramRow
import dev.keycorrect.core.learning.model.OutcomeRow
import dev.keycorrect.core.learning.model.PersistedLearning
import dev.keycorrect.core.learning.model.RankerWeightRow
import dev.keycorrect.core.learning.model.SentenceRow
import dev.keycorrect.core.learning.model.SuppressionRow
import dev.keycorrect.core.learning.model.TouchProfileRow
import dev.keycorrect.core.learning.model.TouchRow
import dev.keycorrect.core.learning.model.UserWordRow
import dev.keycorrect.core.transfer.BlockedWord
import dev.keycorrect.core.transfer.CandidateEntry
import dev.keycorrect.core.transfer.CasingEntry
import dev.keycorrect.core.transfer.CorrectionEntry
import dev.keycorrect.core.transfer.DictWord
import dev.keycorrect.core.transfer.DictionarySection
import dev.keycorrect.core.transfer.EmojiRecent
import dev.keycorrect.core.transfer.EmojiVariant
import dev.keycorrect.core.transfer.KeepEntry
import dev.keycorrect.core.transfer.LearnedWord
import dev.keycorrect.core.transfer.LearningSection
import dev.keycorrect.core.transfer.NgramEntry
import dev.keycorrect.core.transfer.OutcomeEntry
import dev.keycorrect.core.transfer.SentenceEntry
import dev.keycorrect.core.transfer.SettingType
import dev.keycorrect.core.transfer.SettingValue
import dev.keycorrect.core.transfer.SettingsSection
import dev.keycorrect.core.transfer.ShortcutEntry
import dev.keycorrect.core.transfer.SuppressionEntry
import dev.keycorrect.core.transfer.TouchBucket
import dev.keycorrect.core.transfer.TouchKeyStats
import dev.keycorrect.core.transfer.TouchSection
import dev.keycorrect.core.transfer.TransferBundle
import dev.keycorrect.core.transfer.TransferManifest

/** The ranker's live weights are stored under this checkpoint time. */
private const val LIVE_RANKER = -1L

/**
 * Everything the backup covers, as the app's stores hold it. [settings] is the typed value per
 * stored key ([dev.keycorrect.core.settings.SettingsRepository.exportValues]).
 */
class DeviceState(
    val settings: Map<String, Any>,
    val words: List<UserWord>,
    val shortcuts: List<Shortcut>,
    val emoji: EmojiHistoryDump,
    val learning: PersistedLearning,
)

/**
 * Settings that are never exported or imported: incognito is a momentary mode, not a preference.
 */
internal val UNTRANSFERRED_SETTINGS: Set<String> = setOf("incognito")

/** Device state to the file's data model, and the pieces back (see docs/TRANSFER.md). */
@Suppress("TooManyFunctions") // One short mapping per kind of data.
internal object TransferMapper {
    fun toBundle(state: DeviceState, manifest: TransferManifest) =
        TransferBundle(
            manifest,
            settings(state.settings),
            dictionary(state.words, state.shortcuts, state.learning),
            learning(state.learning, state.emoji),
            touch(state.learning),
        )

    // ------------------------------------------------------------------ settings

    fun settings(values: Map<String, Any>): SettingsSection =
        SettingsSection(
            values
                .filterKeys { it !in UNTRANSFERRED_SETTINGS }
                .mapNotNull { (k, v) -> settingValue(v)?.let { k to it } }
                .toMap()
        )

    private fun settingValue(v: Any): SettingValue? =
        when (v) {
            is Boolean -> SettingValue.of(v)
            is Int -> SettingValue.of(v)
            is Float -> SettingValue.of(v)
            is String -> SettingValue.of(v)
            else -> null
        }

    /** The typed values for the settings repository; values that do not parse are dropped. */
    fun settingValues(map: Map<String, SettingValue>): Map<String, Any> =
        map.filterKeys { it !in UNTRANSFERRED_SETTINGS }
            .mapNotNull { (k, v) ->
                val typed: Any? =
                    when (v.type) {
                        SettingType.Bool -> v.asBoolean()
                        SettingType.Int -> v.asInt()
                        SettingType.Float -> v.asFloat()?.takeIf { it.isFinite() }
                        SettingType.Text -> v.asText()
                    }
                typed?.let { k to it }
            }
            .toMap()

    // ------------------------------------------------------------------ to the file

    fun dictionary(
        words: List<UserWord>,
        shortcuts: List<Shortcut>,
        l: PersistedLearning,
    ): DictionarySection =
        DictionarySection(
            words =
                words.map {
                    DictWord(it.word, it.frequency, it.locale, it.createdAt, it.updatedAt)
                },
            learnedWords =
                l.userWords.map {
                    LearnedWord(
                        it.key,
                        it.word,
                        it.reason,
                        it.createdAtMillis,
                        it.removed,
                        it.preExisting,
                    )
                },
            blockedWords = l.blocks.map { BlockedWord(it.word, it.createdAtMillis) },
            shortcuts =
                shortcuts.map {
                    ShortcutEntry(
                        it.trigger,
                        it.expansion,
                        it.mode.name,
                        it.createdAt,
                        it.updatedAt,
                    )
                },
        )

    fun learning(l: PersistedLearning, emoji: EmojiHistoryDump): LearningSection =
        LearningSection(
            activeClockMillis = l.activeClockMillis,
            ngrams = l.ngrams.map { NgramEntry(it.order, it.key, it.weight, it.lastActiveMillis) },
            corrections =
                l.corrections.map {
                    CorrectionEntry(it.typed, it.target, it.bucket, it.score, it.lastActiveMillis)
                },
            suppressions =
                l.suppressions.map { SuppressionEntry(it.typed, it.target, it.createdAtMillis) },
            candidates =
                l.candidates.map {
                    CandidateEntry(
                        it.key,
                        it.word,
                        it.commitWeight,
                        it.firstSeenMillis,
                        it.lastSeenMillis,
                    )
                },
            casing = l.casing.map { CasingEntry(it.key, it.surface, it.count) },
            keeps =
                l.keeps.map {
                    KeepEntry(it.typed, it.corrected, it.count, it.firstMillis, it.lastMillis)
                },
            sentences = l.sentences.map { SentenceEntry(it.sessionId, it.text, it.timeMillis) },
            outcomes = l.outcomes.map { OutcomeEntry(it.kind, it.timeMillis, it.a, it.b) },
            rankerWeights =
                l.rankerWeights
                    .filter { it.checkpointMillis == LIVE_RANKER }
                    .sortedBy { it.index }
                    .map { it.value },
            rankerUpdates = l.rankerUpdates,
            emojiRecents =
                emoji.recents.map { EmojiRecent(it.emoji, it.base, it.lastUsed, it.useCount) },
            emojiVariants = emoji.variants.map { EmojiVariant(it.base, it.variant, it.updatedAt) },
        )

    fun touch(l: PersistedLearning): TouchSection {
        val byKey = l.touch.groupBy { it.touchKey }
        val words = l.touchProfiles.associate { it.touchKey to it.words }
        return TouchSection(
            (byKey.keys + words.keys).sorted().map { key ->
                TouchBucket(
                    key,
                    words[key] ?: 0,
                    byKey[key].orEmpty().map {
                        TouchKeyStats(
                            it.char,
                            it.samples,
                            it.weight,
                            it.sumX,
                            it.sumY,
                            it.sumXX,
                            it.sumYY,
                            it.lastActiveMillis,
                        )
                    },
                )
            }
        )
    }

    // ------------------------------------------------------------------ from the file

    fun userWords(s: DictionarySection): List<UserWord> =
        s.words.map { UserWord(it.word, it.frequency, it.locale, it.createdAt, it.updatedAt) }

    fun shortcuts(s: DictionarySection): List<Shortcut> =
        s.shortcuts.map {
            Shortcut(
                it.trigger,
                it.expansion,
                ShortcutMode.entries.firstOrNull { m -> m.name == it.mode } ?: ShortcutMode.SUGGEST,
                it.createdAt,
                it.updatedAt,
            )
        }

    fun emoji(s: LearningSection) =
        EmojiHistoryDump(
            s.emojiRecents.map { EmojiRecentRow(it.emoji, it.base, it.lastUsed, it.useCount) },
            s.emojiVariants.map { EmojiVariantRow(it.base, it.variant, it.updatedAt) },
        )

    /**
     * Builds the learning rows from the file's sections on top of [base], which supplies what the
     * file does not carry (the clocks, ranker checkpoints, candidate sessions, pending adds). A
     * null [touch] keeps [base]'s touch data.
     */
    fun toRows(
        base: PersistedLearning,
        dictionary: DictionarySection,
        l: LearningSection,
        touch: TouchSection?,
        keepAuxiliary: Boolean,
    ): PersistedLearning {
        val live = l.rankerWeights.mapIndexed { i, v -> RankerWeightRow(LIVE_RANKER, i, v) }
        val unchangedRanker =
            live == base.rankerWeights.filter { it.checkpointMillis == LIVE_RANKER }
        val checkpoints =
            if (keepAuxiliary && unchangedRanker) {
                base.rankerWeights.filter { it.checkpointMillis != LIVE_RANKER }
            } else {
                emptyList()
            }
        val touchRows = touch?.let(::touchRows)
        return base.copy(
            rankerUpdates = l.rankerUpdates,
            ngrams = l.ngrams.map { NgramRow(it.order, it.key, it.weight, it.lastActiveMillis) },
            corrections =
                l.corrections.map {
                    CorrectionRow(it.typed, it.target, it.bucket, it.score, it.lastActiveMillis)
                },
            suppressions =
                l.suppressions.map { SuppressionRow(it.typed, it.target, it.createdAtMillis) },
            blocks = dictionary.blockedWords.map { BlockRow(it.word, it.createdAtMillis) },
            userWords =
                dictionary.learnedWords.map {
                    UserWordRow(
                        it.key,
                        it.word,
                        it.reason,
                        it.createdAtMillis,
                        it.removed,
                        it.preExisting,
                    )
                },
            candidates =
                l.candidates.map {
                    CandidateWordRow(
                        it.key,
                        it.word,
                        it.commitWeight,
                        it.firstSeenMillis,
                        it.lastSeenMillis,
                    )
                },
            candidateSessions = if (keepAuxiliary) base.candidateSessions else emptyList(),
            casing = l.casing.map { CasingRow(it.key, it.surface, it.count) },
            touch = touchRows?.first ?: base.touch,
            touchProfiles = touchRows?.second ?: base.touchProfiles,
            rankerWeights = live + checkpoints,
            outcomes = l.outcomes.map { OutcomeRow(it.kind, it.timeMillis, it.a, it.b) },
            sentences = l.sentences.map { SentenceRow(it.sessionId, it.text, it.timeMillis) },
            pendingAdds = if (keepAuxiliary) base.pendingAdds else emptyList(),
            keeps =
                l.keeps.map {
                    KeepRow(it.typed, it.corrected, it.count, it.firstMillis, it.lastMillis)
                },
        )
    }

    private fun touchRows(t: TouchSection): Pair<List<TouchRow>, List<TouchProfileRow>> =
        t.buckets.flatMap { b ->
            b.keys.map {
                TouchRow(
                    b.touchKey,
                    it.char,
                    it.samples,
                    it.weight,
                    it.sumX,
                    it.sumY,
                    it.sumXX,
                    it.sumYY,
                    it.lastActiveMillis,
                )
            }
        } to t.buckets.filter { it.words > 0 }.map { TouchProfileRow(it.touchKey, it.words) }
}
