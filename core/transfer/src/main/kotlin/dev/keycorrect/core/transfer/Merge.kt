package dev.keycorrect.core.transfer

import java.util.Locale

/*
 * Pure merge rules for "Merge" imports (decision T-3): union the entries, add counts, this
 * device's data wins on conflicts that are not counts, and a blocked word stays blocked. Replace
 * needs no merge: the app wipes and takes the file's section as is.
 */

@Suppress("TooManyFunctions") // One short function per kind of data.
object Merge {
    private const val MAX_FREQUENCY = 255
    private const val MAX_EMOJI_RECENTS = 64

    private fun lower(s: String) = s.lowercase(Locale.ROOT)

    /**
     * How much of a decayed value is left after an elapsed active-clock time (1.0 for none). The
     * app passes the learning model's own decay, so merged weights age exactly as they would have
     * on one device: [evidence] for n-grams and corrections, [touch] for touch statistics.
     */
    class Aging(val evidence: (elapsedMillis: Long) -> Double, val touch: (Long) -> Double)

    /**
     * Union of the dictionaries. Words match by exact text only (the store's primary key is the
     * word, so one word cannot exist twice in two locales); frequencies add (capped at 255), the
     * earliest creation and latest update are kept, this device's locale wins. Words that are
     * blocked on either side (case-insensitively) are dropped; shortcuts keep this device's
     * expansion on a conflicting trigger.
     */
    fun dictionary(existing: DictionarySection, incoming: DictionarySection): DictionarySection {
        val blocked = blocked(existing.blockedWords, incoming.blockedWords)
        val blockedKeys = blocked.mapTo(HashSet()) { lower(it.word) }
        return DictionarySection(
            words = words(existing.words, incoming.words).filter { lower(it.word) !in blockedKeys },
            learnedWords = learnedWords(existing.learnedWords, incoming.learnedWords),
            blockedWords = blocked,
            shortcuts = shortcuts(existing.shortcuts, incoming.shortcuts),
        )
    }

    fun words(existing: List<DictWord>, incoming: List<DictWord>): List<DictWord> {
        val out = LinkedHashMap<String, DictWord>()
        for (w in existing) out[w.word] = w
        for (w in incoming) {
            val e = out[w.word]
            out[w.word] =
                if (e == null) w
                else
                    e.copy(
                        frequency = (e.frequency + w.frequency).coerceAtMost(MAX_FREQUENCY),
                        createdAt = earliest(e.createdAt, w.createdAt),
                        updatedAt = maxOf(e.updatedAt, w.updatedAt),
                    )
        }
        return out.values.toList()
    }

    /** A removed tombstone on either side wins: the word stays removed. */
    fun learnedWords(existing: List<LearnedWord>, incoming: List<LearnedWord>): List<LearnedWord> {
        val out = LinkedHashMap<String, LearnedWord>()
        for (w in existing) out[w.key] = w
        for (w in incoming) {
            val e = out[w.key]
            out[w.key] =
                if (e == null) w
                else
                    e.copy(
                        removed = e.removed || w.removed,
                        createdAtMillis = earliest(e.createdAtMillis, w.createdAtMillis),
                    )
        }
        return out.values.toList()
    }

    /** Union by case-insensitive word; the earliest block time is kept. */
    fun blocked(existing: List<BlockedWord>, incoming: List<BlockedWord>): List<BlockedWord> {
        val out = LinkedHashMap<String, BlockedWord>()
        for (b in existing + incoming) {
            val k = lower(b.word)
            val e = out[k]
            out[k] =
                if (e == null) b
                else e.copy(createdAtMillis = earliest(e.createdAtMillis, b.createdAtMillis))
        }
        return out.values.toList()
    }

    /** Union by trigger (case-insensitive); [replace] lets the incoming one win a conflict. */
    fun shortcuts(
        existing: List<ShortcutEntry>,
        incoming: List<ShortcutEntry>,
        replace: Boolean = false,
    ): List<ShortcutEntry> {
        val out = LinkedHashMap<String, ShortcutEntry>()
        for (s in existing) out[lower(s.trigger)] = s
        for (s in incoming) {
            val k = lower(s.trigger)
            if (replace || k !in out) out[k] = s
        }
        return out.values.toList()
    }

    /**
     * Learned typing data. N-gram weights, correction scores, keeps, casing and candidate weights
     * add; suppressions, outcomes and sentences union; the ranker keeps whichever side learned
     * more. Times are max/min as fits. N-gram and correction times are on each device's own active
     * clock (the app rebases the incoming ones first); two entries are first decayed to the later
     * of their times by [aging], so older evidence counts for less before the weights are added.
     */
    fun learning(
        existing: LearningSection,
        incoming: LearningSection,
        aging: Aging,
    ): LearningSection =
        LearningSection(
            activeClockMillis = maxOf(existing.activeClockMillis, incoming.activeClockMillis),
            ngrams = ngrams(existing.ngrams, incoming.ngrams, aging),
            corrections = corrections(existing.corrections, incoming.corrections, aging),
            suppressions =
                (existing.suppressions + incoming.suppressions).distinctBy {
                    lower(it.typed) to lower(it.target)
                },
            candidates = candidates(existing.candidates, incoming.candidates),
            casing = casing(existing.casing, incoming.casing),
            keeps = keeps(existing.keeps, incoming.keeps),
            sentences = sentences(existing.sentences, incoming.sentences),
            outcomes =
                (existing.outcomes + incoming.outcomes).distinct().sortedBy { it.timeMillis },
            rankerWeights =
                if (incoming.rankerUpdates > existing.rankerUpdates) incoming.rankerWeights
                else existing.rankerWeights,
            rankerUpdates = maxOf(existing.rankerUpdates, incoming.rankerUpdates),
            emojiRecents = emojiRecents(existing.emojiRecents, incoming.emojiRecents),
            emojiVariants = emojiVariants(existing.emojiVariants, incoming.emojiVariants),
        )

    fun ngrams(
        existing: List<NgramEntry>,
        incoming: List<NgramEntry>,
        aging: Aging,
    ): List<NgramEntry> =
        combine(existing, incoming, { it.order to it.key }) { a, b ->
            val last = maxOf(a.lastActiveMillis, b.lastActiveMillis)
            a.copy(
                weight =
                    a.weight * aging.evidence(last - a.lastActiveMillis) +
                        b.weight * aging.evidence(last - b.lastActiveMillis),
                lastActiveMillis = last,
            )
        }

    fun corrections(
        existing: List<CorrectionEntry>,
        incoming: List<CorrectionEntry>,
        aging: Aging,
    ): List<CorrectionEntry> =
        combine(existing, incoming, { Triple(it.typed, it.target, it.bucket) }) { a, b ->
            val last = maxOf(a.lastActiveMillis, b.lastActiveMillis)
            a.copy(
                score =
                    a.score * aging.evidence(last - a.lastActiveMillis) +
                        b.score * aging.evidence(last - b.lastActiveMillis),
                lastActiveMillis = last,
            )
        }

    fun candidates(
        existing: List<CandidateEntry>,
        incoming: List<CandidateEntry>,
    ): List<CandidateEntry> =
        combine(existing, incoming, { it.key }) { a, b ->
            a.copy(
                commitWeight = a.commitWeight + b.commitWeight,
                firstSeenMillis = earliest(a.firstSeenMillis, b.firstSeenMillis),
                lastSeenMillis = maxOf(a.lastSeenMillis, b.lastSeenMillis),
            )
        }

    fun casing(existing: List<CasingEntry>, incoming: List<CasingEntry>): List<CasingEntry> =
        combine(existing, incoming, { it.key to it.surface }) { a, b ->
            a.copy(count = a.count + b.count)
        }

    fun keeps(existing: List<KeepEntry>, incoming: List<KeepEntry>): List<KeepEntry> =
        combine(existing, incoming, { it.typed to it.corrected }) { a, b ->
            a.copy(
                count = a.count + b.count,
                firstMillis = earliest(a.firstMillis, b.firstMillis),
                lastMillis = maxOf(a.lastMillis, b.lastMillis),
            )
        }

    /** Union by text, oldest first. Session ids are per device, so they are not compared. */
    fun sentences(
        existing: List<SentenceEntry>,
        incoming: List<SentenceEntry>,
    ): List<SentenceEntry> = (existing + incoming).distinctBy { it.text }.sortedBy { it.timeMillis }

    /** Counts add, newest use wins; the [MAX_EMOJI_RECENTS] most recent are kept. */
    fun emojiRecents(existing: List<EmojiRecent>, incoming: List<EmojiRecent>): List<EmojiRecent> =
        combine(existing, incoming, { it.emoji }) { a, b ->
                a.copy(lastUsed = maxOf(a.lastUsed, b.lastUsed), useCount = a.useCount + b.useCount)
            }
            .sortedByDescending { it.lastUsed }
            .take(MAX_EMOJI_RECENTS)

    /** This device's remembered variant wins. */
    fun emojiVariants(
        existing: List<EmojiVariant>,
        incoming: List<EmojiVariant>,
    ): List<EmojiVariant> = combine(existing, incoming, { it.base }) { a, _ -> a }

    /**
     * Touch statistics add per bucket and key. The weighted sums are decayed to the later of the
     * two times by [Aging.touch] first (the sample count does not decay).
     */
    fun touch(existing: TouchSection, incoming: TouchSection, aging: Aging): TouchSection {
        val out = LinkedHashMap<String, TouchBucket>()
        for (b in existing.buckets) out[b.touchKey] = b
        for (b in incoming.buckets) {
            val e = out[b.touchKey]
            out[b.touchKey] =
                if (e == null) b
                else
                    TouchBucket(
                        b.touchKey,
                        e.words + b.words,
                        combine(e.keys, b.keys, { it.char }) { x, y -> addKeys(x, y, aging) },
                    )
        }
        return TouchSection(out.values.toList())
    }

    private fun addKeys(x: TouchKeyStats, y: TouchKeyStats, aging: Aging): TouchKeyStats {
        val last = maxOf(x.lastActiveMillis, y.lastActiveMillis)
        val fx = aging.touch(last - x.lastActiveMillis)
        val fy = aging.touch(last - y.lastActiveMillis)
        return x.copy(
            samples = x.samples + y.samples,
            weight = x.weight * fx + y.weight * fy,
            sumX = x.sumX * fx + y.sumX * fy,
            sumY = x.sumY * fx + y.sumY * fy,
            sumXX = x.sumXX * fx + y.sumXX * fy,
            sumYY = x.sumYY * fx + y.sumYY * fy,
            lastActiveMillis = last,
        )
    }

    /** 0 means "unknown", so it never wins the earliest. */
    private fun earliest(a: Long, b: Long): Long =
        if (a == 0L) b else if (b == 0L) a else minOf(a, b)

    private inline fun <T, K> combine(
        existing: List<T>,
        incoming: List<T>,
        key: (T) -> K,
        merge: (T, T) -> T,
    ): List<T> {
        val out = LinkedHashMap<K, T>()
        for (e in existing) out[key(e)] = e
        for (i in incoming) {
            val k = key(i)
            val e = out[k]
            out[k] = if (e == null) i else merge(e, i)
        }
        return out.values.toList()
    }
}
