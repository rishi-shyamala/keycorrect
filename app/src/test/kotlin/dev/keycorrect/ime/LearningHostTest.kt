package dev.keycorrect.ime

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.data.InMemoryUserDictionaryStore
import dev.keycorrect.core.data.UserDictionaryStore
import dev.keycorrect.core.data.UserWord
import dev.keycorrect.core.engineapi.FieldClass
import dev.keycorrect.core.learning.CommitMethod
import dev.keycorrect.core.learning.DeviceClass
import dev.keycorrect.core.learning.InputProfile
import dev.keycorrect.core.learning.LearningEvent
import dev.keycorrect.core.learning.LearningUpdate
import dev.keycorrect.core.learning.Personalization
import dev.keycorrect.core.learning.UserAction
import dev.keycorrect.core.learning.WordCommitted
import dev.keycorrect.core.learning.model.InMemoryLearningStore
import dev.keycorrect.core.learning.model.LearningStore
import dev.keycorrect.core.learning.model.PersistedLearning
import dev.keycorrect.core.learning.model.PersonalModel
import dev.keycorrect.core.learning.model.PersonalModelConfig
import dev.keycorrect.core.settings.KeyboardSettings
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

private const val BASE = 1_700_000_000_000L
private const val MINUTE = 60_000L

private val PROFILE = InputProfile(DeviceClass.Phone, false, "letters@54dp", FieldClass.Text)

/** JVM tests of [LearningHost] with the in-memory learning and user-dictionary stores. */
@OptIn(ExperimentalCoroutinesApi::class)
class LearningHostTest {
    /** Store whose first [failLoads] loads throw and whose loads wait for [gate] when set. */
    private class FakeStore(
        val inner: InMemoryLearningStore = InMemoryLearningStore(),
        var failLoads: Int = 0,
        var gate: CompletableDeferred<Unit>? = null,
    ) : LearningStore by inner {
        var saves = 0

        override suspend fun load(): PersistedLearning? {
            gate?.await()
            if (failLoads > 0) {
                failLoads--
                throw IOException("locked")
            }
            return inner.load()
        }

        override suspend fun save(state: PersistedLearning) {
            saves++
            inner.save(state)
        }

        override suspend fun commit(
            state: PersistedLearning,
            entries: List<dev.keycorrect.core.learning.model.JournalEntry>,
            truncateBefore: Long,
        ) {
            saves++
            inner.commit(state, entries, truncateBefore)
        }
    }

    private class Fixture(
        val scope: TestScope,
        val store: FakeStore = FakeStore(),
        val users: UserDictionaryStore = InMemoryUserDictionaryStore(),
        config: LearningHostConfig = LearningHostConfig(),
        val settings: KeyboardSettings = KeyboardSettings(),
        val model: PersonalModel = PersonalModel(PersonalModelConfig().eager()),
    ) {
        val now: Long
            get() = BASE + scope.testScheduler.currentTime

        val host =
            LearningHost(
                openStore = { store },
                userDictionary = users,
                scope = scope.backgroundScope,
                wallClock = { now },
                model = model,
                config = config,
            )

        fun start(settings: KeyboardSettings? = this.settings) {
            host.start(kotlinx.coroutines.flow.MutableStateFlow(settings ?: KeyboardSettings()))
            scope.testScheduler.runCurrent() // the settings arrive before anyone types
        }

        suspend fun settle() {
            scope.testScheduler.advanceUntilIdle()
            host.awaitIdle()
        }

        fun typed(word: String, at: Long = now): LearningEvent =
            WordCommitted(
                timeMillis = at,
                profile = PROFILE,
                context = listOf("the"),
                typed = word,
                committed = word,
                method = CommitMethod.Typed,
            )

        fun addWord(word: String, at: Long = now): LearningEvent =
            UserAction(at, PROFILE, UserAction.Kind.AddToDictionary, word)

        fun block(word: String, at: Long = now): LearningEvent =
            UserAction(at, PROFILE, UserAction.Kind.NeverSuggest, word)
    }

    private fun fixture(
        scope: TestScope,
        config: LearningHostConfig = LearningHostConfig(),
        store: FakeStore = FakeStore(),
        settings: KeyboardSettings = KeyboardSettings(),
    ) = Fixture(scope, store, config = config, settings = settings)

    private fun UserDictionaryStore.snapshotWords(): Set<String> = snapshot.words.keys

    // ---------------------------------------------------------------- flow to model + store

    @Test
    fun eventsFlowToModelAndUserDictionary() = runTest {
        val f = fixture(this)
        f.start()
        f.settle()
        assertThat(f.host.state.value).isEqualTo(LearningHostState.Ready)

        f.host.onEvent(f.addWord("zorp"))
        f.host.onEvent(f.block("grault"))
        f.host.onEvent(f.typed("hello"))
        f.settle()

        assertThat(f.host.learnedWords().map { it.word }).containsExactly("zorp")
        assertThat(f.host.blockedWords()).containsExactly("grault")
        assertThat(f.users.snapshotWords()).containsExactly("zorp")
        assertThat(f.model.snapshot().isBlocked("grault")).isTrue()
        assertThat(f.model.export().ngrams).isNotEmpty()
    }

    @Test
    fun addedWordKeepsExistingUserDictionaryEntry() = runTest {
        val f = fixture(this)
        f.users.add("Zorp", frequency = 200)
        f.start()
        f.host.onEvent(f.addWord("zorp"))
        f.settle()

        assertThat(f.users.snapshotWords()).containsExactly("Zorp")
        assertThat(f.users.snapshot["Zorp"]!!.frequency).isEqualTo(200)
    }

    @Test
    fun failedUserDictionaryAddIsRetried() = runTest {
        val inner = InMemoryUserDictionaryStore()
        var failures = 1
        val flaky =
            object : UserDictionaryStore by inner {
                override suspend fun add(word: String, frequency: Int, locale: String?): UserWord {
                    if (failures-- > 0) throw IOException("locked")
                    return inner.add(word, frequency, locale)
                }
            }
        val f = Fixture(this, users = flaky)
        f.start()
        f.host.onEvent(f.addWord("zorp"))
        f.settle()
        assertThat(inner.snapshotWords()).isEmpty()

        f.host.onEvent(f.typed("again"))
        f.settle()
        assertThat(inner.snapshotWords()).containsExactly("zorp")
    }

    // ---------------------------------------------------------------- gating

    @Test
    fun nothingIsAcceptedBeforeSettingsArrive() = runTest {
        val f = fixture(this)
        f.host.start(kotlinx.coroutines.flow.emptyFlow())
        f.settle()
        f.host.onEvent(f.addWord("zorp"))
        f.host.onSentenceWord(1, "hello", true, f.now)
        f.settle()

        assertThat(f.host.learnedWords()).isEmpty()
        assertThat(f.host.sentenceBufferSize()).isEqualTo(0)
    }

    @Test
    fun learningOffAndIncognitoStopEvents() = runTest {
        val f = fixture(this)
        f.start()
        f.settle()

        f.host.applySettings(KeyboardSettings(learningEnabled = false))
        f.host.onEvent(f.addWord("one"))
        f.host.onSentenceWord(1, "hello", true, f.now)
        f.settle()
        assertThat(f.host.learnedWords()).isEmpty()
        assertThat(f.host.sentenceBufferSize()).isEqualTo(0)
        assertThat(f.host.personalization()).isSameInstanceAs(Personalization.None)

        f.host.applySettings(KeyboardSettings(incognito = true))
        f.host.onEvent(f.addWord("two"))
        f.settle()
        assertThat(f.host.learnedWords()).isEmpty()

        f.host.applySettings(KeyboardSettings())
        f.host.onEvent(f.addWord("three"))
        f.settle()
        assertThat(f.host.learnedWords().map { it.word }).containsExactly("three")
        assertThat(f.host.personalization()).isNotSameInstanceAs(Personalization.None)
    }

    @Test
    fun queuedEventsAreDroppedWhenTheGateClosesBeforeTheyAreHandled() = runTest {
        val f = fixture(this)
        f.start()
        f.settle()

        f.host.onEvent(f.addWord("late")) // queued, not handled yet (the test thread is the loop)
        f.host.applySettings(KeyboardSettings(incognito = true))
        f.settle()

        assertThat(f.host.learnedWords()).isEmpty()
    }

    @Test
    fun disablingTheSentenceBufferClearsItAndItsSavedCopy() = runTest {
        val f = fixture(this)
        f.start()
        f.host.onSentenceWord(7, "hello", false, f.now)
        f.host.onSentenceWord(7, "world", true, f.now)
        f.settle()
        assertThat(f.host.sentenceBufferSize()).isEqualTo(1)
        f.host.flush()
        assertThat(f.store.inner.state!!.sentences).hasSize(1)

        f.host.applySettings(KeyboardSettings(sentenceBufferEnabled = false))
        f.settle()
        assertThat(f.host.sentenceBufferSize()).isEqualTo(0)
        assertThat(f.store.inner.state!!.sentences).isEmpty()

        f.host.onSentenceWord(8, "more", true, f.now)
        f.settle()
        assertThat(f.host.sentenceBufferSize()).isEqualTo(0)
        // Events still learn with the buffer off.
        f.host.onEvent(f.addWord("zorp"))
        f.settle()
        assertThat(f.host.learnedWords().map { it.word }).containsExactly("zorp")
    }

    @Test
    fun restoredSentencesAreClearedWhenTheBufferIsOff() = runTest {
        val first = fixture(this)
        first.start()
        first.host.onSentenceWord(1, "secret", true, first.now)
        first.settle()
        first.host.flush()
        assertThat(first.store.inner.state!!.sentences).hasSize(1)

        val second = fixture(this, store = first.store)
        second.start(KeyboardSettings(sentenceBufferEnabled = false))
        second.settle()
        assertThat(second.host.sentenceBufferSize()).isEqualTo(0)
    }

    // ---------------------------------------------------------------- restore

    @Test
    fun eventsBeforeRestoreQueueAndApplyOnTopOfTheRestoredState() = runTest {
        val seeded = fixture(this)
        seeded.start()
        seeded.host.onEvent(seeded.addWord("saved"))
        seeded.settle()
        seeded.host.flush()

        val gate = CompletableDeferred<Unit>()
        val store = FakeStore(seeded.store.inner, gate = gate)
        val f = fixture(this, store = store)
        f.start()
        f.host.onEvent(f.addWord("fresh"))
        testScheduler.advanceUntilIdle()
        assertThat(f.host.state.value).isEqualTo(LearningHostState.Restoring)
        assertThat(f.model.userWords()).isEmpty()

        gate.complete(Unit)
        f.settle()

        assertThat(f.host.state.value).isEqualTo(LearningHostState.Ready)
        assertThat(f.host.learnedWords().map { it.word }).containsExactly("saved", "fresh")
    }

    @Test
    fun queueBeforeRestoreIsBounded() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = fixture(this, LearningHostConfig(maxQueuedInputs = 2), FakeStore(gate = gate))
        f.start()
        f.host.onEvent(f.addWord("one"))
        f.host.onEvent(f.addWord("two"))
        f.host.onEvent(f.addWord("three"))
        gate.complete(Unit)
        f.settle()

        assertThat(f.host.learnedWords().map { it.word }).containsExactly("one", "two")
    }

    @Test
    fun failedRestoreNeverOverwritesTheStoredStateAndIsRetried() = runTest {
        val seeded = fixture(this)
        seeded.start()
        seeded.host.onEvent(seeded.addWord("saved"))
        seeded.settle()
        seeded.host.flush()
        val savedBefore = seeded.store.inner.state

        val store = FakeStore(seeded.store.inner, failLoads = 1)
        val f = fixture(this, LearningHostConfig(restoreRetryMillis = MINUTE), store)
        f.start()
        f.settle()
        assertThat(f.host.state.value).isEqualTo(LearningHostState.Unavailable)

        f.host.onEvent(f.addWord("dropped"))
        f.host.requestFlush(FlushReason.TrimMemory)
        f.settle()
        assertThat(f.model.userWords()).isEmpty()
        assertThat(store.inner.state).isSameInstanceAs(savedBefore)

        advanceTimeBy(2 * MINUTE)
        f.host.onEvent(f.addWord("kept"))
        f.settle()
        assertThat(f.host.state.value).isEqualTo(LearningHostState.Ready)
        assertThat(f.host.learnedWords().map { it.word }).containsExactly("saved", "kept")
    }

    // ---------------------------------------------------------------- flush policy

    @Test
    fun keyboardHiddenSavesWhenDirtyAndNotMoreOftenThanTheGap() = runTest {
        val f = fixture(this, LearningHostConfig(minFlushGapMillis = 15_000))
        f.start()
        f.settle()

        f.host.requestFlush(FlushReason.KeyboardHidden) // nothing to save
        f.settle()
        assertThat(f.store.saves).isEqualTo(0)

        advanceTimeBy(60_000)
        f.host.onEvent(f.addWord("one"))
        f.host.requestFlush(FlushReason.KeyboardHidden)
        f.settle()
        assertThat(f.store.saves).isEqualTo(1)
        assertThat(f.store.inner.state!!.userWords).isNotEmpty()

        // Within the gap: deferred, then saved by the timer.
        f.host.onEvent(f.addWord("two"))
        f.host.requestFlush(FlushReason.KeyboardHidden)
        f.host.awaitIdle()
        assertThat(f.store.saves).isEqualTo(1)
        advanceTimeBy(16_000)
        f.settle()
        assertThat(f.store.saves).isEqualTo(2)
    }

    @Test
    fun trimMemorySavesAtOnce() = runTest {
        val f = fixture(this)
        f.start()
        advanceTimeBy(20_000)
        f.host.onEvent(f.addWord("one"))
        f.host.requestFlush(FlushReason.KeyboardHidden)
        f.settle()
        f.host.onEvent(f.addWord("two"))
        f.host.requestFlush(FlushReason.TrimMemory)
        f.host.awaitIdle()
        assertThat(f.store.saves).isEqualTo(2)
    }

    @Test
    fun savesAfterEnoughUpdates() = runTest {
        val f =
            fixture(
                this,
                LearningHostConfig(flushEveryUpdates = 3, minFlushGapMillis = 0),
            )
        f.start()
        f.settle()
        f.host.onEvent(f.addWord("one"))
        f.host.onEvent(f.addWord("two"))
        f.host.awaitIdle()
        assertThat(f.store.saves).isEqualTo(0)
        f.host.onEvent(f.addWord("three"))
        f.host.awaitIdle()
        assertThat(f.store.saves).isEqualTo(1)
    }

    @Test
    fun savesAfterTheInterval() = runTest {
        val f =
            fixture(
                this,
                LearningHostConfig(flushIntervalMillis = 5 * MINUTE, minFlushGapMillis = 0),
            )
        f.start()
        f.settle()
        advanceTimeBy(MINUTE)
        f.host.onEvent(f.addWord("one"))
        f.host.awaitIdle()
        assertThat(f.store.saves).isEqualTo(0)
        advanceTimeBy(5 * MINUTE)
        f.host.onEvent(f.addWord("two"))
        f.host.awaitIdle()
        assertThat(f.store.saves).isEqualTo(1)
    }

    @Test
    fun trailingUpdatesArePublishedAfterAQuietPeriod() = runTest {
        val model =
            PersonalModel(
                PersonalModelConfig()
                    .copy(publishEveryUpdates = 1_000, publishMinIntervalMillis = 1_000_000)
            )
        val f = Fixture(this, model = model)
        f.start()
        f.settle()
        f.host.onEvent(f.typed("hello"))
        f.host.awaitIdle()
        val before = model.snapshot().version
        advanceTimeBy(2_000)
        f.host.awaitIdle()
        assertThat(model.snapshot().version).isGreaterThan(before)
    }

    // ---------------------------------------------------------------- management

    @Test
    fun forgetSinceRemovesRecentWordsFromTheModelAndTheUserDictionary() = runTest {
        val f = fixture(this)
        f.start()
        f.settle()
        advanceTimeBy(3 * 60 * MINUTE)
        f.host.onEvent(f.addWord("olda"))
        advanceTimeBy(2 * 60 * MINUTE)
        f.host.onEvent(f.addWord("newb"))
        f.host.onEvent(f.block("grault"))
        advanceTimeBy(MINUTE)
        f.settle()
        assertThat(f.users.snapshotWords()).containsExactly("olda", "newb")

        val result = f.host.forgetSince(30)

        assertThat(result.removedUserWords).containsExactly("newb")
        assertThat(result.removedBlocks).containsExactly("grault")
        assertThat(f.users.snapshotWords()).containsExactly("olda")
        assertThat(f.host.learnedWords().map { it.word }).containsExactly("olda")
        assertThat(f.host.blockedWords()).isEmpty()
        // The store matches the model.
        assertThat(f.store.inner.state!!.userWords.map { it.word }).containsExactly("olda")
    }

    @Test
    fun removeLearnedWordUnblockAndRemoveSuppression() = runTest {
        val f = fixture(this)
        f.start()
        f.host.onEvent(f.addWord("zorp"))
        f.host.onEvent(f.block("grault"))
        f.settle()

        assertThat(f.host.unblock("grault")).isTrue()
        assertThat(f.host.blockedWords()).isEmpty()
        assertThat(f.host.removeSuppression("a", "b")).isFalse()
        assertThat(f.host.removeLearnedWord("zorp")).isTrue()
        assertThat(f.host.learnedWords()).isEmpty()
        assertThat(f.users.snapshotWords()).isEmpty()
        assertThat(f.store.inner.state!!.blocks).isEmpty()
    }

    @Test
    fun topCorrectionsAndForgetCorrection() = runTest {
        val f = fixture(this)
        f.start()
        f.model.apply(
            listOf(LearningUpdate.CorrectionEvidence("teh", "the", "in|Text", 1f)),
            f.now,
        )
        assertThat(f.host.topCorrections(5).map { it.typed to it.target })
            .containsExactly("teh" to "the")
        assertThat(f.host.topCorrections(0)).isEmpty()

        assertThat(f.host.forgetCorrection("teh", "the")).isTrue()
        assertThat(f.host.topCorrections(5)).isEmpty()
        assertThat(f.store.inner.state!!.corrections).isEmpty()
        assertThat(f.host.forgetCorrection("teh", "the")).isFalse()
    }

    @Test
    fun onUserWordRemovedStopsTheModelTreatingItAsLearned() = runTest {
        val f = fixture(this)
        f.start()
        f.host.onEvent(f.addWord("zorp"))
        f.settle()
        assertThat(f.host.learnedWords().map { it.word }).containsExactly("zorp")

        // The dictionary screen removed it from the store; now the model is told.
        f.users.remove("zorp")
        assertThat(f.host.onUserWordRemoved("zorp")).isTrue()
        assertThat(f.host.learnedWords()).isEmpty()
        assertThat(f.model.snapshot().isUserWord("zorp")).isFalse()
        assertThat(f.store.inner.state!!.userWords.single().removed).isTrue() // saved at once

        // Old evidence does not bring it back; an explicit add does.
        f.model.apply(
            listOf(
                LearningUpdate.NewWordEvidence("zorp", 1, 1f),
                LearningUpdate.NewWordEvidence("zorp", 2, 1f),
                LearningUpdate.NewWordEvidence("zorp", 3, 1f),
            ),
            f.now,
        )
        f.settle()
        assertThat(f.host.learnedWords()).isEmpty()
        assertThat(f.host.onUserWordRemoved("zorp")).isFalse()
        f.host.onEvent(f.addWord("zorp"))
        f.settle()
        assertThat(f.host.learnedWords().map { it.word }).containsExactly("zorp")
    }

    @Test
    fun onUserWordRemovedWorksWhileLearningIsOff() = runTest {
        val f = fixture(this, settings = KeyboardSettings(learningEnabled = true))
        f.start()
        f.host.onEvent(f.addWord("zorp"))
        f.settle()
        f.host.applySettings(KeyboardSettings(learningEnabled = false))
        assertThat(f.host.onUserWordRemoved("zorp")).isTrue()
        assertThat(f.host.learnedWords()).isEmpty()
    }

    @Test
    fun resetAllClearsLearningButLeavesTheUserDictionary() = runTest {
        val f = fixture(this)
        f.start()
        f.host.onEvent(f.addWord("zorp"))
        f.host.onEvent(f.block("grault"))
        f.host.onSentenceWord(1, "hello", true, f.now)
        f.settle()
        f.host.flush()
        assertThat(f.store.inner.state).isNotNull()

        f.host.resetAll()

        assertThat(f.store.inner.state).isNull()
        assertThat(f.host.overview())
            .isEqualTo(LearningOverview(emptyList(), emptyList(), emptyList(), 0))
        assertThat(f.users.snapshotWords()).containsExactly("zorp")
    }

    @Test
    fun resetAllRecoversAnUnavailableStore() = runTest {
        val f = fixture(this, store = FakeStore(failLoads = 100))
        f.start()
        f.settle()
        assertThat(f.host.state.value).isEqualTo(LearningHostState.Unavailable)

        f.host.resetAll()

        assertThat(f.host.state.value).isEqualTo(LearningHostState.Ready)
    }

    @Test
    fun mutationsFailWhileUnavailable() = runTest {
        val f = fixture(this, store = FakeStore(failLoads = 100))
        f.start()
        f.settle()
        val failure = runCatching { f.host.forgetSince(10) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun clearSentenceBufferRemovesTheSavedCopy() = runTest {
        val f = fixture(this)
        f.start()
        f.host.onSentenceWord(1, "hello", true, f.now)
        f.settle()
        f.host.flush()
        assertThat(f.store.inner.state!!.sentences).hasSize(1)

        f.host.clearSentenceBuffer()

        assertThat(f.host.sentenceBufferSize()).isEqualTo(0)
        assertThat(f.store.inner.state!!.sentences).isEmpty()
    }

    @Test
    fun overviewFlowEmitsAgainAfterChanges() = runTest {
        val f = fixture(this)
        f.start()
        f.settle()
        val seen = mutableListOf<LearningOverview>()
        val job = backgroundScope.launchCollect(f.host.overviewFlow(), seen)
        testScheduler.advanceUntilIdle()
        val first = seen.size
        f.host.onEvent(f.addWord("zorp"))
        f.settle()
        advanceTimeBy(5_000)
        testScheduler.advanceUntilIdle()
        assertThat(seen.size).isGreaterThan(first)
        assertThat(seen.last().userWords.map { it.word }).containsExactly("zorp")
        job.cancel()
    }

    private fun keep(at: Long): LearningEvent =
        dev.keycorrect.core.learning.AutocorrectReverted(
            timeMillis = at,
            profile = PROFILE,
            context = listOf("the"),
            typed = "tset",
            corrected = "test",
            kind = dev.keycorrect.core.learning.RevertKind.ImmediateBackspace,
            replacement = "tset",
        )

    @Test
    fun revertThresholdsComeFromTheSettingsAndApplyRetroactively() = runTest {
        val f =
            fixture(
                this,
                settings = KeyboardSettings(implicitAddReverts = 3, suppressCorrectionReverts = 3),
            )
        f.start()
        f.settle()
        f.host.onEvent(keep(f.now))
        f.host.onEvent(keep(f.now + 1))
        f.settle()
        assertThat(f.model.thresholds()).isEqualTo(3 to 3)
        assertThat(f.users.snapshotWords()).isEmpty()
        assertThat(f.host.suppressedCorrections()).isEmpty()

        // Lowering N to 2 adds the word that already has two keeps; M below N is clamped to N.
        f.host.applySettings(
            KeyboardSettings(implicitAddReverts = 2, suppressCorrectionReverts = 1)
        )
        f.settle()
        assertThat(f.model.thresholds()).isEqualTo(2 to 2)
        assertThat(f.users.snapshotWords()).containsExactly("tset")
        assertThat(f.host.suppressedCorrections()).containsExactly("tset" to "test")
    }

    @Test
    fun oneKeepAddsAtThresholdOneAndOutOfRangeValuesAreClamped() = runTest {
        val f =
            fixture(
                this,
                settings = KeyboardSettings(implicitAddReverts = 1, suppressCorrectionReverts = 1),
            )
        f.start()
        f.settle()
        f.host.onEvent(keep(f.now))
        f.settle()
        assertThat(f.users.snapshotWords()).containsExactly("tset")
        f.host.applySettings(
            KeyboardSettings(implicitAddReverts = 99, suppressCorrectionReverts = 0)
        )
        f.settle()
        assertThat(f.model.thresholds()).isEqualTo(5 to 5)
    }

    private fun fieldEvent(f: Fixture, field: FieldClass, word: String, session: Long = 5L) =
        WordCommitted(
            timeMillis = f.now,
            profile = PROFILE.copy(field = field),
            context = listOf("the"),
            typed = word,
            committed = word,
            method = CommitMethod.Typed,
            sessionId = session,
        )

    @Test
    fun emailUrlAndAddressFieldsAreNeverLearnedButNamesAre() = runTest {
        val f = fixture(this)
        f.start()
        for (field in listOf(FieldClass.Email, FieldClass.Url, FieldClass.PostalAddress)) {
            f.host.onEvent(fieldEvent(f, field, "secretword"))
        }
        f.settle()
        assertThat(f.model.export().ngrams).isEmpty()
        f.host.onEvent(fieldEvent(f, FieldClass.PersonName, "Zorbert", 9))
        f.settle()
        assertThat(f.model.export().ngrams).isNotEmpty()
    }

    @Test
    fun personNameFieldsNeverRecordSentenceWords() = runTest {
        val f = fixture(this)
        f.start()
        f.host.onEvent(fieldEvent(f, FieldClass.PersonName, "Zorbert", 9))
        f.host.onSentenceWord(9, "Zorbert", true, f.now)
        f.host.onSentenceWord(10, "hello", true, f.now, FieldClass.PersonName)
        f.host.onSentenceWord(11, "fine", true, f.now, FieldClass.Text)
        f.settle()
        assertThat(f.model.sentences.exportRows().map { it.text }).containsExactly("fine")
    }

    @Test
    fun expiredSentencesArePrunedPeriodicallyWithoutNewUpdates() = runTest {
        val f = fixture(this, LearningHostConfig(sentencePruneIntervalMillis = 10 * MINUTE))
        f.start()
        f.host.onSentenceWord(1, "secret", true, f.now)
        f.settle()
        f.host.flush()
        assertThat(f.store.inner.state!!.sentences).hasSize(1)
        advanceTimeBy(8 * 24 * 60 * MINUTE)
        f.settle()
        assertThat(f.host.sentenceBufferSize()).isEqualTo(0)
        assertThat(f.store.inner.state!!.sentences).isEmpty()
    }

    @Test
    fun forgetSinceNeverDeletesAWordTheUserDictionaryAlreadyHad() = runTest {
        val f = fixture(this)
        f.users.add("Zorp", frequency = 200)
        f.start()
        f.host.onEvent(f.addWord("zorp"))
        f.host.onEvent(f.addWord("newb"))
        f.settle()
        val result = f.host.forgetSince(30)
        assertThat(result.removedUserWords).containsExactly("newb")
        assertThat(f.users.snapshotWords()).containsExactly("Zorp")
    }

    @Test
    fun aNewerSavedFormatIsUnavailableNotACrash() = runTest {
        val store = FakeStore()
        store.inner.save(PersistedLearning(formatVersion = PersistedLearning.FORMAT_VERSION + 1))
        val f = fixture(this, store = store)
        f.start()
        f.settle()
        assertThat(f.host.state.value).isEqualTo(LearningHostState.Unavailable)
        f.host.onEvent(f.addWord("dropped"))
        f.host.requestFlush(FlushReason.TrimMemory)
        f.settle()
        assertThat(store.inner.state!!.formatVersion)
            .isEqualTo(PersistedLearning.FORMAT_VERSION + 1)
    }
}
