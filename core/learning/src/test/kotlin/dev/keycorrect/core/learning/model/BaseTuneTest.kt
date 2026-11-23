package dev.keycorrect.core.learning.model

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.learning.LearningUpdate.BaseTuneEvidence
import kotlin.math.tanh
import org.junit.Test

/** The built-in tune overlay and the lifetime counters (N-26, N-27). */
class BaseTuneTest {
    private fun model(cfg: PersonalModelConfig = testConfig()) = PersonalModel(cfg)

    private fun PersonalModel.tune(prev: String, word: String): Float {
        publishNow()
        return snapshot().baseTune(prev, word)
    }

    @Test
    fun oneConflictIsASlightNudgeWithTheOtherWordLowered() {
        val m = model()
        m.applyAt(1_000, BaseTuneEvidence("the", "help", "hell", 1f))
        val expected = (0.7 * tanh(1.0 / 3.0)).toFloat()
        assertThat(m.tune("the", "help")).isWithin(1e-4f).of(expected)
        assertThat(m.tune("the", "hell")).isWithin(1e-4f).of(-expected)
        assertThat(m.tune("a", "help")).isEqualTo(0f)
    }

    @Test
    fun regularConflictsSaturateBelowTheCap() {
        val m = model()
        repeat(20) { m.applyAt(1_000L + it, BaseTuneEvidence("the", "help", null, 2f)) }
        val v = m.tune("the", "help")
        assertThat(v).isAtMost(0.7f)
        assertThat(v).isGreaterThan(0.6f)
    }

    @Test
    fun theTuneDecaysWithTheActiveClock() {
        val m = model()
        m.applyAt(1_000, BaseTuneEvidence("the", "help", null, 1f))
        val before = m.tune("the", "help")
        m.applyAt(1_000 + 400 * DAY_MS, used("x"))
        val after = m.tune("the", "help")
        assertThat(after).isLessThan(before)
    }

    @Test
    fun forgetSinceRemovesTheJournaledTune() {
        val m = model()
        m.applyAt(1_000, BaseTuneEvidence("the", "help", "hell", 1f))
        assertThat(m.tune("the", "help")).isGreaterThan(0.1f)
        m.forgetSince(0, 1_000)
        assertThat(m.tune("the", "help")).isWithin(1e-3f).of(0f)
        assertThat(m.tune("the", "hell")).isWithin(1e-3f).of(0f)
    }

    @Test
    fun theTuneSurvivesExportAndImport() {
        val m = model()
        m.applyAt(1_000, BaseTuneEvidence("the", "help", "hell", 1f))
        val state = m.export()
        assertThat(state.tune).hasSize(2)
        val copy = model()
        copy.import(state)
        assertThat(copy.tune("the", "help")).isEqualTo(m.tune("the", "help"))
        assertThat(copy.export().tune).containsExactlyElementsIn(state.tune)
    }

    @Test
    fun anOlderStateImportsWithAnEmptyTune() {
        val m = model()
        m.import(PersistedLearning(formatVersion = 2))
        assertThat(m.export().tune).isEmpty()
        assertThat(m.lifetimeCounts()).isEqualTo(LearningCounts(0, 0))
        assertThat(PersistedLearning.FORMAT_VERSION).isEqualTo(3)
    }

    @Test
    fun aNewerFormatIsRefused() {
        val m = model()
        assertThat(runCatching { m.import(PersistedLearning(formatVersion = 99)) }.isFailure)
            .isTrue()
    }

    @Test
    fun theJournalCodecRoundTripsTheTune() {
        for (u in
            listOf(
                BaseTuneEvidence("the", "help", "hell", 0.5f),
                BaseTuneEvidence("we%ird", "x y", null, 1f),
            )) {
            assertThat(JournalCodec.decode(JournalCodec.encode(u))).isEqualTo(u)
        }
    }

    @Test
    fun countersCountAndSurviveExportButNotReset() {
        val m = model()
        m.applyAt(1_000, used("hello"), used("world"), used("bad", w = -1f))
        m.applyAt(1_001, BaseTuneEvidence("a", "b", "c", 1f))
        assertThat(m.lifetimeCounts()).isEqualTo(LearningCounts(2, 1))
        val copy = model()
        copy.import(m.export())
        assertThat(copy.lifetimeCounts()).isEqualTo(LearningCounts(2, 1))
        copy.clear()
        assertThat(copy.lifetimeCounts()).isEqualTo(LearningCounts(0, 0))
        assertThat(copy.tune("a", "b")).isEqualTo(0f)
    }

    @Test
    fun theTableIsCapped() {
        val m = model(testConfig(PersonalModelConfig(minTotalEvidence = 5.0, maxTuneEntries = 10)))
        repeat(40) { m.applyAt(1_000L + it, BaseTuneEvidence("p$it", "w$it", null, 1f)) }
        assertThat(m.export().tune.size).isAtMost(10)
    }
}
