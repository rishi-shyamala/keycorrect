package dev.keycorrect.feature.keyboard

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.layout.KeyAction
import org.junit.Test

class KeyboardStateTest {
    private val start = KeyboardState()

    @Test
    fun tapCyclesOffOnceOff() {
        val once = start.onShift(0)
        assertThat(once.shift).isEqualTo(ShiftState.Once)
        assertThat(once.onShift(1_000).shift).isEqualTo(ShiftState.Off)
    }

    @Test
    fun secondTapWithinWindowLocks() {
        val locked = start.onShift(1_000).onShift(1_250)
        assertThat(locked.shift).isEqualTo(ShiftState.Locked)
    }

    @Test
    fun tapJustOutsideWindowDoesNotLock() {
        val s = start.onShift(1_000).onShift(1_000 + KeyboardState.DOUBLE_TAP_MS + 1)
        assertThat(s.shift).isEqualTo(ShiftState.Off)
    }

    @Test
    fun tapAtWindowEdgeLocks() {
        val s = start.onShift(1_000).onShift(1_000 + KeyboardState.DOUBLE_TAP_MS)
        assertThat(s.shift).isEqualTo(ShiftState.Locked)
    }

    @Test
    fun lockedGoesOffOnNextTapEvenIfFast() {
        val locked = start.onShift(0).onShift(100)
        assertThat(locked.onShift(200).shift).isEqualTo(ShiftState.Off)
        assertThat(locked.onShift(5_000).shift).isEqualTo(ShiftState.Off)
    }

    @Test
    fun tapOnAutoGoesToOnce() {
        val auto = start.withAutoShift(true)
        assertThat(auto.onShift(1_000).shift).isEqualTo(ShiftState.Once)
    }

    @Test
    fun doubleTapFromAutoLocks() {
        val s = start.withAutoShift(true).onShift(1_000).onShift(1_100)
        assertThat(s.shift).isEqualTo(ShiftState.Locked)
    }

    @Test
    fun autoAndOnceAreConsumedByOneCharacter() {
        assertThat(start.withAutoShift(true).afterCharacter().shift).isEqualTo(ShiftState.Off)
        assertThat(start.onShift(0).afterCharacter().shift).isEqualTo(ShiftState.Off)
    }

    @Test
    fun lockedSurvivesCharacters() {
        val locked = start.onShift(0).onShift(100)
        assertThat(locked.afterCharacter().shift).isEqualTo(ShiftState.Locked)
    }

    @Test
    fun autoShiftNeverOverridesManualShift() {
        val once = start.onShift(0)
        val locked = once.onShift(100)
        assertThat(once.withAutoShift(true)).isEqualTo(once)
        assertThat(once.withAutoShift(false)).isEqualTo(once)
        assertThat(locked.withAutoShift(false)).isEqualTo(locked)
    }

    @Test
    fun autoShiftOffClearsAuto() {
        assertThat(start.withAutoShift(true).withAutoShift(false).shift).isEqualTo(ShiftState.Off)
    }

    @Test
    fun switchingLayerResetsShiftAndTapHistory() {
        val s = start.onShift(1_000).withLayer("symbols")
        assertThat(s.layerId).isEqualTo("symbols")
        assertThat(s.shift).isEqualTo(ShiftState.Off)
        // A tap right after switching is not a double tap with the earlier one.
        assertThat(s.onShift(1_100).shift).isEqualTo(ShiftState.Once)
    }

    @Test
    fun transformUsesShiftedTextWhenShifted() {
        val key = KeyAction.Text("a", "A")
        assertThat(start.transform(key)).isEqualTo("a")
        assertThat(start.withAutoShift(true).transform(key)).isEqualTo("A")
        assertThat(start.onShift(0).transform(key)).isEqualTo("A")
        assertThat(start.onShift(0).onShift(10).transform(key)).isEqualTo("A")
    }

    @Test
    fun transformFallsBackToTextWithoutShiftedText() {
        assertThat(start.onShift(0).transform(KeyAction.Text(".com"))).isEqualTo(".com")
    }
}
