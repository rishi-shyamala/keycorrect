package dev.keycorrect.ime

import android.util.Log
import dev.keycorrect.core.data.InvalidEntryException
import dev.keycorrect.core.data.UserDictionaryStore
import dev.keycorrect.core.engineapi.FieldClass
import dev.keycorrect.core.learning.LearningEvent
import dev.keycorrect.core.learning.LearningSink
import dev.keycorrect.core.learning.Personalization
import dev.keycorrect.core.learning.classifier.OutcomeClassifier
import dev.keycorrect.core.learning.model.ForgetResult
import dev.keycorrect.core.learning.model.LearnedCorrection
import dev.keycorrect.core.learning.model.LearningPersistence
import dev.keycorrect.core.learning.model.LearningStore
import dev.keycorrect.core.learning.model.PersonalModel
import dev.keycorrect.core.learning.model.TouchStatistics
import dev.keycorrect.core.learning.model.UserWordInfo
import dev.keycorrect.core.settings.KeyboardSettings
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Tunables of [LearningHost]; the defaults are the shipped behaviour. */
data class LearningHostConfig(
    /** Events and sentence words accepted while behind (e.g. during restore); extra ones drop. */
    val maxQueuedInputs: Int = 2_000,
    /** Save after this many applied updates (sentence words count as one) ... */
    val flushEveryUpdates: Int = 300,
    /** ... or when this much time passed since the last save, whichever comes first. */
    val flushIntervalMillis: Long = 5 * 60_000L,
    /** Saves rewrite every learning table, so unforced saves are at least this far apart. */
    val minFlushGapMillis: Long = 15_000L,
    /** Quiet period after the last update before trailing updates are published. */
    val publishDelayMillis: Long = 1_500L,
    /** Delay before preferences deferred because the decoder was busy are retried. */
    val retryDelayMillis: Long = 2_000L,
    /** After a failed restore, how long to wait before the next event retries it. */
    val restoreRetryMillis: Long = 30_000L,
)

/** Where [LearningHost] is with loading the saved state. */
enum class LearningHostState {
    /** Loading the saved model; events queue (bounded) until it is done. */
    Restoring,
    Ready,

    /**
     * The store could not be read (locked keystore, corrupt data, newer format). Events are
     * dropped, nothing is saved (so the stored state is never overwritten by an empty model) and
     * loading is retried. [LearningHost.resetAll] recovers by clearing the store.
     */
    Unavailable,
}

/** Why a save was asked for; see [LearningHost.requestFlush]. */
enum class FlushReason {
    /** The keyboard went away: saved unless a save just happened, else a little later. */
    KeyboardHidden,
    /** The system wants memory (the process may be killed next): saved at once. */
    TrimMemory,
}

/** Everything the "What KeyCorrect learned" screen lists, in one consistent read. */
data class LearningOverview(
    /** Words on the learned user dictionary with how each got there (see `AddUserWord.Reason`). */
    val userWords: List<UserWordInfo>,
    val blockedWords: List<String>,
    /** (typed, corrected-to) pairs that are never autocorrected. */
    val suppressedCorrections: List<Pair<String, String>>,
    /** Number of stored recent sentences (decision D10). */
    val sentenceCount: Int,
)

/**
 * The app's learning system, one per process (see `AppContainer.learningHost`): the keyboard emits
 * [LearningEvent]s into it (it is the controller's [LearningSink]) and the settings screens manage
 * what was learned through its suspend API. It owns the [OutcomeClassifier], the [PersonalModel]
 * and the [LearningPersistence] on the encrypted store.
 *
 * Threading: [onEvent] and [onSentenceWord] (main thread) only check the gates and enqueue. A
 * single consumer coroutine on the learning thread ([scope]'s dispatcher) is the ONLY code that
 * touches the model's write side, the classifier and the store: it restores the saved state first,
 * then handles, in order, events (classify, apply, hand user-dictionary adds to the real store),
 * sentence words, saves, publishing ticks and management requests. Management functions are suspend
 * functions that run as queued requests on that thread, so they are ordered with events and never
 * race them. The decoder reads [model] snapshots lock-free from its own thread (see
 * [personalization]).
 *
 * Gates (the keyboard also gates; this is the second lock): nothing is accepted until the first
 * settings arrive, while learning is off or incognito is on; events already queued are dropped if a
 * gate closes before they are handled. Turning the sentence buffer off stops recording and clears
 * it (and its saved copy).
 *
 * Saving: [PersonalModel] state is saved as a whole (every table is rewritten), so it is debounced:
 * after [LearningHostConfig.flushEveryUpdates] updates or [LearningHostConfig.flushIntervalMillis],
 * when the keyboard hides ([requestFlush]) and on memory trim, never more often than
 * [LearningHostConfig.minFlushGapMillis] except on trim; management changes are saved at once.
 * Saving never runs on the main thread.
 */
class LearningHost(
    private val openStore: () -> LearningStore,
    private val userDictionary: UserDictionaryStore,
    private val scope: CoroutineScope,
    private val wallClock: () -> Long = System::currentTimeMillis,
    val model: PersonalModel = PersonalModel(),
    private val classifier: OutcomeClassifier = OutcomeClassifier(),
    private val config: LearningHostConfig = LearningHostConfig(),
) : LearningSink {
    private sealed interface Command {
        class Event(val event: LearningEvent) : Command

        class Sentence(
            val sessionId: Long,
            val word: String,
            val sentenceEnd: Boolean,
            val timeMillis: Long,
        ) : Command

        class Flush(val force: Boolean) : Command

        data object Tick : Command

        data object ClearSentences : Command

        class Run(val block: suspend () -> Unit) : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val queuedInputs = AtomicInteger(0)
    private val started = AtomicBoolean(false)

    // Gates, written by applySettings, read on the main thread and the learning thread.
    @Volatile private var settingsKnown = false
    @Volatile private var learningEnabled = true
    @Volatile private var incognito = false
    @Volatile private var sentenceBufferEnabled = true

    private val acceptingEvents: Boolean
        get() = settingsKnown && learningEnabled && !incognito

    private val acceptingSentences: Boolean
        get() = acceptingEvents && sentenceBufferEnabled

    private val _state = MutableStateFlow(LearningHostState.Restoring)

    /** Loading status; the settings UI can show a message for [LearningHostState.Unavailable]. */
    val state: StateFlow<LearningHostState> = _state.asStateFlow()

    private val changes = MutableStateFlow(0L)

    /** The field class of the event being handled; the ranking feature provider reads it. */
    @Volatile
    var rankingField: FieldClass = FieldClass.Text
        private set

    // ---- learning-thread-only state -------------------------------------------------------
    private var persistence: LearningPersistence? = null
    private var dirty = false
    private var updatesSinceFlush = 0
    private var lastFlushAt = Long.MIN_VALUE / 2
    private var lastRestoreAttemptAt = Long.MIN_VALUE / 2
    private var appliedSinceTick = false
    private val failedAdds = LinkedHashSet<String>()

    private val tickScheduled = AtomicBoolean(false)
    private val flushScheduled = AtomicBoolean(false)
    private val retryWanted = AtomicBoolean(false)

    /**
     * Starts the learning thread's work: restores the saved state, then serves requests. [settings]
     * is the settings stream that drives the gates. Safe to call more than once.
     */
    fun start(settings: Flow<KeyboardSettings>) {
        if (!started.compareAndSet(false, true)) return
        scope.launch { settings.collect { applySettings(it) } }
        scope.launch {
            restore()
            for (command in commands) handle(command)
        }
    }

    /**
     * Applies the privacy-relevant settings. Called by [start]'s collector; also usable directly.
     */
    fun applySettings(settings: KeyboardSettings) {
        learningEnabled = settings.learningEnabled
        incognito = settings.incognito
        sentenceBufferEnabled = settings.sentenceBufferEnabled
        settingsKnown = true
        if (!settings.sentenceBufferEnabled) commands.trySend(Command.ClearSentences)
    }

    /**
     * What the decoder should personalise with, per decode: the latest model snapshot (a new
     * immutable object after every publish, which is what makes learned touch offsets reach the
     * spatial model) or none while learning is off ("base models only").
     */
    fun personalization(): Personalization =
        if (learningEnabled) model.snapshot() else Personalization.None

    // ---- LearningSink (main thread) -------------------------------------------------------

    override fun onEvent(event: LearningEvent) {
        if (!acceptingEvents || !reserve()) return
        commands.trySend(Command.Event(event))
    }

    override fun onSentenceWord(
        sessionId: Long,
        word: String,
        sentenceEnd: Boolean,
        timeMillis: Long,
    ) {
        if (!acceptingSentences || !reserve()) return
        commands.trySend(Command.Sentence(sessionId, word, sentenceEnd, timeMillis))
    }

    private fun reserve(): Boolean {
        if (queuedInputs.incrementAndGet() <= config.maxQueuedInputs) return true
        queuedInputs.decrementAndGet()
        return false
    }

    /** Asks for the state to be saved; returns at once (the save runs on the learning thread). */
    fun requestFlush(reason: FlushReason) {
        commands.trySend(Command.Flush(force = reason == FlushReason.TrimMemory))
    }

    /**
     * The ranking feature provider found the decoder busy and declined to compute features; ask for
     * the model's deferred preferences to be retried once typing has paused.
     */
    fun onRankingDeferred() {
        retryWanted.set(true)
    }

    // ---- management (suspend, run on the learning thread) ---------------------------------

    /** Completes when everything queued before this call has been handled. */
    suspend fun awaitIdle() = onLearningThread {}

    /** Saves now (forced). */
    suspend fun flush() = onLearningThread { flushNow() }

    suspend fun overview(): LearningOverview = onLearningThread { readOverview() }

    /**
     * Emits [overview] now and again after the learned data changes (new learning, removals,
     * forgetting, reset), at most every [LearningHostConfig.publishDelayMillis] while typing.
     */
    fun overviewFlow(): Flow<LearningOverview> = changes.map { overview() }

    suspend fun learnedWords(): List<UserWordInfo> = onLearningThread { model.userWords() }

    suspend fun blockedWords(): List<String> = onLearningThread { model.blockedWords() }

    suspend fun suppressedCorrections(): List<Pair<String, String>> = onLearningThread {
        model.suppressedCorrections()
    }

    /** Per-profile touch statistics for the keyboard-fit report (never contains text). */
    suspend fun touchStatistics(): TouchStatistics = onLearningThread { model.touchStatistics() }

    /** Number of stored recent sentences. */
    suspend fun sentenceBufferSize(): Int = onLearningThread { model.sentences.size() }

    /** "Clear now" for the sentence buffer, also from the saved copy. */
    suspend fun clearSentenceBuffer() = onLearningThread {
        model.sentences.clear()
        changed()
        flushNow()
    }

    /**
     * Removes [word] from what was learned and from the user dictionary (it will not be promoted
     * again on its own). Returns true if something was removed.
     */
    suspend fun removeLearnedWord(word: String): Boolean = onLearningThread {
        requireReady()
        val removedFromModel = model.removeUserWord(word, wallClock())
        failedAdds.removeAll { it.equals(word, ignoreCase = true) }
        val removedFromStore = removeFromUserDictionary(word)
        changed()
        flushNow()
        removedFromModel || removedFromStore
    }

    /**
     * The strongest learned autocorrections (typed -> target, best first, at most [limit]) for the
     * settings screen; see [PersonalModel.topCorrections]. Typed and target are normalised
     * (lower-case) keys.
     */
    suspend fun topCorrections(limit: Int): List<LearnedCorrection> = onLearningThread {
        model.topCorrections(limit)
    }

    /**
     * Drops the learned correction [typed] -> [target] (correction memory in every context and its
     * journaled evidence; a "never autocorrect" suppression of the pair is left alone). Returns
     * true if something was dropped.
     */
    suspend fun forgetCorrection(typed: String, target: String): Boolean = onLearningThread {
        requireReady()
        checkNotNull(persistence).forgetCorrection(typed, target, wallClock()).also {
            if (it) {
                dirty = false
                updatesSinceFlush = 0
                lastFlushAt = wallClock()
                bump()
            }
        }
    }

    /**
     * Tells the model that the user removed [word] from the personal dictionary (the settings
     * dictionary screen calls this after its own store removal; this does not touch the store). The
     * model stops treating the word as learned and will not promote it again from old evidence;
     * only an explicit add (by the user, through the keyboard's add-word flow) brings it back.
     * Works even while learning is off, and is a no-op while the saved state is unavailable.
     * Returns true if the model changed.
     */
    suspend fun onUserWordRemoved(word: String): Boolean = onLearningThread {
        if (_state.value != LearningHostState.Ready) return@onLearningThread false
        failedAdds.removeAll { it.equals(word, ignoreCase = true) }
        model.forgetUserWord(word, wallClock()).also {
            if (it) {
                changed()
                flushNow()
            }
        }
    }

    /** Lifts a "never suggest" block. */
    suspend fun unblock(word: String): Boolean = onLearningThread {
        requireReady()
        model.unblock(word, wallClock()).also {
            changed()
            flushNow()
        }
    }

    /** Lets [typed] be autocorrected to [target] again. */
    suspend fun removeSuppression(typed: String, target: String): Boolean = onLearningThread {
        requireReady()
        model.removeSuppression(typed, target, wallClock()).also {
            changed()
            flushNow()
        }
    }

    /**
     * "Forget the last [minutes] minutes": undoes what was learned since then (see
     * [PersonalModel.forgetSince]; n-gram, correction and touch evidence is only journaled for 24
     * h, so older learning cannot be subtracted) and removes the user words, suppressions and
     * blocks created since from the model and the user dictionary store.
     */
    suspend fun forgetSince(minutes: Int): ForgetResult = onLearningThread {
        requireReady()
        val now = wallClock()
        val result =
            checkNotNull(persistence).forgetSince(now - minutes.coerceAtLeast(0) * 60_000L, now)
        for (word in result.removedUserWords) {
            failedAdds.removeAll { it.equals(word, ignoreCase = true) }
            removeFromUserDictionary(word)
        }
        dirty = false
        updatesSinceFlush = 0
        lastFlushAt = now
        bump()
        result
    }

    /**
     * Forgets everything learned and clears the saved learning data, sentences included. User words
     * in the user dictionary store (explicit adds and promoted words) are left alone; remove those
     * in the personal dictionary. Also recovers from [LearningHostState.Unavailable].
     */
    suspend fun resetAll() = onLearningThread {
        val p = persistence ?: LearningPersistence(model, openStore()).also { persistence = it }
        p.clearAll()
        failedAdds.clear()
        dirty = false
        updatesSinceFlush = 0
        _state.value = LearningHostState.Ready
        bump()
    }

    // ---- the learning thread ---------------------------------------------------------------

    private suspend fun <T> onLearningThread(block: suspend () -> T): T {
        val result = CompletableDeferred<T>()
        commands.send(
            Command.Run {
                try {
                    result.complete(block())
                } catch (e: CancellationException) {
                    result.cancel(e)
                } catch (e: Throwable) {
                    result.completeExceptionally(e)
                }
            }
        )
        return result.await()
    }

    private suspend fun handle(command: Command) {
        try {
            when (command) {
                is Command.Event -> {
                    queuedInputs.decrementAndGet()
                    handleEvent(command.event)
                }
                is Command.Sentence -> {
                    queuedInputs.decrementAndGet()
                    handleSentence(command)
                }
                is Command.Flush -> handleFlush(command.force)
                Command.Tick -> handleTick()
                Command.ClearSentences -> handleClearSentences()
                is Command.Run -> command.block()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Type only: messages could contain user text.
            Log.w(TAG, "Learning step failed: ${e.javaClass.name}")
        }
    }

    private suspend fun restore() {
        _state.value = LearningHostState.Restoring
        val now = wallClock()
        lastRestoreAttemptAt = now
        try {
            val p = persistence ?: LearningPersistence(model, openStore()).also { persistence = it }
            p.restore(now)
            lastFlushAt = now
            enforceSentenceGate()
            drainUserWords()
            _state.value = LearningHostState.Ready
            bump()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Restoring learning data failed: ${e.javaClass.name}")
            _state.value = LearningHostState.Unavailable
        }
    }

    /** True once the saved state is loaded; retries a failed load at most every few seconds. */
    private suspend fun ensureReady(): Boolean {
        if (_state.value == LearningHostState.Ready) return true
        if (wallClock() - lastRestoreAttemptAt >= config.restoreRetryMillis) restore()
        return _state.value == LearningHostState.Ready
    }

    private fun requireReady() {
        check(_state.value == LearningHostState.Ready && persistence != null) {
            "Learning data is not available"
        }
    }

    private suspend fun handleEvent(event: LearningEvent) {
        if (!acceptingEvents || !ensureReady()) return
        val updates = classifier.classify(event)
        rankingField = event.profile.field
        model.apply(updates, event.timeMillis)
        drainUserWords()
        noteChange(updates.size)
    }

    private suspend fun handleSentence(s: Command.Sentence) {
        if (!acceptingSentences || !ensureReady()) return
        model.recordSentenceWord(s.sessionId, s.word, s.sentenceEnd, s.timeMillis)
        noteChange(1, visible = false)
    }

    /** Bookkeeping after learning: schedule publishing, and save when enough accumulated. */
    private suspend fun noteChange(updates: Int, visible: Boolean = true) {
        dirty = true
        updatesSinceFlush += updates
        if (visible) {
            appliedSinceTick = true
            scheduleTick(config.publishDelayMillis)
        }
        val now = wallClock()
        if (
            updatesSinceFlush >= config.flushEveryUpdates ||
                now - lastFlushAt >= config.flushIntervalMillis
        ) {
            handleFlush(force = false)
        }
    }

    private suspend fun handleFlush(force: Boolean) {
        if (!dirty || _state.value != LearningHostState.Ready) return
        val sinceLast = wallClock() - lastFlushAt
        if (force || sinceLast >= config.minFlushGapMillis) {
            flushNow()
        } else if (flushScheduled.compareAndSet(false, true)) {
            val wait = config.minFlushGapMillis - sinceLast
            scope.launch {
                delay(wait)
                flushScheduled.set(false)
                commands.trySend(Command.Flush(force = true))
            }
        }
    }

    private suspend fun flushNow() {
        val p = persistence
        if (p == null || _state.value != LearningHostState.Ready) return
        if (!dirty) return
        try {
            drainUserWords()
            p.flush(wallClock())
            dirty = false
            updatesSinceFlush = 0
            lastFlushAt = wallClock()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Saving learning data failed: ${e.javaClass.name}")
        }
    }

    private fun scheduleTick(delayMillis: Long) {
        if (!tickScheduled.compareAndSet(false, true)) return
        scope.launch {
            delay(delayMillis)
            commands.trySend(Command.Tick)
        }
    }

    private fun handleTick() {
        tickScheduled.set(false)
        if (_state.value != LearningHostState.Ready) return
        val now = wallClock()
        model.retryPendingPreferences(now)
        if (appliedSinceTick) {
            appliedSinceTick = false
            model.publishNow(now)
            changes.update { it + 1 }
        }
        if (retryWanted.getAndSet(false)) scheduleTick(config.retryDelayMillis)
    }

    private suspend fun handleClearSentences() {
        if (model.sentences.size() > 0) {
            model.sentences.clear()
            dirty = true
            changed()
            flushNow()
        } else {
            model.sentences.clear() // open (unfinished) sentences hold text too
        }
    }

    /** After a restore the buffer must honour the setting that may have changed meanwhile. */
    private fun enforceSentenceGate() {
        if (settingsKnown && !sentenceBufferEnabled) model.sentences.clear()
    }

    private fun readOverview() =
        LearningOverview(
            userWords = model.userWords(),
            blockedWords = model.blockedWords(),
            suppressedCorrections = model.suppressedCorrections(),
            sentenceCount = model.sentences.size(),
        )

    private fun changed() {
        dirty = true
        bump()
    }

    private fun bump() {
        changes.update { it + 1 }
    }

    // ---- user dictionary --------------------------------------------------------------------

    /**
     * Hands words the model promoted or was told to add to the real user dictionary. A failed add
     * (store locked) is retried at the next drain; an invalid word is dropped.
     */
    private suspend fun drainUserWords() {
        val adds = model.pendingUserDictionaryAdds()
        if (adds.isEmpty() && failedAdds.isEmpty()) return
        val work = LinkedHashSet<String>(failedAdds)
        adds.mapTo(work) { it.word }
        failedAdds.clear()
        for (word in work) {
            try {
                addIfAbsent(word)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidEntryException) {
                // Never valid; do not retry.
            } catch (e: Throwable) {
                Log.w(TAG, "Adding a learned word failed: ${e.javaClass.name}")
                if (failedAdds.size < MAX_FAILED_ADDS) failedAdds += word
            }
        }
    }

    /** Adds [word] unless the user dictionary has it (in any casing): keeps their frequency. */
    private suspend fun addIfAbsent(word: String) {
        if (userDictionary.snapshot.words.keys.any { it.equals(word, ignoreCase = true) }) return
        if (userDictionary.contains(word)) return
        userDictionary.add(word)
    }

    private suspend fun removeFromUserDictionary(word: String): Boolean {
        var removed = false
        val keys =
            userDictionary.snapshot.words.keys.filter { it.equals(word, ignoreCase = true) } + word
        for (key in keys.distinct()) {
            try {
                removed = userDictionary.remove(key) || removed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Removing a learned word failed: ${e.javaClass.name}")
            }
        }
        return removed
    }

    private companion object {
        const val TAG = "LearningHost"
        const val MAX_FAILED_ADDS = 256
    }
}
