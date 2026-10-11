package dev.keycorrect.core.learning.model

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.engineapi.FieldClass
import dev.keycorrect.core.learning.LearningUpdate
import dev.keycorrect.core.learning.LearningUpdate.AddUserWord
import dev.keycorrect.core.learning.LearningUpdate.Preference
import dev.keycorrect.core.learning.LearningUpdate.TouchEvidence
import dev.keycorrect.core.learning.TouchSample
import org.junit.Assert.assertThrows
import org.junit.Test

/** Privacy, atomicity and robustness fixes from the phase-3 review. */
class ReviewFixesTest {
    private val key = "Phone/port/qwerty@54dp"

    // ---- sentence TTL on restore / flush ----

    @Test
    fun importDropsExpiredSentencesWhenGivenTheTime() {
        val b = SentenceBuffer()
        val rows = listOf(SentenceRow(1, "old", 0), SentenceRow(1, "fresh", 6 * DAY_MS))
        b.importRows(rows, nowMillis = 8 * DAY_MS)
        assertThat(b.exportRows().map { it.text }).containsExactly("fresh")
    }

    @Test
    fun restoreDropsExpiredSentencesAndRewritesTheStore() {
        val store = InMemoryLearningStore()
        val m = PersonalModel(testConfig())
        val p = LearningPersistence(m, store)
        m.recordSentenceWord(1, "secret", true, 0)
        m.recordSentenceWord(1, "recent", true, 6 * DAY_MS)
        runSuspend { p.flush(6 * DAY_MS) }
        assertThat(store.state!!.sentences).hasSize(2)

        val m2 = PersonalModel(testConfig())
        val restored = runSuspend { LearningPersistence(m2, store).restore(8 * DAY_MS) }
        assertThat(restored).isTrue()
        assertThat(m2.sentences.exportRows().map { it.text }).containsExactly("recent")
        assertThat(store.state!!.sentences.map { it.text }).containsExactly("recent")
    }

    @Test
    fun flushPrunesExpiredSentencesWithoutAnyNewUpdate() {
        val store = InMemoryLearningStore()
        val m = PersonalModel(testConfig())
        val p = LearningPersistence(m, store)
        m.recordSentenceWord(1, "secret", true, 0)
        runSuspend { p.flush(1_000) }
        assertThat(store.state!!.sentences).hasSize(1)
        runSuspend { p.flush(8 * DAY_MS) }
        assertThat(store.state!!.sentences).isEmpty()
    }

    @Test
    fun discardSessionRemovesOpenAndClosedSentences() {
        val b = SentenceBuffer()
        b.recordSentenceWord(1, "a", true, 1)
        b.recordSentenceWord(2, "b", true, 2)
        b.recordSentenceWord(1, "open", false, 3)
        assertThat(b.discardSession(1)).isEqualTo(1)
        b.closeSession(1, 4)
        assertThat(b.exportRows().map { it.text }).containsExactly("b")
    }

    // ---- forgetSince and the app's user dictionary ----

    @Test
    fun forgetSinceListsOnlyWordsTheModelCreatedInTheStore() {
        val m = PersonalModel(testConfig())
        m.applyAt(
            100,
            AddUserWord("mine", AddUserWord.Reason.Explicit),
            AddUserWord("theirs", AddUserWord.Reason.RevertedAutocorrect),
        )
        assertThat(m.pendingUserDictionaryAdds()).hasSize(2)
        assertThat(m.markUserWordPreExisting("theirs")).isTrue()
        assertThat(m.markUserWordPreExisting("theirs")).isFalse()
        val r = m.forgetSince(0, 200)
        assertThat(r.removedUserWords).containsExactly("mine")
        assertThat(m.userWords()).isEmpty() // the model forgets both
    }

    @Test
    fun preExistingFlagSurvivesExportAndImport() {
        val m = PersonalModel(testConfig())
        m.applyAt(100, AddUserWord("theirs", AddUserWord.Reason.Explicit))
        m.markUserWordPreExisting("theirs")
        val m2 = PersonalModel(testConfig())
        m2.import(m.export())
        assertThat(m2.export().userWords.single().preExisting).isTrue()
        assertThat(m2.forgetSince(0, 200).removedUserWords).isEmpty()
    }

    @Test
    fun forgetSinceKeepsTombstonesSoRemovedWordsAreNotRepromoted() {
        val m = PersonalModel(testConfig())
        m.applyAt(100, AddUserWord("gone", AddUserWord.Reason.Explicit))
        m.pendingUserDictionaryAdds()
        assertThat(m.removeUserWord("gone", 150)).isTrue()
        m.forgetSince(0, 200)
        val tomb = m.export().userWords.single()
        assertThat(tomb.removed).isTrue()
        // Plenty of evidence from several sessions: still not promoted.
        for (s in 1L..5L) m.applyAt(300 + s, LearningUpdate.NewWordEvidence("gone", s, 3f))
        assertThat(m.userWords()).isEmpty()
        assertThat(m.pendingUserDictionaryAdds()).isEmpty()
    }

    @Test
    fun forgetOfATombstonedWordFromForgetUserWordIsAlsoKept() {
        val m = PersonalModel(testConfig())
        assertThat(m.forgetUserWord("never-learned", 100)).isTrue()
        m.forgetSince(0, 200)
        assertThat(m.export().userWords.map { it.removed }).containsExactly(true)
    }

    // ---- atomic persistence ----

    /** Store that records which atomic operation was used and can fail the next commit. */
    private class SpyStore(private val inner: InMemoryLearningStore = InMemoryLearningStore()) :
        LearningStore by inner {
        var failCommit = false
        var cancelCommit = false
        var failSave = false
        var commits = 0
        var replaces = 0
        val journalSize
            get() = inner.journalSize()

        val state
            get() = inner.state

        override suspend fun commit(
            state: PersistedLearning,
            entries: List<JournalEntry>,
            truncateBefore: Long,
        ) {
            if (cancelCommit)
                throw kotlin.coroutines.cancellation.CancellationException("cancelled")
            if (failCommit) throw java.io.IOException("disk")
            commits++
            inner.commit(state, entries, truncateBefore)
        }

        override suspend fun save(state: PersistedLearning) {
            if (failSave) throw java.io.IOException("secret path /data/x")
            inner.save(state)
        }

        override suspend fun replaceAll(state: PersistedLearning, journal: List<JournalEntry>) {
            replaces++
            inner.replaceAll(state, journal)
        }
    }

    @Test
    fun forgetSinceRewritesStateAndJournalInOneStep() {
        val store = SpyStore()
        val m = PersonalModel(testConfig())
        val p = LearningPersistence(m, store)
        m.applyAt(0, used("old", w = 3f))
        m.applyAt(HOUR_MS, used("recent", w = 3f))
        runSuspend { p.flush(HOUR_MS) }
        runSuspend { p.forgetSince(HOUR_MS / 2, HOUR_MS + 1) }
        assertThat(store.replaces).isEqualTo(1)
        assertThat(store.journalSize).isEqualTo(1)
        assertThat(store.state!!.ngrams.map { it.key }).containsExactly("old")
    }

    @Test
    fun forgetCorrectionRewritesStateAndJournalInOneStep() {
        val store = SpyStore()
        val m = PersonalModel(testConfig())
        val p = LearningPersistence(m, store)
        m.applyAt(0, LearningUpdate.CorrectionEvidence("tset", "set", "the|Text", 1f))
        m.applyAt(1, used("x"))
        runSuspend { p.flush(10) }
        assertThat(runSuspend { p.forgetCorrection("tset", "set", 20) }).isTrue()
        assertThat(store.replaces).isEqualTo(1)
        assertThat(store.journalSize).isEqualTo(1)
        assertThat(store.state!!.corrections).isEmpty()
    }

    @Test
    fun flushIsOneCommitAndAFailedCommitKeepsTheJournalEntries() {
        val store = SpyStore()
        val m = PersonalModel(testConfig())
        val p = LearningPersistence(m, store)
        m.applyAt(0, used("a", w = 2f))
        store.failCommit = true
        assertThrows(java.io.IOException::class.java) { runSuspend { p.flush(10) } }
        assertThat(store.journalSize).isEqualTo(0)
        store.failCommit = false
        runSuspend { p.flush(20) }
        assertThat(store.commits).isEqualTo(1)
        assertThat(store.journalSize).isEqualTo(1) // the entry was not lost
        assertThat(store.state!!.ngrams.map { it.key }).containsExactly("a")
    }

    @Test
    fun aCancelledFlushDoesNotRequeueTheJournalEntries() {
        val store = SpyStore()
        val m = PersonalModel(testConfig())
        val p = LearningPersistence(m, store)
        m.applyAt(0, used("a", w = 2f))
        store.cancelCommit = true
        assertThrows(kotlin.coroutines.cancellation.CancellationException::class.java) {
            runSuspend { p.flush(10) }
        }
        store.cancelCommit = false
        assertThat(m.drainJournalForPersistence()).isEmpty() // not requeued
    }

    @Test
    fun aFailedPostPruneSaveDuringRestoreStillSucceedsAndLogsOnlyTheType() {
        val store = SpyStore()
        runSuspend {
            val seed = PersonalModel(testConfig())
            seed.applyAt(0, used("a", w = 2f))
            seed.recordSentenceWord(1, "old", true, 0)
            store.save(seed.export())
        }
        val warnings = ArrayList<String>()
        val m = PersonalModel(testConfig())
        store.failSave = true
        val ok = runSuspend { LearningPersistence(m, store) { warnings += it }.restore(8 * DAY_MS) }
        assertThat(ok).isTrue()
        assertThat(m.sentences.exportRows()).isEmpty()
        assertThat(warnings).hasSize(1)
        assertThat(warnings.single()).contains("java.io.IOException")
        assertThat(warnings.single()).doesNotContain("secret")
        // The next flush persists the pruned state.
        store.failSave = false
        runSuspend { LearningPersistence(m, store).flush(8 * DAY_MS) }
        assertThat(store.state!!.sentences).isEmpty()
    }

    @Test
    fun defaultCommitWritesStateBeforeTheJournal() {
        val order = ArrayList<String>()
        val store =
            object : LearningStore {
                override suspend fun load() = null

                override suspend fun save(state: PersistedLearning) {
                    order += "save"
                }

                override suspend fun appendJournal(entries: List<JournalEntry>) {
                    order += "append"
                }

                override suspend fun journalSince(timeMillis: Long) = emptyList<JournalEntry>()

                override suspend fun truncateJournalBefore(timeMillis: Long) {
                    order += "truncate"
                }

                override suspend fun clear() = Unit
            }
        val m = PersonalModel(testConfig())
        m.applyAt(0, used("a", w = 2f))
        runSuspend { LearningPersistence(m, store).flush(10) }
        assertThat(order).containsExactly("save", "append", "truncate").inOrder()
    }

    // ---- touch outliers ----

    @Test
    fun adjacentKeyErrorsDoNotMoveTheLearnedOffset() {
        val clean = PersonalModel(testConfig())
        val noisy = PersonalModel(testConfig())
        val good = List(100) { TouchSample('f', 0.1f, 0.05f) }
        // A few taps that hit the neighbouring key (offset about 1.0 from the intended one).
        val errors =
            List(8) { TouchSample('f', 1.0f, 0.0f) } + List(4) { TouchSample('f', -0.1f, 1.05f) }
        clean.applyAt(0, TouchEvidence(key, good))
        noisy.applyAt(0, TouchEvidence(key, good + errors))
        val a = clean.snapshot().touchOffset(key, 'f')!!
        val b = noisy.snapshot().touchOffset(key, 'f')!!
        assertThat(b.first).isWithin(1e-3f).of(a.first)
        assertThat(b.second).isWithin(1e-3f).of(a.second)
        assertThat(noisy.touchStatistics().profile(key)!!.sampleCount).isEqualTo(100)
    }

    @Test
    fun rejectedSamplesAreAlsoIgnoredWhenForgotten() {
        val m = PersonalModel(testConfig())
        val good = List(40) { TouchSample('f', 0.1f, 0.0f) }
        m.applyAt(0, TouchEvidence(key, good))
        m.applyAt(10, TouchEvidence(key, List(5) { TouchSample('f', 1.0f, 0.0f) }))
        m.forgetSince(5, 20) // the all-outlier word contributed nothing, so nothing is subtracted
        val p = m.touchStatistics().profile(key)!!
        assertThat(p.sampleCount).isEqualTo(40)
        assertThat(p.wordCount).isEqualTo(1)
    }

    @Test
    fun aMildlyOffTapStillCounts() {
        val m = PersonalModel(testConfig())
        m.applyAt(0, TouchEvidence(key, List(40) { TouchSample('d', 0.6f, -0.6f) }))
        assertThat(m.snapshot().touchOffset(key, 'd')).isNotNull()
    }

    // ---- downgrade ----

    @Test
    fun aNewerFormatIsRefusedAndRestoreFailsWithoutTouchingTheModel() {
        val store = InMemoryLearningStore()
        runSuspend {
            store.save(PersistedLearning(formatVersion = PersistedLearning.FORMAT_VERSION + 1))
        }
        val m = PersonalModel(testConfig())
        m.applyAt(0, used("kept", w = 2f))
        val before = m.export()
        assertThrows(IllegalArgumentException::class.java) {
            runSuspend { LearningPersistence(m, store).restore(10) }
        }
        assertThat(m.export()).isEqualTo(before)
    }

    // ---- Preference field ----

    @Test
    fun preferenceFieldDefaultsToText() {
        assertThat(Preference("a", emptyList(), "b", "c", 1f).field).isEqualTo(FieldClass.Text)
    }

    @Test
    fun fieldAwareProviderReceivesThePreferencesOwnField() {
        val seen = ArrayList<FieldClass>()
        val m = PersonalModel(testConfig())
        m.fieldRankingFeatureProvider = { _, _, c, field ->
            seen += field
            if (c == "good") feat(userLm = -1f) else feat(userLm = -6f)
        }
        m.applyAt(0, Preference("gd", emptyList(), "good", "bad", 1f, FieldClass.PersonName))
        assertThat(seen).containsExactly(FieldClass.PersonName, FieldClass.PersonName)
    }
}
