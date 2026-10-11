package dev.keycorrect.core.learning.model

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.learning.LearningUpdate
import dev.keycorrect.core.learning.LearningUpdate.AutocorrectOutcome
import dev.keycorrect.core.learning.LearningUpdate.PhraseFeedback
import dev.keycorrect.core.learning.LearningUpdate.Preference
import dev.keycorrect.core.learning.LearningUpdate.TouchEvidence
import dev.keycorrect.core.learning.RankingFeatures
import dev.keycorrect.core.learning.TouchSample
import org.junit.Test

class TouchAndRankerTest {
    private val key = "Phone/port/qwerty@54dp"

    private fun touches(n: Int, ch: Char, dx: Float, dy: Float) =
        TouchEvidence(key, List(n) { TouchSample(ch, dx, dy) })

    @Test
    fun touchOffsetNeedsThirtySamples() {
        val m = PersonalModel(testConfig())
        m.applyAt(0, touches(29, 'a', 0.1f, -0.05f))
        assertThat(m.snapshot().touchOffset(key, 'a')).isNull()
        m.applyAt(1, touches(1, 'a', 0.1f, -0.05f))
        val o = m.snapshot().touchOffset(key, 'A')!!
        assertThat(o.first).isWithin(1e-3f).of(0.1f)
        assertThat(o.second).isWithin(1e-3f).of(-0.05f)
        assertThat(m.snapshot().touchOffset(key, 'b')).isNull()
        assertThat(m.snapshot().touchOffset("Tablet/land/x", 'a')).isNull()
    }

    @Test
    fun touchOffsetIsClampedToHalfAKey() {
        val m = PersonalModel(testConfig())
        m.applyAt(0, touches(40, 's', 0.65f, -0.65f))
        val o = m.snapshot().touchOffset(key, 's')!!
        assertThat(o.first).isEqualTo(0.5f)
        assertThat(o.second).isEqualTo(-0.5f)
    }

    @Test
    fun touchStatisticsReportMeanSpreadAndCounts() {
        val m = PersonalModel(testConfig())
        val samples =
            List(50) {
                TouchSample('e', if (it % 2 == 0) 0.3f else 0.1f, if (it % 2 == 0) 0.2f else -0.2f)
            }
        m.applyAt(0, TouchEvidence(key, samples), TouchEvidence(key, samples.take(10)))
        val p = m.touchStatistics().profile(key)!!
        assertThat(p.wordCount).isEqualTo(2)
        assertThat(p.sampleCount).isEqualTo(60)
        assertThat(p.hasEnoughData).isFalse()
        val e = p.keys.single()
        assertThat(e.char).isEqualTo('e')
        assertThat(e.meanDx).isWithin(0.01f).of(0.2f)
        assertThat(e.sigmaX).isWithin(0.02f).of(0.1f)
        assertThat(e.sigmaY).isWithin(0.02f).of(0.2f)
        assertThat(p.sigmaX).isWithin(0.02f).of(0.1f)
        assertThat(p.rmsOffsetX).isGreaterThan(p.sigmaX) // bias + spread
        assertThat(m.touchStatistics().profile("nope")).isNull()
    }

    @Test
    fun touchStatisticsAreFlaggedReadyAfterEnoughWords() {
        val m = PersonalModel(testConfig(PersonalModelConfig(touchMinProfileWords = 3)))
        repeat(3) { m.applyAt(it.toLong(), touches(2, 'a', 0f, 0f)) }
        assertThat(m.touchStatistics().profile(key)!!.hasEnoughData).isTrue()
    }

    // ---- ranker ----

    private val good = feat(userLm = -1f, lm = -6f)
    private val bad = feat(userLm = -6f, lm = -6f)

    private fun provider(): RankingFeatureProvider = { _, _, c ->
        when (c) {
            "good" -> good
            "bad" -> bad
            else -> null
        }
    }

    private fun pref(w: Float = 1f) = Preference("gd", listOf("a"), "good", "bad", w)

    @Test
    fun rankerLearnsASyntheticPreference() {
        val m = PersonalModel(testConfig())
        m.rankingFeatureProvider = provider()
        assertThat(m.snapshot().rankerScore(good)).isEqualTo(0f)
        repeat(200) { m.applyAt(it.toLong(), pref()) }
        val s = m.snapshot()
        assertThat(s.rankerScore(good)).isGreaterThan(s.rankerScore(bad) + 0.3f)
        // Generalises to unseen feature values with the same pattern.
        assertThat(s.rankerScore(feat(userLm = -2f)))
            .isGreaterThan(s.rankerScore(feat(userLm = -5f)))
    }

    @Test
    fun rankerScoreIsBoundedAndStaysFiniteUnderExtremeFeatures() {
        val m = PersonalModel(testConfig())
        m.rankingFeatureProvider = provider()
        repeat(2000) { m.applyAt(it.toLong(), pref(2f)) }
        val extreme = RankingFeatures(-1e6f, 1e6f, 1e6f, 1000, 1e6f, 1e6f, true, true, 99)
        val s = m.snapshot().rankerScore(extreme)
        assertThat(s).isAtMost(3f)
        assertThat(s).isAtLeast(-3f)
    }

    @Test
    fun withoutProviderPreferencesAreIgnored() {
        val m = PersonalModel(testConfig())
        repeat(50) { m.applyAt(it.toLong(), pref()) }
        m.rankingFeatureProvider = provider()
        m.applyAt(100, used("x"))
        assertThat(m.snapshot().rankerScore(good)).isEqualTo(0f) // nothing was buffered
    }

    @Test
    fun preferencesAreBufferedUntilFeaturesAreAvailable() {
        val m =
            PersonalModel(
                testConfig(
                    PersonalModelConfig(
                        minTotalEvidence = 5.0,
                        rankerMaxAttempts = 1000,
                        rankerRetryGapMillis = 0,
                    )
                )
            )
        var ready = false
        val p = provider()
        m.rankingFeatureProvider = { t, c, cand -> if (ready) p(t, c, cand) else null }
        repeat(30) { m.applyAt(it.toLong(), pref()) }
        assertThat(m.snapshot().rankerScore(good)).isEqualTo(0f)
        ready = true
        m.apply(emptyList(), 1000)
        assertThat(m.snapshot().rankerScore(good)).isGreaterThan(m.snapshot().rankerScore(bad))
    }

    @Test
    fun bufferedPreferencesExpire() {
        val m =
            PersonalModel(
                testConfig(
                    PersonalModelConfig(
                        minTotalEvidence = 5.0,
                        rankerMaxAttempts = 1000,
                        rankerRetryGapMillis = 0,
                    )
                )
            )
        var ready = false
        val p = provider()
        m.rankingFeatureProvider = { t, c, cand -> if (ready) p(t, c, cand) else null }
        repeat(10) { m.applyAt(it.toLong(), pref()) }
        ready = true
        m.apply(emptyList(), 60 * 60_000L)
        assertThat(m.snapshot().rankerScore(good)).isEqualTo(0f)
    }

    @Test
    fun aPreferenceIsGivenUpOnAfterMaxFailedAttempts() {
        val m = PersonalModel(testConfig(PersonalModelConfig(rankerRetryGapMillis = 0)))
        var calls = 0
        m.rankingFeatureProvider = { _, _, _ ->
            calls++
            null // always busy
        }
        m.applyAt(0, pref()) // attempt 1 (one call: the preferred candidate is already busy)
        repeat(10) { m.apply(emptyList(), 1L + it) }
        assertThat(calls).isEqualTo(3) // rankerMaxAttempts
    }

    @Test
    fun retriesAreSpacedByTheRetryGap() {
        val m =
            PersonalModel(
                testConfig(PersonalModelConfig(rankerMaxAttempts = 5, rankerRetryGapMillis = 1_000))
            )
        var calls = 0
        m.rankingFeatureProvider = { _, _, _ ->
            calls++
            null
        }
        m.applyAt(0, pref())
        repeat(10) { m.apply(emptyList(), 10L * it) } // all within the gap of the first attempt
        assertThat(calls).isEqualTo(1)
        m.apply(emptyList(), 1_000)
        assertThat(calls).isEqualTo(2)
        m.apply(emptyList(), 1_500)
        assertThat(calls).isEqualTo(2)
        m.apply(emptyList(), 2_000)
        assertThat(calls).isEqualTo(3)
    }

    @Test
    fun aCandidateTheDecoderCannotProduceDropsThePreferenceAtOnce() {
        val m = PersonalModel(testConfig(PersonalModelConfig(rankerRetryGapMillis = 0)))
        var calls = 0
        m.rankingFeatureProvider = { _, _, c ->
            calls++
            if (c == "good") good else RankingFeatures.NOT_DECODABLE
        }
        m.applyAt(0, pref())
        repeat(5) { m.apply(emptyList(), 1L + it) }
        assertThat(calls).isEqualTo(2) // one decode per candidate, never retried
        assertThat(m.snapshot().rankerScore(good)).isEqualTo(0f)
    }

    @Test
    fun aNotDecodablePreferredCandidateSkipsTheSecondDecode() {
        val m = PersonalModel(testConfig())
        var calls = 0
        m.rankingFeatureProvider = { _, _, _ ->
            calls++
            RankingFeatures.NOT_DECODABLE
        }
        m.applyAt(0, pref())
        m.apply(emptyList(), 1)
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun aBusyPreferenceStillTrainsOnceTheProviderRecovers() {
        val m = PersonalModel(testConfig(PersonalModelConfig(rankerRetryGapMillis = 0)))
        var busy = true
        val p = provider()
        m.rankingFeatureProvider = { t, c, cand -> if (busy) null else p(t, c, cand) }
        m.applyAt(0, pref())
        busy = false
        m.apply(emptyList(), 1) // second attempt, within the limit
        assertThat(m.snapshot().rankerScore(good)).isGreaterThan(m.snapshot().rankerScore(bad))
    }

    @Test
    fun zeroWeightPreferenceDoesNotMoveWeights() {
        val m = PersonalModel(testConfig())
        m.rankingFeatureProvider = provider()
        repeat(20) { m.applyAt(it.toLong(), pref(0f)) }
        assertThat(m.snapshot().rankerScore(good)).isEqualTo(0f)
    }

    // ---- adaptive thresholds ----

    @Test
    fun thresholdIsNeutralWithoutData() {
        val m = PersonalModel(testConfig())
        assertThat(m.snapshot().autocorrectThresholdDelta).isEqualTo(0f)
        assertThat(m.snapshot().phraseGateDelta).isEqualTo(0f)
    }

    @Test
    fun manyRevertsRaiseTheThresholdUpToTwo() {
        val m = PersonalModel(testConfig())
        m.applyAt(0, AutocorrectOutcome(true))
        val few = m.snapshot().autocorrectThresholdDelta
        assertThat(few).isGreaterThan(0f)
        m.applyAt(1, *Array(300) { AutocorrectOutcome(true) })
        assertThat(m.snapshot().autocorrectThresholdDelta).isEqualTo(2f)
        assertThat(few).isLessThan(2f)
    }

    @Test
    fun fewRevertsLowerTheThresholdDownToHalf() {
        val m = PersonalModel(testConfig())
        m.applyAt(0, *Array(5) { AutocorrectOutcome(false) })
        val some = m.snapshot().autocorrectThresholdDelta
        assertThat(some).isLessThan(0f)
        m.applyAt(1, *Array(300) { AutocorrectOutcome(false) })
        val d = m.snapshot().autocorrectThresholdDelta
        assertThat(d).isAtLeast(-0.5f)
        assertThat(d).isLessThan(-0.45f)
    }

    @Test
    fun thresholdReactsToRecentOutcomesMoreThanOldOnes() {
        val m = PersonalModel(testConfig())
        m.applyAt(0, *Array(150) { AutocorrectOutcome(true) })
        val high = m.snapshot().autocorrectThresholdDelta
        m.applyAt(1, *Array(200) { AutocorrectOutcome(false) })
        assertThat(m.snapshot().autocorrectThresholdDelta).isLessThan(high)
        assertThat(m.snapshot().autocorrectThresholdDelta).isLessThan(0f)
    }

    @Test
    fun targetRevertRateGivesNearZeroDelta() {
        val m = PersonalModel(testConfig())
        // ~3% reverts spread evenly.
        val ups = List(300) { AutocorrectOutcome(it % 33 == 0) }
        m.applyAt(0, *ups.toTypedArray())
        assertThat(m.snapshot().autocorrectThresholdDelta).isWithin(0.6f).of(0f)
    }

    @Test
    fun phraseGateRisesWhenPhrasesAreRejectedAndFallsWhenAccepted() {
        val rejected = PersonalModel(testConfig())
        rejected.applyAt(0, *Array(100) { PhraseFeedback(4, 0) })
        assertThat(rejected.snapshot().phraseGateDelta).isEqualTo(1f)
        val accepted = PersonalModel(testConfig())
        accepted.applyAt(0, *Array(100) { PhraseFeedback(4, 4) })
        assertThat(accepted.snapshot().phraseGateDelta).isEqualTo(-0.5f)
        val none = PersonalModel(testConfig())
        none.applyAt(0, LearningUpdate.PhraseFeedback(0, 0))
        assertThat(none.snapshot().phraseGateDelta).isEqualTo(0f)
    }
}
