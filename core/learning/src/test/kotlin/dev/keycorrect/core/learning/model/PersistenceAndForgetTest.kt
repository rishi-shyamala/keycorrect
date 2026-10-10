package dev.keycorrect.core.learning.model

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.learning.LearningUpdate
import dev.keycorrect.core.learning.LearningUpdate.AddUserWord
import dev.keycorrect.core.learning.LearningUpdate.AutocorrectOutcome
import dev.keycorrect.core.learning.LearningUpdate.BlockWord
import dev.keycorrect.core.learning.LearningUpdate.CasingEvidence
import dev.keycorrect.core.learning.LearningUpdate.CorrectionEvidence
import dev.keycorrect.core.learning.LearningUpdate.NewWordEvidence
import dev.keycorrect.core.learning.LearningUpdate.PhraseFeedback
import dev.keycorrect.core.learning.LearningUpdate.Preference
import dev.keycorrect.core.learning.LearningUpdate.SuppressCorrection
import dev.keycorrect.core.learning.LearningUpdate.TouchEvidence
import dev.keycorrect.core.learning.TouchSample
import org.junit.Test

class PersistenceAndForgetTest {
    private val key = "Phone/port/qwerty@54dp"
    private val bucket = "the|Text"

    private fun provider(): RankingFeatureProvider = { _, _, c ->
        if (c == "good") feat(userLm = -1f) else feat(userLm = -6f)
    }

    private fun pref() = Preference("gd", listOf("a"), "good", "bad", 1f)

    private fun richBatch(sessionBase: Long): List<LearningUpdate> = buildList {
        repeat(6) { add(used("hello", "say")) }
        repeat(4) { add(used("world", "hello", "say")) }
        add(CorrectionEvidence("tset", "set", bucket, 1f))
        add(CasingEvidence("iPhone"))
        add(CasingEvidence("iPhone"))
        add(NewWordEvidence("zorblax", sessionBase, 1f))
        add(NewWordEvidence("zorblax", sessionBase + 1, 1f))
        add(SuppressCorrection("teh", "the"))
        add(BlockWord("darn"))
        add(AddUserWord("kubectl", AddUserWord.Reason.Explicit))
        add(TouchEvidence(key, List(40) { TouchSample('a', 0.1f, -0.1f) }))
        add(AutocorrectOutcome(true))
        add(AutocorrectOutcome(false))
        add(PhraseFeedback(4, 1))
        add(pref())
    }

    // ---- codec ----

    @Test
    fun journalCodecRoundTripsEveryUpdateType() {
        val weird = "we%ird\u001Fsep\u001Erec’s\né"
        val all =
            listOf(
                LearningUpdate.WordUsed(listOf("a", weird, ""), weird, 0.3f),
                LearningUpdate.WordUsed(emptyList(), "", 1f),
                NewWordEvidence(weird, -7L, 1.25f),
                AddUserWord(weird, AddUserWord.Reason.RevertedAutocorrect),
                CorrectionEvidence(weird, "t", "", -0.5f),
                SuppressCorrection("a", weird),
                BlockWord(weird),
                TouchEvidence(
                    key,
                    listOf(TouchSample('é', 0.1f, -0.25f), TouchSample(',', 1e-5f, 3f)),
                ),
                TouchEvidence(key, emptyList()),
                Preference("x", listOf(weird), "y", "z", 2f),
                AutocorrectOutcome(true),
                PhraseFeedback(5, 2),
                CasingEvidence("McDonald's"),
            )
        for (u in all) {
            val text = JournalCodec.encode(u)
            assertThat(text).startsWith("v1\u001F")
            assertThat(JournalCodec.decode(text)).isEqualTo(u)
        }
    }

    @Test
    fun journalCodecRejectsUnknownOrCorruptPayloads() {
        assertThat(JournalCodec.decode("")).isNull()
        assertThat(JournalCodec.decode("v2\u001FW\u001F0\u001Fa\u001F1.0")).isNull()
        assertThat(JournalCodec.decode("v1\u001FZ\u001F1")).isNull()
        assertThat(JournalCodec.decode("v1\u001FW\u001Fnot-a-list")).isNull()
        assertThat(JournalCodec.decode("garbage")).isNull()
    }

    // ---- export / import ----

    @Test
    fun exportImportRoundTripIsLossless() {
        val a = PersonalModel(testConfig())
        a.rankingFeatureProvider = provider()
        a.applyAt(1_000, *richBatch(1).toTypedArray())
        a.applyAt(2 * HOUR_MS, *Array(30) { pref() })
        a.recordSentenceWord(1, "hello", false, 5)
        a.recordSentenceWord(1, "world", true, 6)
        val state = a.export()
        assertThat(state.ngrams).isNotEmpty()
        assertThat(state.touch).isNotEmpty()
        assertThat(state.sentences).hasSize(1)
        assertThat(state.pendingAdds).isNotEmpty()

        val b = PersonalModel(testConfig())
        b.import(state)
        assertThat(b.export()).isEqualTo(state)
        assertThat(b.pendingUserDictionaryAdds().map { it.word })
            .containsExactlyElementsIn(a.pendingUserDictionaryAdds().map { it.word })

        val sa = a.snapshot()
        val sb = b.snapshot()
        assertThat(sb.userLogProb(listOf("say"), "hello")!!)
            .isWithin(1e-5f)
            .of(sa.userLogProb(listOf("say"), "hello")!!)
        assertThat(sb.correctionBias("tset", "set", bucket))
            .isEqualTo(sa.correctionBias("tset", "set", bucket))
        assertThat(sb.isSuppressed("teh", "the")).isTrue()
        assertThat(sb.isBlocked("darn")).isTrue()
        assertThat(sb.isUserWord("kubectl")).isTrue()
        assertThat(sb.preferredCasing("iphone")).isEqualTo("iPhone")
        assertThat(sb.touchOffset(key, 'a')).isEqualTo(sa.touchOffset(key, 'a'))
        assertThat(sb.rankerScore(feat(userLm = -1f))).isEqualTo(sa.rankerScore(feat(userLm = -1f)))
        assertThat(sb.autocorrectThresholdDelta).isEqualTo(sa.autocorrectThresholdDelta)
        assertThat(sb.phraseGateDelta).isEqualTo(sa.phraseGateDelta)
        assertThat(b.sentences.recentSentences(10).map { it.text }).containsExactly("hello world")
    }

    @Test
    fun importRejectsNewerFormat() {
        val m = PersonalModel()
        runCatching { m.import(PersistedLearning(formatVersion = 99)) }
            .onSuccess { throw AssertionError("expected failure") }
    }

    @Test
    fun storeBackedRestoreRecoversModelAndJournal() {
        val store = InMemoryLearningStore()
        val a = PersonalModel(testConfig())
        a.applyAt(10_000, *richBatch(1).toTypedArray())
        runSuspend { LearningPersistence(a, store).flush(10_000) }
        assertThat(store.journalSize()).isGreaterThan(0)

        val b = PersonalModel(testConfig())
        val ok = runSuspend { LearningPersistence(b, store).restore(10_000) }
        assertThat(ok).isTrue()
        assertThat(b.export()).isEqualTo(a.export())
        assertThat(b.journalSnapshot()).isEqualTo(a.journalSnapshot())
        assertThat(
                runSuspend {
                    LearningPersistence(PersonalModel(), InMemoryLearningStore()).restore(0)
                }
            )
            .isFalse()
    }

    @Test
    fun journalOlderThanRetentionIsDropped() {
        val m = PersonalModel(testConfig())
        m.applyAt(0, used("old"))
        m.applyAt(25 * HOUR_MS, used("new"))
        assertThat(m.journalSnapshot().map { (it.update as LearningUpdate.WordUsed).word })
            .containsExactly("new")
        val store = InMemoryLearningStore()
        runSuspend {
            store.appendJournal(listOf(JournalEntry(0, used("a")), JournalEntry(50, used("b"))))
            store.truncateJournalBefore(10)
        }
        assertThat(runSuspend { store.journalSince(0) }.map { it.timeMillis }).containsExactly(50L)
    }

    // ---- forget since ----

    @Test
    fun forgetSinceUndoesOnlyRecentLearning() {
        val reference = PersonalModel(testConfig())
        val m = PersonalModel(testConfig())
        for (model in listOf(reference, m)) model.rankingFeatureProvider = provider()

        // Old learning at t = 0 and a later filler at +1h (takes a ranker checkpoint holding the
        // old weights).
        val old =
            listOf(
                used("hello", "say", w = 4f),
                used("hello", "say", w = 4f),
                CorrectionEvidence("tset", "set", bucket, 1f),
                TouchEvidence(key, List(35) { TouchSample('a', 0.1f, 0f) }),
                CasingEvidence("iPhone"),
                NewWordEvidence("zorblax", 1, 1f),
                pref(),
                pref(),
                AutocorrectOutcome(false),
            )
        for (model in listOf(reference, m)) {
            model.apply(old, 0)
            model.apply(listOf(used("filler")), HOUR_MS)
        }

        // Recent learning at +90 minutes that must disappear.
        val recent =
            listOf(
                used("hello", "say", w = 4f),
                used("secret", "my", w = 4f),
                used("secret", "my", w = 4f),
                CorrectionEvidence("tset", "test", bucket, -1f),
                CorrectionEvidence("tset", "set", bucket, 2f),
                TouchEvidence(key, List(40) { TouchSample('a', 0.4f, 0.4f) }),
                CasingEvidence("iPhone"),
                NewWordEvidence("zorblax", 2, 1f), // would have promoted
                SuppressCorrection("teh", "the"),
                BlockWord("darn"),
                AddUserWord("kubectl", AddUserWord.Reason.Explicit),
                Preference("gd", listOf("a"), "bad", "good", 2f),
                Preference("gd", listOf("a"), "bad", "good", 2f),
                AutocorrectOutcome(true),
                PhraseFeedback(4, 0),
            )
        m.apply(recent, 90 * 60_000L)
        assertThat(m.snapshot().isBlocked("darn")).isTrue()
        m.recordSentenceWord(1, "secret", true, 90 * 60_000L)

        val result = m.forgetSince(80 * 60_000L, 95 * 60_000L)

        assertThat(result.removedUserWords).contains("kubectl")
        assertThat(result.removedBlocks).containsExactly("darn")
        assertThat(result.removedSuppressions).containsExactly("teh" to "the")
        assertThat(result.rankerRestoredFromMillis).isEqualTo(HOUR_MS)
        assertThat(result.removedSentences).isEqualTo(1)
        assertThat(result.subtractedUpdates).isGreaterThan(5)

        val s = m.snapshot()
        val r = reference.snapshot()
        assertThat(s.isBlocked("darn")).isFalse()
        assertThat(s.isSuppressed("teh", "the")).isFalse()
        assertThat(s.isUserWord("kubectl")).isFalse()
        assertThat(s.isUserWord("zorblax")).isFalse()
        assertThat(m.pendingUserDictionaryAdds()).isEmpty()
        assertThat(s.userLogProb(listOf("my"), "secret")).isNull()
        assertThat(s.userLogProb(listOf("say"), "hello")!!)
            .isWithin(0.02f)
            .of(r.userLogProb(listOf("say"), "hello")!!)
        assertThat(s.correctionBias("tset", "set", bucket))
            .isWithin(0.02f)
            .of(r.correctionBias("tset", "set", bucket))
        assertThat(s.correctionBias("tset", "test", bucket)).isWithin(0.02f).of(0f)
        assertThat(s.preferredCasing("iphone")).isNull() // only 1 old observation left
        assertThat(s.touchOffset(key, 'a')!!.first).isWithin(0.01f).of(0.1f)
        assertThat(s.touchOffset(key, 'a')!!.second).isWithin(0.01f).of(0f)
        assertThat(s.rankerScore(feat(userLm = -1f))).isEqualTo(r.rankerScore(feat(userLm = -1f)))
        assertThat(s.autocorrectThresholdDelta).isEqualTo(r.autocorrectThresholdDelta)
        assertThat(s.phraseGateDelta).isEqualTo(0f)
        assertThat(m.journalSnapshot().size).isEqualTo(reference.journalSnapshot().size)
        assertThat(m.sentences.size()).isEqualTo(0)

        // Forgetting again is a harmless no-op.
        m.forgetSince(80 * 60_000L, 96 * 60_000L)
        assertThat(m.snapshot().userLogProb(listOf("say"), "hello")!!)
            .isWithin(0.02f)
            .of(r.userLogProb(listOf("say"), "hello")!!)
    }

    @Test
    fun forgetResetsRankerWhenModelIsYoungerThanT() {
        val m = PersonalModel(testConfig())
        m.rankingFeatureProvider = provider()
        m.apply(List(50) { pref() }, 5_000)
        assertThat(m.snapshot().rankerScore(feat(userLm = -1f)))
            .isGreaterThan(m.snapshot().rankerScore(feat(userLm = -6f)))
        val r = m.forgetSince(1_000, 6_000)
        assertThat(r.rankerRestoredFromMillis).isNull()
        assertThat(m.snapshot().rankerScore(feat(userLm = -1f))).isEqualTo(0f)
    }

    @Test
    fun forgetThroughPersistenceKeepsStoreConsistent() {
        val store = InMemoryLearningStore()
        val m = PersonalModel(testConfig())
        val p = LearningPersistence(m, store)
        m.applyAt(0, used("old", w = 3f))
        m.applyAt(HOUR_MS, used("recent", w = 3f))
        runSuspend { p.flush(HOUR_MS) }
        assertThat(store.journalSize()).isEqualTo(2)
        val result = runSuspend { p.forgetSince(HOUR_MS / 2, HOUR_MS + 1) }
        assertThat(result.subtractedUpdates).isEqualTo(1)
        assertThat(store.journalSize()).isEqualTo(1)
        assertThat(store.state!!.ngrams.map { it.key }).containsExactly("old")
        // Reload from the store gives the same state.
        val m2 = PersonalModel(testConfig())
        runSuspend { LearningPersistence(m2, store).restore(HOUR_MS + 2) }
        assertThat(m2.export()).isEqualTo(m.export())
        runSuspend { p.clearAll() }
        assertThat(store.state).isNull()
        assertThat(m.export().ngrams).isEmpty()
    }
}
