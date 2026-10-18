package dev.keycorrect.feature.keyboard

import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The slot font shrink rule that [fitText] applies to a word that overflows its slot. */
class FitTextTest {
    @Test
    fun anOverflowingWordShrinksToAFifthLessButNotBelowTheMinimum() {
        assertThat(shrunkSize(20.sp)).isEqualTo(16.sp)
        assertThat(shrunkSize(14.sp)).isEqualTo(12.sp)
    }

    @Test
    fun aSizeAtOrBelowTheMinimumIsLeftAlone() {
        assertThat(shrunkSize(12.sp)).isEqualTo(12.sp)
        assertThat(shrunkSize(10.sp)).isEqualTo(10.sp)
    }
}
