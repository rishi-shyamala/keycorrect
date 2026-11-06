package dev.keycorrect.core.learning.fit

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.learning.model.KeyTouchStatistics
import dev.keycorrect.core.learning.model.ProfileTouchStatistics
import org.junit.Test

class KeyboardFitTest {
    private val sizes = FitSizes(keyHeightDp = 54, keyGapDp = 3, edgePaddingDp = 3)
    private val limits = FitLimits(40..80, 0..6, 0..4)
    private val width = 360.0
    private val w = KeyboardFit.cellWidthDp(width, sizes)
    private val h = 54.0

    private fun key(dx: Float = 0f, dy: Float = 0f, sx: Float, sy: Float, n: Int = 100) =
        KeyTouchStatistics('a', n, dx, dy, sx, sy)

    private fun profile(words: Int, vararg keys: KeyTouchStatistics) =
        ProfileTouchStatistics(
            "Phone/port/qwerty",
            words,
            keys.sumOf { it.sampleCount },
            keys.toList(),
            0.2f,
            0.2f,
            0f,
            0f,
            0.2f,
            0.2f,
            words >= 300,
        )

    private fun miss(k: KeyTouchStatistics, cw: Double = w, ch: Double = h) =
        KeyboardFit.missProbability(k, w, h, cw, ch)

    @Test
    fun erfMatchesKnownValues() {
        assertThat(KeyboardFit.erf(0.0)).isWithin(1e-7).of(0.0)
        assertThat(KeyboardFit.erf(1.0)).isWithin(1e-6).of(0.8427008)
        assertThat(KeyboardFit.erf(-1.0)).isWithin(1e-6).of(-0.8427008)
    }

    @Test
    fun centredTightTouchesOnBigKeyAlmostNeverMiss() {
        assertThat(miss(key(sx = 0.05f, sy = 0.05f))).isLessThan(1e-6)
    }

    @Test
    fun zeroSpreadIsAPointMass() {
        assertThat(miss(key(sx = 0f, sy = 0f))).isEqualTo(0.0)
        assertThat(miss(key(dx = 0.7f, sx = 0f, sy = 0f))).isEqualTo(1.0)
    }

    @Test
    fun offsetAndSpreadIncreaseMisses() {
        val base = miss(key(sx = 0.2f, sy = 0.2f))
        assertThat(miss(key(dy = 0.3f, sx = 0.2f, sy = 0.2f))).isGreaterThan(base)
        assertThat(miss(key(sx = 0.3f, sy = 0.3f))).isGreaterThan(base)
    }

    @Test
    fun tallerKeyReducesVerticalMisses() {
        val k = key(sx = 0.05f, sy = 0.35f)
        assertThat(miss(k, ch = 62.0)).isLessThan(miss(k))
    }

    @Test
    fun expectedRateIsWeightedBySampleCount() {
        val good = key(sx = 0.02f, sy = 0.02f, n = 900)
        val bad = key(dx = 0.7f, sx = 0f, sy = 0f, n = 100)
        assertThat(KeyboardFit.expectedMissRate(listOf(good, bad), w, h, w, h))
            .isWithin(1e-6)
            .of(0.1)
        assertThat(KeyboardFit.expectedMissRate(emptyList(), w, h, w, h)).isEqualTo(0.0)
    }

    @Test
    fun recommendsTallerKeysForVerticalScatter() {
        val p = profile(400, key(sx = 0.05f, sy = 0.4f, n = 500))
        val r = KeyboardFit.recommend(p, width, sizes, limits)!!
        assertThat(r.kind).isEqualTo(FitKind.KEY_HEIGHT)
        assertThat(r.percent).isEqualTo(15)
        assertThat(r.proposed.keyHeightDp).isEqualTo(62)
        assertThat(r.relativePercent).isAtLeast(10)
    }

    @Test
    fun needsEnoughWords() {
        val p = profile(299, key(sx = 0.05f, sy = 0.4f, n = 500))
        assertThat(KeyboardFit.recommend(p, width, sizes, limits)).isNull()
    }

    @Test
    fun noRecommendationWhenAlreadyAccurate() {
        val p = profile(400, key(sx = 0.05f, sy = 0.05f, n = 500))
        assertThat(KeyboardFit.recommend(p, width, sizes, limits)).isNull()
    }

    @Test
    fun anythingRecommendedIsMeaningful() {
        for (sy in listOf(0.1f, 0.12f, 0.15f, 0.2f, 0.25f)) {
            val r =
                KeyboardFit.recommend(profile(400, key(sx = 0.1f, sy = sy)), width, sizes, limits)
            if (r != null) {
                assertThat(r.relativeReduction).isAtLeast(KeyboardFit.MIN_RELATIVE_REDUCTION)
                assertThat(r.absoluteReduction).isAtLeast(KeyboardFit.MIN_ABSOLUTE_REDUCTION)
            }
        }
    }

    @Test
    fun candidatesStayInsideClampRanges() {
        val cs = KeyboardFit.candidates(FitSizes(78, 0, 0), limits)
        assertThat(cs.none { it.sizes.keyHeightDp > 80 }).isTrue()
        assertThat(cs.none { it.kind == FitKind.KEY_GAP || it.kind == FitKind.EDGE_PADDING })
            .isTrue()
        assertThat(KeyboardFit.candidates(FitSizes(80, 0, 0), limits)).isEmpty()
    }

    @Test
    fun maxedHeightLeavesOnlyAnUnmeaningfulEdgeChange() {
        val p = profile(400, key(sx = 0.4f, sy = 0.02f, n = 500))
        val r = KeyboardFit.recommend(p, width, FitSizes(80, 3, 4), limits)
        assertThat(r).isNull()
    }

    @Test
    fun dismissalLastsFourteenDays() {
        val day = 24L * 60 * 60 * 1000
        assertThat(KeyboardFit.isDismissed(null, 100 * day)).isFalse()
        assertThat(KeyboardFit.isDismissed(100 * day, 113 * day)).isTrue()
        assertThat(KeyboardFit.isDismissed(100 * day, 114 * day)).isFalse()
    }

    @Test
    fun nonLetterKeysAreIgnored() {
        val letter = key(sx = 0.1f, sy = 0.1f)
        val digit = KeyTouchStatistics('5', 1000, 0.4f, 0f, 0.5f, 0.5f)
        val only = KeyboardFit.expectedMissRate(listOf(letter), w, h, w, h)
        assertThat(KeyboardFit.expectedMissRate(listOf(letter, digit), w, h, w, h))
            .isEqualTo(only)
    }
}
