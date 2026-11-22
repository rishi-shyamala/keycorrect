package dev.keycorrect.feature.keyboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The rule [fitText] applies to a word that overflows its slot: shrink, then cut at the start. */
class FitTextTest {
    private fun decide(base: Float, min: Float, available: Float, atBase: Float, atMin: Float) =
        decideFit(base, min, available, atBase, { atMin })

    @Test
    fun aWordThatFitsKeepsTheChosenSize() {
        val d = decide(base = 14f, min = 12f, available = 100f, atBase = 100f, atMin = 85f)
        assertThat(d.size).isEqualTo(14f)
        assertThat(d.ellipsizeStart).isFalse()
    }

    @Test
    fun anOverflowingWordShrinksContinuouslyToFit() {
        val d = decide(base = 20f, min = 8f, available = 80f, atBase = 100f, atMin = 40f)
        assertThat(d.size).isWithin(0.001f).of(16f)
        assertThat(d.ellipsizeStart).isFalse()
    }

    @Test
    fun shrinkingStopsAtTheMinimumAndTheStartIsCut() {
        val d = decide(base = 14f, min = 12f, available = 50f, atBase = 100f, atMin = 86f)
        assertThat(d.size).isEqualTo(12f)
        assertThat(d.ellipsizeStart).isTrue()
    }

    @Test
    fun aWordThatFitsExactlyAtTheMinimumIsNotCut() {
        val d = decide(base = 14f, min = 12f, available = 86f, atBase = 100f, atMin = 86f)
        assertThat(d.size).isEqualTo(12f)
        assertThat(d.ellipsizeStart).isFalse()
    }

    @Test
    fun aMinimumAboveTheSizeIsCappedAtTheSize() {
        val d = decide(base = 10f, min = 14f, available = 50f, atBase = 100f, atMin = 100f)
        assertThat(d.size).isEqualTo(10f)
        assertThat(d.ellipsizeStart).isTrue()
    }

    @Test
    fun startEllipsisKeepsTheEndOfTheWord() {
        assertThat(startEllipsized("computing", 4)).isEqualTo("…ting")
        assertThat(startEllipsized("cat", 5)).isEqualTo("cat")
    }

    @Test
    fun startEllipsisNeverSplitsASurrogatePair() {
        assertThat(startEllipsized("ab😀cd", 3)).isEqualTo("…cd")
    }
}
