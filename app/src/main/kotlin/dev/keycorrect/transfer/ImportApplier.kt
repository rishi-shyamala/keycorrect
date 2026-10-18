package dev.keycorrect.transfer

import dev.keycorrect.core.data.EmojiHistoryDump
import dev.keycorrect.core.data.EmojiHistoryStore
import dev.keycorrect.core.data.Shortcut
import dev.keycorrect.core.data.UserWord
import dev.keycorrect.core.data.Validation
import dev.keycorrect.core.learning.model.PersistedLearning
import dev.keycorrect.core.learning.model.PersonalModelConfig
import dev.keycorrect.core.learning.model.decayFactor
import dev.keycorrect.core.transfer.DictionarySection
import dev.keycorrect.core.transfer.ImportMode
import dev.keycorrect.core.transfer.ImportPlan
import dev.keycorrect.core.transfer.LearningSection
import dev.keycorrect.core.transfer.Merge
import dev.keycorrect.core.transfer.SettingsSection
import dev.keycorrect.core.transfer.TouchSection
import dev.keycorrect.core.transfer.TransferBundle

/** What an import changes, worked out without touching any store. */
@Suppress("LongParameterList") // A flat result value.
class ImportOutcome(
    val words: List<UserWord>,
    val shortcuts: List<Shortcut>,
    val emoji: EmojiHistoryDump,
    val learning: PersistedLearning,
    /** The settings to write (screen-tied ones removed when they are skipped). */
    val settings: Map<String, Any>,
    /** Settings that keep this device's value however the import goes. */
    val keepLocalSettings: Set<String>,
    /** Whether the settings not in the file return to their defaults. */
    val replaceSettings: Boolean,
    val counts: TransferCounts,
)

/** How much of each kind the file holds or an import brought in. */
data class TransferCounts(
    val settings: Int = 0,
    val words: Int = 0,
    val learnedWords: Int = 0,
    val blockedWords: Int = 0,
    val shortcuts: Int = 0,
    val ngrams: Int = 0,
    val corrections: Int = 0,
    val sentences: Int = 0,
    val emoji: Int = 0,
    val touchKeys: Int = 0,
) {
    companion object {
        fun of(b: TransferBundle, includeTouch: Boolean = true) =
            TransferCounts(
                settings = b.settings.values.keys.count { it !in UNTRANSFERRED_SETTINGS },
                words = b.dictionary.words.size,
                learnedWords = b.dictionary.learnedWords.count { !it.removed },
                blockedWords = b.dictionary.blockedWords.size,
                shortcuts = b.dictionary.shortcuts.size,
                ngrams = b.learning.ngrams.size,
                corrections = b.learning.corrections.size,
                sentences = b.learning.sentences.size,
                emoji = b.learning.emojiRecents.size,
                touchKeys = if (includeTouch) b.touch.buckets.sumOf { it.keys.size } else 0,
            )
    }
}

/** Merge and Replace as pure functions of the device's state and the file (decision T-3, T-4). */
internal object ImportApplier {
    /**
     * Applies [bundle] to [current] as [plan] says. N-gram, correction and touch times are on each
     * device's own active clock, so the file's are rebased onto this device's clock first: an entry
     * [age] ms old on the exporting device is [age] ms old here, and decay carries on.
     */
    fun apply(current: DeviceState, bundle: TransferBundle, plan: ImportPlan): ImportOutcome {
        val clock = current.learning.activeClockMillis
        val dictionary = sanitize(bundle.dictionary)
        val learning = rebase(sanitize(bundle.learning), clock)
        val touch =
            if (plan.includesScreenTied)
                rebase(bundle.touch, bundle.learning.activeClockMillis, clock)
            else null
        val localDict =
            TransferMapper.dictionary(current.words, current.shortcuts, current.learning)
        val localLearning = TransferMapper.learning(current.learning, current.emoji)

        val replace = plan.mode == ImportMode.Replace
        val dict = if (replace) dictionary else Merge.dictionary(localDict, dictionary)
        val learn = if (replace) learning else Merge.learning(localLearning, learning, aging)
        val mergedTouch =
            when {
                touch == null -> null
                replace -> touch
                else -> Merge.touch(TransferMapper.touch(current.learning), touch, aging)
            }
        // Replace starts the rows from the clocks only; Merge keeps the rest of this device's.
        val base =
            if (replace) {
                PersistedLearning(
                    activeClockMillis = current.learning.activeClockMillis,
                    lastWallMillis = current.learning.lastWallMillis,
                    lastCheckpointMillis = current.learning.lastCheckpointMillis,
                    touch = if (touch == null) current.learning.touch else emptyList(),
                    touchProfiles =
                        if (touch == null) current.learning.touchProfiles else emptyList(),
                )
            } else {
                current.learning
            }
        val rows = TransferMapper.toRows(base, dict, learn, mergedTouch, keepAuxiliary = !replace)
        val settings = TransferMapper.settingValues(plan.settingsToApply(bundle.settings))
        // Settings this device keeps whatever the file says: the ones a backup never carries
        // (incognito) always, and the screen-tied sizes when they are skipped. Without this,
        // Replace would reset them to their defaults.
        val keep =
            if (plan.includesScreenTied) UNTRANSFERRED_SETTINGS
            else UNTRANSFERRED_SETTINGS + SettingsSection.SCREEN_TIED_KEYS
        return ImportOutcome(
            words = TransferMapper.userWords(dict).sortedBy { it.word },
            shortcuts = TransferMapper.shortcuts(dict).sortedBy { it.trigger },
            emoji = TransferMapper.emoji(learn),
            learning = rows,
            settings = settings,
            keepLocalSettings = keep,
            replaceSettings = replace,
            counts =
                TransferCounts.of(bundle, includeTouch = plan.includesScreenTied)
                    .copy(settings = settings.size),
        )
    }

    /** Merged weights decay as the learning model's own do (half-lives from its config). */
    private val aging =
        PersonalModelConfig().let { cfg ->
            Merge.Aging(
                evidence = { decayFactor(cfg.halfLifeMillis, it) },
                touch = { decayFactor(cfg.touchHalfLifeMillis, it) },
            )
        }

    // ------------------------------------------------------------------ clock rebase

    /** [LearningSection] with n-gram and correction times moved onto [targetClock]. */
    fun rebase(l: LearningSection, targetClock: Long): LearningSection {
        val source = l.activeClockMillis
        return l.copy(
            activeClockMillis = targetClock,
            ngrams =
                l.ngrams.map {
                    it.copy(lastActiveMillis = move(it.lastActiveMillis, source, targetClock))
                },
            corrections =
                l.corrections.map {
                    it.copy(lastActiveMillis = move(it.lastActiveMillis, source, targetClock))
                },
        )
    }

    fun rebase(t: TouchSection, sourceClock: Long, targetClock: Long): TouchSection =
        TouchSection(
            t.buckets
                .distinctBy { it.touchKey }
                .map { b ->
                    b.copy(
                        keys =
                            b.keys
                                .distinctBy { it.char }
                                .map {
                                    it.copy(
                                        lastActiveMillis =
                                            move(it.lastActiveMillis, sourceClock, targetClock)
                                    )
                                }
                    )
                }
        )

    /** The age on the source clock, laid back from the target clock. Never in the future. */
    private fun move(last: Long, source: Long, target: Long): Long =
        target - (source - last).coerceAtLeast(0L)

    // ------------------------------------------------------------------ sanitizing

    /** Drops entries the stores would refuse, so one bad entry cannot fail the whole import. */
    private fun sanitize(d: DictionarySection): DictionarySection =
        d.copy(
            words =
                d.words
                    .mapNotNull { w ->
                        val word = Validation.normalizeWord(w.word)
                        val freq =
                            w.frequency.coerceIn(Validation.MIN_FREQUENCY, Validation.MAX_FREQUENCY)
                        if (Validation.checkWord(word, freq) != null) null
                        else
                            w.copy(
                                word = word,
                                frequency = freq,
                                locale = Validation.normalizeLocale(w.locale),
                            )
                    }
                    .distinctBy { it.word },
            shortcuts =
                d.shortcuts
                    .filter { Validation.checkShortcut(it.trigger, it.expansion) == null }
                    .map { it.copy(trigger = Validation.normalizeTrigger(it.trigger)) }
                    .distinctBy { it.trigger },
            learnedWords =
                d.learnedWords
                    .filter { it.key.isNotBlank() && it.word.isNotBlank() }
                    .distinctBy { it.key },
            blockedWords = d.blockedWords.filter { it.word.isNotBlank() }.distinctBy { it.word },
        )

    private fun sanitize(l: LearningSection): LearningSection =
        l.copy(
            ngrams =
                l.ngrams
                    .filter {
                        it.order in 1..MAX_ORDER && it.key.isNotBlank() && it.weight.isFinite()
                    }
                    .distinctBy { it.order to it.key },
            corrections =
                l.corrections
                    .filter { it.score.isFinite() && it.typed.isNotBlank() }
                    .distinctBy { Triple(it.typed, it.target, it.bucket) },
            suppressions = l.suppressions.distinctBy { it.typed to it.target },
            candidates = l.candidates.filter { it.commitWeight.isFinite() }.distinctBy { it.key },
            casing = l.casing.filter { it.count.isFinite() }.distinctBy { it.key to it.surface },
            keeps = l.keeps.distinctBy { it.typed to it.corrected },
            rankerWeights =
                if (l.rankerWeights.all { it.isFinite() }) l.rankerWeights else emptyList(),
            emojiRecents =
                l.emojiRecents
                    .filter { valid(it.emoji) && valid(it.base) }
                    .distinctBy { it.emoji }
                    .sortedByDescending { it.lastUsed }
                    .take(EmojiHistoryStore.MAX_RECENTS),
            emojiVariants =
                l.emojiVariants
                    .filter { valid(it.base) && valid(it.variant) }
                    .distinctBy { it.base },
        )

    private fun valid(v: String) = v.isNotBlank() && v.length <= EmojiHistoryStore.MAX_VALUE_LENGTH

    private const val MAX_ORDER = 3
}
