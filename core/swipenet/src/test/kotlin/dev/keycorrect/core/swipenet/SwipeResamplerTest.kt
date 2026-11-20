package dev.keycorrect.core.swipenet

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SwipeResamplerTest {
    private val points = SwipeResampler.POINTS

    @Test
    fun matchesFutosPythonReferenceOnEveryGoldenGesture() {
        for (case in Goldens.cases) {
            val features = SwipeResampler.features(case.x, case.y, case.t)
            assertThat(features).hasLength(2 * points)
            // The reference runs in float64; the port in float32 as FUTO's C++ does.
            assertThat(features).usingTolerance(1e-5).containsExactly(case.features).inOrder()
        }
    }

    @Test
    fun aStraightLineAtConstantSpeedIsSampledEvenly() {
        val n = 31
        val x = FloatArray(n) { it / (n - 1f) }
        val y = FloatArray(n) { 0.25f }
        val t = FloatArray(n) { it * 10f }
        val f = SwipeResampler.features(x, y, t)
        for (i in 0 until points) {
            assertThat(f[i]).isWithin(1e-5f).of(i / (points - 1f))
            assertThat(f[points + i]).isEqualTo(0.25f)
        }
    }

    @Test
    fun timeNotIndexDecidesWhereSamplesFall() {
        // Three points; the finger rests 900 ms at the middle one, then moves on in 100 ms.
        val f =
            SwipeResampler.features(
                floatArrayOf(0f, 0.5f, 0.5f, 1f),
                floatArrayOf(0f, 0f, 0f, 0f),
                floatArrayOf(0f, 100f, 1000f, 1100f),
            )
        // Most of the 1.1 s is spent at x = 0.5, so most samples are there.
        assertThat((0 until points).count { f[it] == 0.5f }).isGreaterThan(points / 2)
        assertThat(f[0]).isEqualTo(0f)
        assertThat(f[points - 1]).isEqualTo(1f)
    }

    @Test
    fun the60HzStepCountRoundsLikeTheOriginal() {
        // 494 ms -> round(29.64) + 1 = 31 samples; 520 ms -> round(31.2) + 1 = 32.
        for ((duration, expected) in listOf(494f to 31, 520f to 32, 8f to 2, 25f to 3)) {
            val (x, _) =
                SwipeResampler.to60Hz(
                    floatArrayOf(0f, 1f),
                    floatArrayOf(0f, 1f),
                    floatArrayOf(0f, duration),
                )
            assertThat(x).hasLength(expected)
            assertThat(x.last()).isEqualTo(1f)
        }
    }

    @Test
    fun coordinatesOutsideTheFrameAreClamped() {
        val f =
            SwipeResampler.features(
                floatArrayOf(-0.5f, 1.5f),
                floatArrayOf(2f, -1f),
                floatArrayOf(0f, 500f),
            )
        assertThat(f.all { it in 0f..1f }).isTrue()
        assertThat(f[0]).isEqualTo(0f)
        assertThat(f[points - 1]).isEqualTo(1f)
        assertThat(f[points]).isEqualTo(1f)
        assertThat(f[2 * points - 1]).isEqualTo(0f)
    }

    @Test
    fun degenerateGestures() {
        val empty = SwipeResampler.features(FloatArray(0), FloatArray(0), FloatArray(0))
        assertThat(empty.all { it == 0f }).isTrue()

        val one = SwipeResampler.features(floatArrayOf(0.3f), floatArrayOf(0.7f), floatArrayOf(5f))
        assertThat(one.take(points).distinct()).containsExactly(0.3f)
        assertThat(one.drop(points).distinct()).containsExactly(0.7f)

        // No duration: the first and last points, spread by index.
        val still =
            SwipeResampler.features(
                floatArrayOf(0f, 0.9f, 1f),
                floatArrayOf(0f, 0f, 0f),
                floatArrayOf(7f, 7f, 7f),
            )
        assertThat(still[0]).isEqualTo(0f)
        assertThat(still[points - 1]).isEqualTo(1f)
        assertThat(still[points / 2]).isWithin(0.02f).of(0.5f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedArraysAreRefused() {
        SwipeResampler.features(FloatArray(2), FloatArray(2), FloatArray(3))
    }

    @Test
    fun aLargeAbsoluteTimeOffsetGivesTheSameFeatures() {
        val n = 40
        val x = FloatArray(n) { (it * 7 % 13) / 13f }
        val y = FloatArray(n) { (it * 5 % 11) / 11f }
        // Multiples of 16 ms are exact in float32 even at this offset (spacing there is 8 ms), so
        // the only difference between the two inputs is the origin.
        val t = FloatArray(n) { it * 16f }
        val shifted = FloatArray(n) { 123_456_000f + t[it] }
        assertThat(SwipeResampler.features(x, y, shifted))
            .isEqualTo(SwipeResampler.features(x, y, t))
    }

    @Test
    fun aVeryLongGestureStaysBounded() {
        val x = floatArrayOf(0f, 1f)
        val y = floatArrayOf(0f, 0f)
        val (rx, _) = SwipeResampler.to60Hz(x, y, floatArrayOf(0f, 1e12f))
        assertThat(rx.size).isEqualTo(SwipeResampler.MAX_SAMPLES)
        val f = SwipeResampler.features(x, y, floatArrayOf(0f, 1e12f))
        assertThat(f[0]).isEqualTo(0f)
        assertThat(f[points - 1]).isEqualTo(1f)
        // Up to the bound nothing changes: 10 s is exactly 601 samples.
        val (tenSeconds, _) = SwipeResampler.to60Hz(x, y, floatArrayOf(0f, 10_000f))
        assertThat(tenSeconds.size).isEqualTo(SwipeResampler.MAX_SAMPLES)
    }
}
