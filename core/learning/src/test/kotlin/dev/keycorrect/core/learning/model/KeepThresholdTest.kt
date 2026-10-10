package dev.keycorrect.core.learning.model

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.learning.LearningUpdate.AddUserWord
import dev.keycorrect.core.learning.LearningUpdate.RevertKept
import org.junit.Test

/** Docs/PLAN.md §6.3 rows 6/7: N keeps add the literal, M >= N keeps suppress the pair. */
class KeepThresholdTest {
    private fun model(n: Int = 2, m: Int = 2) =
        PersonalModel(testConfig()).also { it.updateThresholds(n, m) }

    private fun PersonalModel.keep(t: Long, typed: String = "tset", to: String = "test") =
        applyAt(t, RevertKept(typed, to))

    private fun PersonalModel.added(word: String = "tset") = snapshot().isUserWord(word)

    private fun PersonalModel.suppressed(typed: String = "tset", to: String = "test") =
        snapshot().isSuppressed(typed, to)

    @Test
    fun defaultsAreTwoAndTwo() {
        val m = PersonalModel(testConfig())
        assertThat(m.thresholds()).isEqualTo(2 to 2)
        m.keep(1)
        assertThat(m.added()).isFalse()
        assertThat(m.suppressed()).isFalse()
        m.keep(2)
        assertThat(m.added()).isTrue()
        assertThat(m.suppressed()).isTrue()
    }

    @Test
    fun oneKeepIsEnoughWhenThresholdIsOne() {
        val m = model(1, 1)
        m.keep(1)
        assertThat(m.added()).isTrue()
        assertThat(m.suppressed()).isTrue()
        val adds = m.pendingUserDictionaryAdds()
        assertThat(adds).hasSize(1)
        assertThat(adds[0].reason).isEqualTo(AddUserWord.Reason.RevertedAutocorrect)
        assertThat(adds[0].word).isEqualTo("tset")
    }

    @Test
    fun threeKeepsNeededAtThree() {
        val m = model(3, 3)
        m.keep(1)
        m.keep(2)
        assertThat(m.added()).isFalse()
        assertThat(m.suppressed()).isFalse()
        assertThat(m.pendingUserDictionaryAdds()).isEmpty()
        m.keep(3)
        assertThat(m.added()).isTrue()
        assertThat(m.suppressed()).isTrue()
        assertThat(m.pendingUserDictionaryAdds().map { it.word }).containsExactly("tset")
    }

    @Test
    fun suppressionMayComeLaterThanTheAdd() {
        val m = model(1, 3)
        m.keep(1)
        assertThat(m.added()).isTrue()
        assertThat(m.suppressed()).isFalse()
        m.keep(2)
        assertThat(m.suppressed()).isFalse()
        m.keep(3)
        assertThat(m.suppressed()).isTrue()
        assertThat(m.pendingUserDictionaryAdds()).hasSize(1) // added once only
    }

    @Test
    fun suppressThresholdIsRaisedToTheAddThreshold() {
        val m = model(4, 2)
        assertThat(m.thresholds()).isEqualTo(4 to 4)
        m.updateThresholds(9, 1)
        assertThat(m.thresholds()).isEqualTo(5 to 5) // N is clamped to 1..5
        m.updateThresholds(0, 0)
        assertThat(m.thresholds()).isEqualTo(1 to 1)
    }

    @Test
    fun wordKeepsAddAcrossDifferentCorrections_pairKeepsDoNot() {
        val m = model(2, 2)
        m.keep(1, "teh", "the")
        m.keep(2, "teh", "ten")
        assertThat(m.added("teh")).isTrue() // two keeps of the word
        assertThat(m.suppressed("teh", "the")).isFalse() // one keep per pair
        assertThat(m.suppressed("teh", "ten")).isFalse()
        m.keep(3, "teh", "the")
        assertThat(m.suppressed("teh", "the")).isTrue()
        assertThat(m.suppressed("teh", "ten")).isFalse()
    }

    @Test
    fun keysAreCaseAndApostropheInsensitive() {
        val m = model(2, 2)
        m.keep(1, "Don’t", "Dont")
        m.keep(2, "don't", "dont")
        assertThat(m.suppressed("DON'T", "DONT")).isTrue()
        assertThat(m.added("don't")).isTrue()
    }

    @Test
    fun loweringTheThresholdIsRetroactiveOnTheNextApply() {
        val m = model(3, 3)
        m.keep(1)
        m.keep(2)
        assertThat(m.added()).isFalse()
        m.updateThresholds(2, 2)
        assertThat(m.added()).isFalse() // not until the next apply
        m.apply(emptyList(), 3)
        assertThat(m.added()).isTrue()
        assertThat(m.suppressed()).isTrue()
        assertThat(m.pendingUserDictionaryAdds().map { it.word }).containsExactly("tset")
    }

    @Test
    fun loweringAppliesWithAnUnrelatedUpdateToo() {
        val m = model(3, 3)
        m.keep(1)
        m.keep(2)
        m.updateThresholds(1, 2)
        m.applyAt(3, used("hello"))
        assertThat(m.added()).isTrue()
        assertThat(m.suppressed()).isTrue()
    }

    @Test
    fun raisingTheThresholdDoesNotTakeBackWhatWasDone() {
        val m = model(1, 1)
        m.keep(1)
        m.updateThresholds(5, 5)
        m.apply(emptyList(), 2)
        assertThat(m.added()).isTrue()
        assertThat(m.suppressed()).isTrue()
        // ... and later keeps are judged by the new threshold
        m.keep(3, "zorp", "zip")
        assertThat(m.added("zorp")).isFalse()
    }

    @Test
    fun wordsThatCannotBeUserWordsAreNotAdded_butTheirPairIsStillSuppressed() {
        val m = model(1, 1)
        m.keep(1, "12345", "1234")
        m.keep(2, "a b", "ab")
        assertThat(m.added("12345")).isFalse()
        assertThat(m.suppressed("12345", "1234")).isTrue()
        assertThat(m.pendingUserDictionaryAdds()).isEmpty()
    }

    @Test
    fun removedUserWordIsNotAddedAgainByLaterKeeps() {
        val m = model(1, 1)
        m.keep(1)
        assertThat(m.removeUserWord("tset", 2)).isTrue()
        m.keep(3)
        m.updateThresholds(2, 2)
        m.apply(emptyList(), 4)
        assertThat(m.added()).isFalse()
    }

    @Test
    fun liftingASuppressionDropsItsKeeps_soARestartDoesNotSuppressAgain() {
        val m = model(2, 2)
        m.keep(1)
        m.keep(2)
        assertThat(m.removeSuppression("tset", "test", 3)).isTrue()
        val b = model(2, 2)
        b.import(m.export())
        b.apply(emptyList(), 4)
        assertThat(b.suppressed()).isFalse()
    }

    @Test
    fun keepsPersistAndAreCheckedAgainstTheThresholdsInForceAfterImport() {
        val a = model(3, 3)
        a.keep(1)
        a.keep(2)
        val state = a.export()
        assertThat(state.keeps).isNotEmpty()
        assertThat(state.formatVersion).isEqualTo(PersistedLearning.FORMAT_VERSION)

        val b = model(3, 3)
        b.import(state)
        assertThat(b.export().keeps).isEqualTo(state.keeps)
        assertThat(b.added()).isFalse()
        b.keep(3)
        assertThat(b.added()).isTrue()
        assertThat(b.suppressed()).isTrue()

        val c = model(2, 2) // lower thresholds in force when the state is loaded
        c.import(state)
        c.apply(emptyList(), 5)
        assertThat(c.added()).isTrue()
        assertThat(c.suppressed()).isTrue()
    }

    @Test
    fun keepRowsCarryCountsAndTimestamps() {
        val m = model(5, 5)
        m.keep(100)
        m.keep(250)
        val word = m.export().keeps.single { it.corrected.isEmpty() }
        val pair = m.export().keeps.single { it.corrected == "test" }
        assertThat(word).isEqualTo(KeepRow("tset", "", 2, 100, 250))
        assertThat(pair).isEqualTo(KeepRow("tset", "test", 2, 100, 250))
    }

    @Test
    fun anOlderStateWithoutKeepsStillLoads() {
        val m = model(2, 2)
        m.import(PersistedLearning(formatVersion = 1))
        m.keep(1)
        assertThat(m.added()).isFalse()
        m.keep(2)
        assertThat(m.added()).isTrue()
    }

    @Test
    fun forgetSinceTakesBackRecentKeepsAndWhatTheyCaused() {
        val m = model(2, 2)
        m.keep(10_000) // old keep
        m.keep(HOUR_MS) // recent: reaches the thresholds
        assertThat(m.added()).isTrue()
        assertThat(m.suppressed()).isTrue()
        m.forgetSince(HOUR_MS - 1, HOUR_MS + 10)
        assertThat(m.added()).isFalse()
        assertThat(m.suppressed()).isFalse()
        assertThat(m.pendingUserDictionaryAdds()).isEmpty()
        assertThat(m.export().keeps.single { it.corrected.isEmpty() }.count).isEqualTo(1)
        // the old keep still counts: one more reaches the threshold again
        m.keep(HOUR_MS + 20)
        assertThat(m.added()).isTrue()
    }

    @Test
    fun forgetSinceSubtractsKeepsEvenWhenTheThresholdWasNotReached() {
        val m = model(5, 5)
        m.keep(HOUR_MS)
        m.keep(HOUR_MS + 1)
        m.forgetSince(HOUR_MS, HOUR_MS + 2)
        assertThat(m.export().keeps).isEmpty()
    }

    @Test
    fun keepCountersAreCapped() {
        val m = PersonalModel(testConfig(PersonalModelConfig(maxKeepEntries = 3)))
        for (i in 0 until 10) m.keep(i.toLong(), "wd$i", "ww$i")
        val rows = m.export().keeps
        assertThat(rows.count { it.corrected.isEmpty() }).isEqualTo(3)
        assertThat(rows.count { it.corrected.isNotEmpty() }).isEqualTo(3)
        assertThat(rows.map { it.typed }).contains("wd9")
    }
}
