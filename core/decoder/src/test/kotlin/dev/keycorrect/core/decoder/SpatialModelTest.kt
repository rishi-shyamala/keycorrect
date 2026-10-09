package dev.keycorrect.core.decoder

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.engineapi.TouchPoint
import org.junit.Test

class SpatialModelTest {
    private val model = SpatialModel(TestKeyboard.layout.keys)
    private val w = TestKeyboard.keyWidth

    private fun at(c: Char, dx: Float = 0f, dy: Float = 0f) = TestKeyboard.touch(c, dx, dy)

    @Test
    fun centreOfKeyScoresZero() {
        assertThat(model.logLikelihood('a', at('a'))).isWithin(1e-6f).of(0f)
    }

    @Test
    fun likelihoodFallsWithDistanceAndIsFloored() {
        val near = model.logLikelihood('a', at('a', 0.2f * w))
        val far = model.logLikelihood('a', at('a', 0.6f * w))
        assertThat(near).isLessThan(0f)
        assertThat(far).isLessThan(near)
        assertThat(model.logLikelihood('a', at('p'))).isEqualTo(model.config.floorLogLikelihood)
    }

    @Test
    fun sigmaIsAFractionOfKeySize() {
        // One sigma along x costs exactly 0.5 nats.
        val sigma = model.config.sigmaXFactor * w
        assertThat(model.logLikelihood('a', at('a', sigma))).isWithin(1e-4f).of(-0.5f)
    }

    @Test
    fun caseIsFolded() {
        val t = at('e', 3f)
        assertThat(model.logLikelihood('E', t)).isEqualTo(model.logLikelihood('e', t))
        assertThat(model.hasKey('Q')).isTrue()
    }

    @Test
    fun accentedLetterUsesBaseKeyWithPenalty() {
        val t = at('e', 3f)
        val base = model.logLikelihood('e', t)
        assertThat(model.logLikelihood('é', t))
            .isWithin(1e-5f)
            .of(base + model.config.accentPenalty)
        assertThat(model.hasKey('é')).isTrue()
    }

    @Test
    fun nullTouchAndKeylessCharsCarryNoSpatialInformation() {
        assertThat(model.logLikelihood('a', null)).isEqualTo(0f)
        assertThat(model.logLikelihood('#', at('a'))).isEqualTo(0f)
        assertThat(model.hasKey('#')).isFalse()
    }

    @Test
    fun touchCorrectionShiftsTheMeanAndWidensSigma() {
        val shifted =
            SpatialModel(
                TestKeyboard.layout.keys,
                correction = { c ->
                    if (c == 'a') KeyAdjustment(dx = 0.3f * w) else KeyAdjustment()
                },
            )
        val t = at('a', 0.3f * w)
        assertThat(shifted.logLikelihood('a', t)).isWithin(1e-5f).of(0f)
        assertThat(model.logLikelihood('a', t)).isLessThan(shifted.logLikelihood('a', t))
        assertThat(shifted.logLikelihood('s', at('s'))).isWithin(1e-6f).of(0f)

        val wide =
            SpatialModel(TestKeyboard.layout.keys, correction = { KeyAdjustment(sigmaScale = 2f) })
        val edge = at('a', 0.5f * w)
        assertThat(wide.logLikelihood('a', edge)).isGreaterThan(model.logLikelihood('a', edge))
    }

    @Test
    fun cacheReusesModelsPerLayoutAndResetsOnNewCorrection() {
        val cache = SpatialModelCache(SpatialConfig(), capacity = 2)
        val identity = TouchCorrection.Identity
        val a = cache.get(TestKeyboard.layout, identity)
        assertThat(cache.get(TestKeyboard.layout, identity)).isSameInstanceAs(a)
        val other = TestKeyboard.layout.copy(id = 2L)
        assertThat(cache.get(other, identity)).isNotSameInstanceAs(a)
        val replaced = cache.get(TestKeyboard.layout, TouchCorrection { KeyAdjustment() })
        assertThat(replaced).isNotSameInstanceAs(a)
    }

    @Test
    fun touchBetweenKeysIsAmbiguous() {
        val a = TestKeyboard.touch('i')!!
        val o = TestKeyboard.touch('o')!!
        val mid = TouchPoint((a.x + o.x) / 2f, a.y, 0)
        assertThat(model.logLikelihood('i', mid)).isWithin(1e-4f).of(model.logLikelihood('o', mid))
    }
}
