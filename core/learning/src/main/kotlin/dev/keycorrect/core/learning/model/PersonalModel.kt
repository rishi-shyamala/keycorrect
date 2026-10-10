package dev.keycorrect.core.learning.model

import dev.keycorrect.core.learning.LearningUpdate
import dev.keycorrect.core.learning.LearningUpdate.AddUserWord
import dev.keycorrect.core.learning.Personalization
import dev.keycorrect.core.learning.RankingFeatures
import java.util.concurrent.atomic.AtomicReference

/** Computes decoder features for a candidate (see [PersonalModel.rankingFeatureProvider]). */
typealias RankingFeatureProvider =
    (typed: String, context: List<String>, candidate: String) -> RankingFeatures?

/**
 * The personal-model aggregate (docs/PLAN.md §6.1): user n-gram cache, new-word promotion,
 * correction memory, suppressions / blocklist / casing, touch model, fusion ranker, adaptive
 * thresholds and the sentence buffer, all driven by [LearningUpdate]s.
 *
 * Threading: ONE writer (the learning thread) calls [apply], [forgetSince], [export], [import] and
 * the management methods (they are `@Synchronized`, so a stray second caller is safe but
 * serialised). Any number of readers (the decoder thread) use [snapshot] / [personalization], which
 * are lock-free reads of an immutable [PersonalSnapshot] published through an [AtomicReference].
 * Snapshots are published at most every [PersonalModelConfig.publishEveryUpdates] updates or
 * [PersonalModelConfig.publishMinIntervalMillis] ms, except that updates which must take effect at
 * once (suppress, block, correction evidence, casing, explicit user-word add) publish immediately.
 * Call [publishNow] to flush trailing updates (e.g. on a timer or at idle).
 *
 * Time: every method takes the wall clock from the caller. Decay runs on an *active clock* that
 * advances by the wall time between applies, capped at [PersonalModelConfig.maxActiveGapMillis] per
 * gap, so weeks of non-use do not age the evidence ("half-life of 90 days of active use").
 */
class PersonalModel(val config: PersonalModelConfig = PersonalModelConfig()) {
    private val decay = Decay(config.halfLifeMillis.toDouble())
    private val ngrams = NgramModel(config, decay)
    private val corrections = CorrectionMemory(config, decay)
    private val touch = TouchModel(config)
    private val ranker = FusionRanker(config)
    private val thresholds = AdaptiveThresholds(config)
    private val lexicon = Lexicon(config)

    /** The sentence buffer (D10). The keyboard feeds it via [recordSentenceWord]. */
    val sentences = SentenceBuffer(config.sentence)

    private val ref = AtomicReference(PersonalSnapshot.Empty)

    /**
     * Live [Personalization] that always reads the latest snapshot (one atomic read per call).
     *
     * The decoder caches its spatial models (with the learned touch offsets) per [Personalization]
     * instance, so this stable view never makes it rebuild them and learned touch offsets would
     * never reach the keyboard. The app must wire `personalization = { model.snapshot() }` to the
     * engine instead (a new immutable object after every publish); this view is for callers that
     * only need the other, per-call lookups.
     */
    val personalization: Personalization =
        object : Personalization {
            override fun userLogProb(context: List<String>, word: String) =
                ref.get().userLogProb(context, word)

            override fun correctionBias(typed: String, candidate: String, contextBucket: String) =
                ref.get().correctionBias(typed, candidate, contextBucket)

            override fun isSuppressed(typed: String, candidate: String) =
                ref.get().isSuppressed(typed, candidate)

            override fun isBlocked(word: String) = ref.get().isBlocked(word)

            override fun preferredCasing(key: String) = ref.get().preferredCasing(key)

            override fun touchOffset(touchKey: String, char: Char) =
                ref.get().touchOffset(touchKey, char)

            override val autocorrectThresholdDelta: Float
                get() = ref.get().autocorrectThresholdDelta

            override fun rankerScore(features: RankingFeatures) = ref.get().rankerScore(features)

            override val phraseGateDelta: Float
                get() = ref.get().phraseGateDelta
        }

    /**
     * Hook the app wires to the decoder: `(typed, context, candidate) -> RankingFeatures?`. The
     * classifier gives [LearningUpdate.Preference] as strings; the ranker trains on features, so
     * each preference is turned into a (preferred, other) feature pair through this provider,
     * called on the learning thread. With no provider, Preference updates are ignored. If the
     * provider returns null for either candidate (cannot be scored right now) the preference is
     * buffered (at most [PersonalModelConfig.rankerPendingMax], for
     * [PersonalModelConfig.rankerPendingTtlMillis]) and retried on the next [apply] or
     * [retryPendingPreferences].
     */
    @Volatile var rankingFeatureProvider: RankingFeatureProvider? = null

    private class PendingPreference(val timeMillis: Long, val update: LearningUpdate.Preference)

    private val pendingPrefs = ArrayDeque<PendingPreference>()

    private var active = 0L
    private var lastWall = Long.MIN_VALUE
    private var version = 0L
    private var sincePublish = 0
    private var lastPublishWall = Long.MIN_VALUE
    private var lastPruneWall = Long.MIN_VALUE

    private val journal = ArrayList<JournalEntry>()
    private val journalPending = ArrayList<JournalEntry>()
    private val pendingAdds = ArrayList<AddUserWord>()

    // Cached immutable lexicon views, rebuilt only when the lexicon changed.
    private var lexDirty = true
    private var cSuppressed: Set<String> = emptySet()
    private var cBlocked: Set<String> = emptySet()
    private var cUserWords: Set<String> = emptySet()
    private var cCasing: Map<String, String> = emptyMap()

    /** The latest published snapshot; hold on to it for the duration of one decode. */
    fun snapshot(): PersonalSnapshot = ref.get()

    /** Aggregate touch statistics for the keyboard-fit report (§6.5), from the latest snapshot. */
    fun touchStatistics(): TouchStatistics = ref.get().touchStatistics()

    // ------------------------------------------------------------------ apply

    @Synchronized
    fun apply(updates: List<LearningUpdate>, nowMillis: Long) {
        if (updates.isEmpty()) {
            retryPendingPreferences(nowMillis)
            maybePublish(nowMillis, urgent = false)
            return
        }
        advanceClock(nowMillis)
        ranker.maybeCheckpoint(nowMillis)
        var urgent = false
        for (u in updates) if (handle(u, nowMillis)) urgent = true
        sincePublish += updates.size
        retryPendingPreferences(nowMillis)
        ngrams.enforceCaps(active)
        corrections.enforceCaps(active)
        housekeeping(nowMillis)
        maybePublish(nowMillis, urgent)
    }

    private fun advanceClock(nowMillis: Long) {
        if (lastWall != Long.MIN_VALUE) {
            active += (nowMillis - lastWall).coerceIn(0L, config.maxActiveGapMillis)
        }
        lastWall = nowMillis
    }

    /** Returns true if the update must be visible to the decoder immediately. */
    private fun handle(u: LearningUpdate, now: Long): Boolean {
        when (u) {
            is LearningUpdate.WordUsed -> {
                val w = Text.key(u.word)
                if (w.isEmpty() || lexicon.isBlocked(w)) return false
                val weight =
                    u.weight
                        .toDouble()
                        .coerceIn(-config.maxUsageWeightPerUpdate, config.maxUsageWeightPerUpdate)
                ngrams.add(Text.contextKeys(u.context), w, weight, active)
                record(now, u)
            }
            is LearningUpdate.NewWordEvidence -> {
                val promoted = lexicon.noteCandidate(u.word, u.sessionId, u.weight.toDouble(), now)
                if (promoted != null) {
                    pendingAdds += AddUserWord(promoted, AddUserWord.Reason.Promoted)
                    lexDirty = true
                }
                record(now, u)
                return promoted != null
            }
            is LearningUpdate.AddUserWord -> {
                if (lexicon.addUserWord(u.word, u.reason, now)) {
                    pendingAdds += u
                    lexDirty = true
                    return true
                }
            }
            is LearningUpdate.CorrectionEvidence -> {
                corrections.add(u.typed, u.target, u.contextBucket, u.delta.toDouble(), active)
                record(now, u)
                return true
            }
            is LearningUpdate.SuppressCorrection -> {
                lexicon.suppress(u.typed, u.target, now)
                lexDirty = true
                return true
            }
            is LearningUpdate.BlockWord -> {
                lexicon.block(u.word, now)
                lexDirty = true
                return true
            }
            is LearningUpdate.TouchEvidence -> {
                touch.add(u.touchKey, u.samples, active)
                record(now, u)
            }
            is LearningUpdate.Preference -> handlePreference(u, now)
            is LearningUpdate.AutocorrectOutcome -> thresholds.addAutocorrect(now, u.reverted)
            is LearningUpdate.PhraseFeedback ->
                thresholds.addPhrase(now, u.offeredWords, u.acceptedWords)
            is LearningUpdate.CasingEvidence -> {
                lexicon.addCasing(u.surface, 1.0)
                lexDirty = true
                record(now, u)
                return true
            }
        }
        return false
    }

    private fun handlePreference(u: LearningUpdate.Preference, now: Long) {
        if (rankingFeatureProvider == null) return
        if (!tryTrain(u) && pendingPrefs.size < config.rankerPendingMax) {
            pendingPrefs.addLast(PendingPreference(now, u))
        }
    }

    /** True when consumed (trained, or no provider so it is dropped); false = retry later. */
    private fun tryTrain(u: LearningUpdate.Preference): Boolean {
        val provider = rankingFeatureProvider ?: return true
        val fp = provider(u.typed, u.context, u.preferred) ?: return false
        val fo = provider(u.typed, u.context, u.other) ?: return false
        ranker.train(fp, fo, u.weight)
        return true
    }

    /** Retries buffered preferences whose features could not be computed earlier. */
    @Synchronized
    fun retryPendingPreferences(nowMillis: Long) {
        if (pendingPrefs.isEmpty()) return
        if (rankingFeatureProvider == null) {
            pendingPrefs.clear()
            return
        }
        val it = pendingPrefs.iterator()
        var trained = false
        while (it.hasNext()) {
            val p = it.next()
            if (nowMillis - p.timeMillis > config.rankerPendingTtlMillis) {
                it.remove()
            } else if (tryTrain(p.update)) {
                it.remove()
                trained = true
            }
        }
        if (trained) sincePublish++
    }

    private fun record(time: Long, u: LearningUpdate) {
        val e = JournalEntry(time, u)
        journal += e
        journalPending += e
        if (journal.size > config.maxJournalEntries) {
            val drop = journal.size - config.maxJournalEntries
            journal.subList(0, drop).clear()
        }
        if (journalPending.size > config.maxJournalEntries) {
            journalPending.subList(0, journalPending.size - config.maxJournalEntries).clear()
        }
    }

    private fun housekeeping(now: Long) {
        if (lastPruneWall != Long.MIN_VALUE && now - lastPruneWall < 60_000L) return
        lastPruneWall = now
        val cutoff = now - config.journalRetentionMillis
        journal.removeAll { it.timeMillis < cutoff }
        lexicon.pruneCandidates(now)
        sentences.prune(now)
    }

    // ---------------------------------------------------------------- publish

    private fun maybePublish(now: Long, urgent: Boolean) {
        if (sincePublish == 0 && !urgent) return
        val due =
            urgent ||
                lastPublishWall == Long.MIN_VALUE ||
                sincePublish >= config.publishEveryUpdates ||
                now - lastPublishWall >= config.publishMinIntervalMillis
        if (due) publish(now)
    }

    /**
     * Publishes a fresh snapshot now (flush after a batch, on a timer, or before reading in tests).
     */
    @Synchronized
    fun publishNow(nowMillis: Long = if (lastWall == Long.MIN_VALUE) 0L else lastWall) =
        publish(nowMillis)

    private fun publish(now: Long) {
        if (lexDirty) {
            cSuppressed = lexicon.suppressions.keys.toSet()
            cBlocked = lexicon.blocked.keys.toSet()
            cUserWords = lexicon.userWords.filter { !it.value.removed }.keys.toSet()
            cCasing = HashMap(lexicon.preferredCasing())
            lexDirty = false
        }
        version++
        ref.set(
            PersonalSnapshot(
                version,
                ngrams.freeze(active),
                corrections.freeze(active),
                cSuppressed,
                cBlocked,
                cUserWords,
                cCasing,
                touch.freeze(),
                ranker.frozenWeights(),
                config.rankerMaxScore,
                thresholds.thresholdDelta(),
                thresholds.phraseGateDelta(),
            )
        )
        sincePublish = 0
        lastPublishWall = now
    }

    // ------------------------------------------------------- side effects

    /**
     * Drains user-dictionary adds the app must persist in its own store (promoted words, explicit
     * and reverted-autocorrect adds). Also kept in [export] until drained, so a crash loses none.
     */
    @Synchronized
    fun pendingUserDictionaryAdds(): List<AddUserWord> {
        if (pendingAdds.isEmpty()) return emptyList()
        val out = pendingAdds.toList()
        pendingAdds.clear()
        return out
    }

    /** Journal entries not yet handed to a store; drained by [LearningPersistence.flush]. */
    @Synchronized
    fun drainJournalForPersistence(): List<JournalEntry> {
        if (journalPending.isEmpty()) return emptyList()
        val out = journalPending.toList()
        journalPending.clear()
        return out
    }

    @Synchronized fun journalSnapshot(): List<JournalEntry> = journal.toList()

    // --------------------------------------------------------- sentences

    /** See [SentenceBuffer.recordSentenceWord]; only non-sensitive input may reach this. */
    fun recordSentenceWord(sessionId: Long, word: String, sentenceEnd: Boolean, timeMillis: Long) =
        sentences.recordSentenceWord(sessionId, word, sentenceEnd, timeMillis)

    // ------------------------------------------------- management (UI)

    @Synchronized
    fun userWords(): List<UserWordInfo> =
        lexicon.userWords.values
            .filter { !it.removed }
            .map { UserWordInfo(it.word, it.reason, it.createdAt) }
            .sortedBy { it.word.lowercase() }

    @Synchronized fun blockedWords(): List<String> = lexicon.blocked.keys.sorted()

    @Synchronized
    fun suppressedCorrections(): List<Pair<String, String>> =
        lexicon.suppressions.keys
            .map { k -> k.split(Text.SEP).let { it[0] to it[1] } }
            .sortedBy { it.first }

    /** Removes [word] from the user dictionary (tombstoned so it is not auto-promoted again). */
    @Synchronized
    fun removeUserWord(word: String, nowMillis: Long): Boolean =
        lexicon.removeUserWord(word, nowMillis).also {
            if (it) {
                lexDirty = true
                publish(nowMillis)
            }
        }

    @Synchronized
    fun unblock(word: String, nowMillis: Long): Boolean =
        lexicon.unblock(word).also {
            if (it) {
                lexDirty = true
                publish(nowMillis)
            }
        }

    @Synchronized
    fun removeSuppression(typed: String, target: String, nowMillis: Long): Boolean =
        lexicon.removeSuppression(typed, target).also {
            if (it) {
                lexDirty = true
                publish(nowMillis)
            }
        }

    // ------------------------------------------------------------ forget

    /**
     * "Forget since T": undoes what was learned at or after [timeMillis]. Additive models (n-grams,
     * correction memory, touch, casing, candidate words) subtract the journaled updates, with the
     * subtracted amount decayed by the entry's age (error is below 1% for the 24 h journal); user
     * words, suppressions and blocks created at or after T are removed; the ranker is restored from
     * the last checkpoint taken before T (initial weights if none); outcome windows and sentences
     * newer than T are dropped. Returns what the app must also remove from its own stores.
     */
    @Synchronized
    fun forgetSince(timeMillis: Long, nowMillis: Long): ForgetResult {
        advanceClock(nowMillis)
        val doomed = journal.filter { it.timeMillis >= timeMillis }
        for (e in doomed.asReversed()) {
            val age = (nowMillis - e.timeMillis).coerceAtLeast(0L)
            val f = decay.factor(age)
            when (val u = e.update) {
                is LearningUpdate.WordUsed -> {
                    val w = Text.key(u.word)
                    val weight =
                        u.weight
                            .toDouble()
                            .coerceIn(
                                -config.maxUsageWeightPerUpdate,
                                config.maxUsageWeightPerUpdate,
                            )
                    ngrams.add(Text.contextKeys(u.context), w, -weight * f, active)
                }
                is LearningUpdate.NewWordEvidence ->
                    lexicon.forgetCandidate(u.word, u.sessionId, u.weight.toDouble())
                is LearningUpdate.CorrectionEvidence ->
                    corrections.add(
                        u.typed,
                        u.target,
                        u.contextBucket,
                        -u.delta.toDouble() * f,
                        active,
                    )
                is LearningUpdate.TouchEvidence -> touch.remove(u.touchKey, u.samples, age, active)
                is LearningUpdate.CasingEvidence -> lexicon.addCasing(u.surface, -1.0)
                else -> Unit
            }
        }
        journal.removeAll { it.timeMillis >= timeMillis }
        journalPending.removeAll { it.timeMillis >= timeMillis }
        val (words, sups, blocks) = lexicon.forgetSince(timeMillis)
        val removedKeys = words.map(Text::key).toSet()
        pendingAdds.removeAll { Text.key(it.word) in removedKeys }
        val dropped = thresholds.forgetSince(timeMillis)
        pendingPrefs.removeAll { it.timeMillis >= timeMillis }
        val restored = ranker.restoreBefore(timeMillis)
        val sentencesRemoved = sentences.forgetSince(timeMillis)
        lexDirty = true
        publish(nowMillis)
        return ForgetResult(words, sups, blocks, doomed.size, dropped, sentencesRemoved, restored)
    }

    // ------------------------------------------------------- persistence

    @Synchronized
    fun export(): PersistedLearning {
        val (touchRows, profileRows) = touch.exportRows()
        return PersistedLearning(
            activeClockMillis = active,
            lastWallMillis = lastWall,
            rankerUpdates = ranker.updates,
            lastCheckpointMillis = ranker.lastCheckpointMillis,
            ngrams = ngrams.exportRows(),
            corrections = corrections.exportRows(),
            suppressions = lexicon.suppressionRows(),
            blocks = lexicon.blockRows(),
            userWords = lexicon.userWordRows(),
            candidates = lexicon.candidateRows(),
            candidateSessions = lexicon.candidateSessionRows(),
            casing = lexicon.casingRows(),
            touch = touchRows,
            touchProfiles = profileRows,
            rankerWeights = ranker.exportRows(),
            outcomes = thresholds.exportRows(),
            sentences = sentences.exportRows(),
            pendingAdds = pendingAdds.map { PendingAddRow(it.word, it.reason.ordinal) },
        )
    }

    /** Replaces all state from [state] (and the 24 h [journal] if the store has one). */
    @Synchronized
    fun import(state: PersistedLearning, journal: List<JournalEntry> = emptyList()) {
        require(state.formatVersion <= PersistedLearning.FORMAT_VERSION) {
            "Unsupported learning format ${state.formatVersion}"
        }
        active = state.activeClockMillis
        lastWall = state.lastWallMillis
        ngrams.importRows(state.ngrams, active)
        corrections.importRows(state.corrections)
        lexicon.importRows(state)
        touch.importRows(state.touch, state.touchProfiles)
        ranker.importRows(state.rankerWeights, state.rankerUpdates, state.lastCheckpointMillis)
        thresholds.importRows(state.outcomes)
        sentences.importRows(state.sentences)
        pendingAdds.clear()
        for (r in state.pendingAdds) {
            pendingAdds +=
                AddUserWord(
                    r.word,
                    AddUserWord.Reason.entries.getOrElse(r.reason) { AddUserWord.Reason.Explicit },
                )
        }
        pendingPrefs.clear()
        this.journal.clear()
        this.journal.addAll(journal.sortedBy { it.timeMillis })
        journalPending.clear()
        lexDirty = true
        publish(if (lastWall == Long.MIN_VALUE) 0L else lastWall)
    }

    /** Forgets everything (settings "reset"). */
    @Synchronized
    fun clear() {
        import(PersistedLearning())
        sentences.clear()
    }
}

/**
 * Glue between a [PersonalModel] (learning thread) and a [LearningStore]. Call these from the
 * learning coroutine only.
 */
class LearningPersistence(
    private val model: PersonalModel,
    private val store: LearningStore,
) {
    /** Loads state and the retained journal; returns false if the store was empty. */
    suspend fun restore(nowMillis: Long): Boolean {
        val state = store.load() ?: return false
        model.import(
            state,
            store.journalSince(nowMillis - model.config.journalRetentionMillis),
        )
        return true
    }

    /** Persists new journal entries and the full state; trims the journal to its retention. */
    suspend fun flush(nowMillis: Long) {
        val entries = model.drainJournalForPersistence()
        if (entries.isNotEmpty()) store.appendJournal(entries)
        store.save(model.export())
        store.truncateJournalBefore(nowMillis - model.config.journalRetentionMillis)
    }

    /** Forgets since [timeMillis] and rewrites the store so it matches. */
    suspend fun forgetSince(timeMillis: Long, nowMillis: Long): ForgetResult {
        val result = model.forgetSince(timeMillis, nowMillis)
        store.truncateJournalBefore(Long.MAX_VALUE)
        val remaining = model.journalSnapshot()
        if (remaining.isNotEmpty()) store.appendJournal(remaining)
        model.drainJournalForPersistence()
        store.save(model.export())
        return result
    }

    suspend fun clearAll() {
        model.clear()
        store.clear()
    }
}
