package dev.keycorrect.core.learning.model

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.learning.LearningUpdate
import dev.keycorrect.core.learning.LearningUpdate.AddUserWord
import dev.keycorrect.core.learning.LearningUpdate.CasingEvidence
import dev.keycorrect.core.learning.LearningUpdate.CorrectionEvidence
import dev.keycorrect.core.learning.LearningUpdate.NewWordEvidence
import org.junit.Test

class CorrectionAndLexiconTest {
    private val bucket = "the|Text"

    @Test
    fun oneTieChoiceFlipsTheNextTie() {
        val m = PersonalModel(testConfig())
        // tset -> test was applied, the user switched to "set": neg test, pos set.
        m.applyAt(
            0,
            CorrectionEvidence("tset", "test", bucket, -1f),
            CorrectionEvidence("tset", "set", bucket, 1f),
        )
        val s = m.snapshot()
        assertThat(s.correctionBias("tset", "set", bucket)).isGreaterThan(1f)
        assertThat(s.correctionBias("tset", "test", bucket)).isLessThan(-1f)
    }

    @Test
    fun biasIsBoundedByMaxBias() {
        val m = PersonalModel(testConfig())
        repeat(100) { m.applyAt(it.toLong(), CorrectionEvidence("teh", "the", bucket, 5f)) }
        val b = m.snapshot().correctionBias("teh", "the", bucket)
        assertThat(b).isAtMost(4f)
        assertThat(b).isGreaterThan(3.5f)
    }

    @Test
    fun globalBackOffAppliesToOtherBucketsButWeaker() {
        val m = PersonalModel(testConfig())
        m.applyAt(0, CorrectionEvidence("tset", "set", bucket, 1f))
        val s = m.snapshot()
        val inBucket = s.correctionBias("tset", "set", bucket)
        val otherBucket = s.correctionBias("tset", "set", "a|Text")
        assertThat(otherBucket).isGreaterThan(0f)
        assertThat(otherBucket).isLessThan(inBucket)
        assertThat(s.correctionBias("tset", "set", "")).isGreaterThan(0f)
        assertThat(s.correctionBias("tset", "other", bucket)).isEqualTo(0f)
    }

    @Test
    fun contextSpecificNegativeDoesNotKillOtherBucketsEntirely() {
        val m = PersonalModel(testConfig())
        m.applyAt(0, CorrectionEvidence("their", "there", "over|Text", 2f))
        m.applyAt(1, CorrectionEvidence("their", "there", "put|Text", -1f))
        val s = m.snapshot()
        assertThat(s.correctionBias("their", "there", "over|Text")).isGreaterThan(0f)
        assertThat(s.correctionBias("their", "there", "put|Text"))
            .isLessThan(s.correctionBias("their", "there", "over|Text"))
    }

    @Test
    fun correctionMemoryDecays() {
        val cfg = testConfig(PersonalModelConfig(halfLifeMillis = DAY_MS))
        val m = PersonalModel(cfg)
        m.applyAt(0, CorrectionEvidence("tset", "set", bucket, 1f))
        val before = m.snapshot().correctionBias("tset", "set", bucket)
        m.applyAt(DAY_MS * 3 / 2, used("x"))
        m.applyAt(DAY_MS * 2, CorrectionEvidence("zzz", "yyy", bucket, 1f))
        val after = m.snapshot().correctionBias("tset", "set", bucket)
        assertThat(after).isLessThan(before / 2)
    }

    @Test
    fun suppressionIsAHardStopUntilRemoved() {
        val m = PersonalModel(testConfig())
        m.applyAt(0, LearningUpdate.SuppressCorrection("Teh", "The"))
        assertThat(m.snapshot().isSuppressed("teh", "the")).isTrue()
        // Lots of positive evidence for the pair does not undo a suppression.
        repeat(20) { m.applyAt(10L + it, CorrectionEvidence("teh", "the", bucket, 2f)) }
        assertThat(m.snapshot().isSuppressed("TEH", "THE")).isTrue()
        assertThat(m.snapshot().isSuppressed("teh", "ten")).isFalse()
        assertThat(m.suppressedCorrections()).containsExactly("teh" to "the")
        assertThat(m.removeSuppression("teh", "the", 100)).isTrue()
        assertThat(m.snapshot().isSuppressed("teh", "the")).isFalse()
    }

    @Test
    fun blocklistAndUnblock() {
        val m = PersonalModel(testConfig())
        m.applyAt(0, LearningUpdate.BlockWord("Darn’it"))
        assertThat(m.snapshot().isBlocked("darn'it")).isTrue()
        assertThat(m.blockedWords()).containsExactly("darn'it")
        m.unblock("darn'it", 5)
        assertThat(m.snapshot().isBlocked("darn'it")).isFalse()
    }

    @Test
    fun promotionNeedsThreeCommitsInThreeSessions() {
        val m = PersonalModel(testConfig())
        m.applyAt(0, NewWordEvidence("Zorblax", 1, 1f), NewWordEvidence("zorblax", 1, 1f))
        m.applyAt(1, NewWordEvidence("zorblax", 2, 1f)) // 3 commits, two sessions
        assertThat(m.pendingUserDictionaryAdds()).isEmpty()
        assertThat(m.snapshot().isUserWord("zorblax")).isFalse()
        m.applyAt(2, NewWordEvidence("zorblax", 3, 1f))
        val adds = m.pendingUserDictionaryAdds()
        assertThat(adds).hasSize(1)
        assertThat(adds[0].reason).isEqualTo(AddUserWord.Reason.Promoted)
        assertThat(adds[0].word).isEqualTo("Zorblax")
        assertThat(m.snapshot().isUserWord("ZORBLAX")).isTrue()
        assertThat(m.pendingUserDictionaryAdds()).isEmpty() // drained
        m.applyAt(3, NewWordEvidence("zorblax", 4, 1f))
        assertThat(m.pendingUserDictionaryAdds()).isEmpty() // not re-emitted
    }

    @Test
    fun explicitKeepsPromoteOnTheFastPath() {
        val m = PersonalModel(testConfig())
        m.applyAt(0, NewWordEvidence("quibblex", 1, 2f))
        assertThat(m.pendingUserDictionaryAdds()).isEmpty()
        m.applyAt(1, NewWordEvidence("quibblex", 2, 2f)) // two literal taps, two sessions
        assertThat(m.pendingUserDictionaryAdds()).hasSize(1)
    }

    @Test
    fun lowWeightVoiceSessionsDoNotCountAsDistinct() {
        val m = PersonalModel(testConfig())
        m.applyAt(0, NewWordEvidence("quux", 1, 2f), NewWordEvidence("quux", 2, 0.3f))
        assertThat(m.pendingUserDictionaryAdds()).isEmpty()
    }

    @Test
    fun blockedOrRemovedWordsAreNeverPromoted() {
        val m = PersonalModel(testConfig())
        m.applyAt(0, LearningUpdate.BlockWord("blocked"))
        m.applyAt(1, NewWordEvidence("blocked", 1, 1f), NewWordEvidence("blocked", 2, 1f))
        assertThat(m.pendingUserDictionaryAdds()).isEmpty()
        assertThat(m.snapshot().isUserWord("blocked")).isFalse()

        m.applyAt(2, AddUserWord("blocked", AddUserWord.Reason.Explicit))
        assertThat(m.pendingUserDictionaryAdds()).isEmpty() // block wins until undone

        m.applyAt(3, AddUserWord("kept", AddUserWord.Reason.Explicit))
        assertThat(m.pendingUserDictionaryAdds()).hasSize(1)
        assertThat(m.removeUserWord("kept", 4)).isTrue()
        m.applyAt(5, NewWordEvidence("kept", 1, 1f), NewWordEvidence("kept", 2, 1f))
        assertThat(m.pendingUserDictionaryAdds()).isEmpty() // tombstone
        m.applyAt(6, AddUserWord("kept", AddUserWord.Reason.Explicit))
        assertThat(m.snapshot().isUserWord("kept")).isTrue()
    }

    @Test
    fun revertedAutocorrectAddIsRecordedOnce() {
        val m = PersonalModel(testConfig())
        m.applyAt(0, AddUserWord("tset", AddUserWord.Reason.RevertedAutocorrect))
        m.applyAt(1, AddUserWord("tset", AddUserWord.Reason.RevertedAutocorrect))
        assertThat(m.pendingUserDictionaryAdds()).hasSize(1)
        assertThat(m.userWords().map { it.word }).containsExactly("tset")
    }

    @Test
    fun casingPreferenceNeedsTwoObservations() {
        val m = PersonalModel(testConfig())
        m.applyAt(0, CasingEvidence("iPhone"))
        assertThat(m.snapshot().preferredCasing("iphone")).isNull()
        m.applyAt(1, CasingEvidence("iPhone"))
        assertThat(m.snapshot().preferredCasing("iPhone")).isEqualTo("iPhone")
        assertThat(m.snapshot().preferredCasing("IPHONE")).isEqualTo("iPhone")
        // Competing lowercase usage at least as frequent cancels the preference.
        m.applyAt(2, CasingEvidence("iphone"), CasingEvidence("iphone"))
        assertThat(m.snapshot().preferredCasing("iphone")).isNull()
    }

    // ---- management: topCorrections / forgetCorrection / forgetUserWord ----

    @Test
    fun topCorrectionsListsTheStrongestPositivePairsFirst() {
        val m = PersonalModel(testConfig())
        repeat(3) { m.applyAt(it.toLong(), CorrectionEvidence("teh", "the", "a|Text", 1f)) }
        m.applyAt(5, CorrectionEvidence("adn", "and", "b|Text", 1f))
        m.applyAt(6, CorrectionEvidence("tset", "test", bucket, -1f)) // rejected: not listed
        val top = m.topCorrections(10)
        assertThat(top.map { it.typed to it.target })
            .containsExactly("teh" to "the", "adn" to "and")
            .inOrder()
        assertThat(top[0].score).isGreaterThan(top[1].score)
        assertThat(m.topCorrections(1).map { it.typed }).containsExactly("teh")
        assertThat(m.topCorrections(0)).isEmpty()
    }

    @Test
    fun topCorrectionsKeysAreNormalised() {
        val m = PersonalModel(testConfig())
        m.applyAt(0, CorrectionEvidence("Dont", "Don’t", bucket, 1f))
        assertThat(m.topCorrections(5).single().target).isEqualTo("don't")
    }

    @Test
    fun correctionIsContextualOnlyWhenOneContextHoldsMostOfIt() {
        val m = PersonalModel(testConfig())
        repeat(3) { m.applyAt(it.toLong(), CorrectionEvidence("teh", "the", "in|Text", 1f)) }
        repeat(3) { m.applyAt(10L + it, CorrectionEvidence("adn", "and", "w$it|Text", 1f)) }
        val byTyped = m.topCorrections(5).associateBy { it.typed }
        assertThat(byTyped.getValue("teh").contextual).isTrue()
        assertThat(byTyped.getValue("adn").contextual).isFalse()
    }

    @Test
    fun forgetCorrectionDropsMemoryInEveryContextButKeepsSuppressions() {
        val m = PersonalModel(testConfig())
        m.applyAt(0, CorrectionEvidence("teh", "the", "a|Text", 1f))
        m.applyAt(1, CorrectionEvidence("teh", "the", "b|Text", 1f))
        m.applyAt(2, CorrectionEvidence("adn", "and", bucket, 1f))
        m.applyAt(3, LearningUpdate.SuppressCorrection("teh", "the"))
        assertThat(m.forgetCorrection("TEH", "the", 4)).isTrue()
        assertThat(m.topCorrections(5).map { it.typed }).containsExactly("adn")
        val s = m.snapshot()
        assertThat(s.correctionBias("teh", "the", "a|Text")).isEqualTo(0f)
        assertThat(s.correctionBias("teh", "the", "")).isEqualTo(0f)
        assertThat(s.isSuppressed("teh", "the")).isTrue()
        assertThat(m.forgetCorrection("teh", "the", 5)).isFalse()
    }

    @Test
    fun forgetCorrectionAlsoDropsItsJournalSoForgetSinceDoesNotSubtractAgain() {
        val m = PersonalModel(testConfig())
        m.applyAt(10, CorrectionEvidence("teh", "the", bucket, 1f))
        m.forgetCorrection("teh", "the", 11)
        assertThat(m.journalSnapshot()).isEmpty()
        assertThat(m.drainJournalForPersistence()).isEmpty()
        m.forgetSince(0, 12)
        assertThat(m.export().corrections).isEmpty() // no negative leftovers
    }

    @Test
    fun forgetCorrectionIsRewrittenToTheStore() {
        val store = InMemoryLearningStore()
        val a = PersonalModel(testConfig())
        a.applyAt(10, CorrectionEvidence("teh", "the", bucket, 1f))
        a.applyAt(11, CorrectionEvidence("adn", "and", bucket, 1f))
        runSuspend {
            val p = LearningPersistence(a, store)
            p.flush(12)
            assertThat(p.forgetCorrection("teh", "the", 13)).isTrue()
        }
        val b = PersonalModel(testConfig())
        runSuspend { LearningPersistence(b, store).restore(14) }
        assertThat(b.topCorrections(5).map { it.typed }).containsExactly("adn")
        assertThat(b.journalSnapshot()).hasSize(1)
    }

    @Test
    fun forgetUserWordTombstonesALearnedWord() {
        val m = PersonalModel(testConfig())
        m.applyAt(0, AddUserWord("kept", AddUserWord.Reason.Explicit))
        assertThat(m.snapshot().isUserWord("kept")).isTrue()
        assertThat(m.forgetUserWord("Kept", 1)).isTrue()
        assertThat(m.snapshot().isUserWord("kept")).isFalse()
        assertThat(m.userWords()).isEmpty()
        assertThat(m.pendingUserDictionaryAdds()).isEmpty() // the undrained add is gone too
        m.applyAt(2, NewWordEvidence("kept", 1, 1f), NewWordEvidence("kept", 2, 1f))
        assertThat(m.snapshot().isUserWord("kept")).isFalse() // not re-promoted
        assertThat(m.forgetUserWord("kept", 3)).isFalse() // already forgotten
        m.applyAt(4, AddUserWord("kept", AddUserWord.Reason.Explicit))
        assertThat(m.snapshot().isUserWord("kept")).isTrue() // explicit add resurrects
    }

    @Test
    fun forgetUserWordDropsCandidateEvidenceOfAWordTheModelNeverPromoted() {
        val m = PersonalModel(testConfig())
        m.applyAt(0, NewWordEvidence("zorblax", 1, 1f), NewWordEvidence("zorblax", 2, 1f))
        assertThat(m.forgetUserWord("zorblax", 1)).isTrue()
        m.applyAt(2, NewWordEvidence("zorblax", 3, 1f), NewWordEvidence("zorblax", 4, 1f))
        assertThat(m.pendingUserDictionaryAdds()).isEmpty()
        assertThat(m.snapshot().isUserWord("zorblax")).isFalse()
        // The tombstone survives a save and restore.
        val b = PersonalModel(testConfig())
        b.import(m.export())
        b.applyAt(5, NewWordEvidence("zorblax", 5, 1f), NewWordEvidence("zorblax", 6, 1f))
        assertThat(b.snapshot().isUserWord("zorblax")).isFalse()
    }
}
