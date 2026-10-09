package dev.keycorrect.feature.keyboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KeyboardStateTest {
    @Test
    fun shiftCyclesOffOnceLockedOff() {
        val s = KeyboardState()
        assertThat(s.onShift().shift).isEqualTo(ShiftState.Once)
        assertThat(s.onShift().onShift().shift).isEqualTo(ShiftState.Locked)
        assertThat(s.onShift().onShift().onShift().shift).isEqualTo(ShiftState.Off)
    }

    @Test
    fun oneShotShiftIsConsumedByCharacter() {
        val s = KeyboardState().onShift()
        assertThat(s.transform("a")).isEqualTo("A")
        assertThat(s.afterCharacter().shift).isEqualTo(ShiftState.Off)
    }

    @Test
    fun capsLockSurvivesCharacters() {
        val s = KeyboardState().onShift().onShift()
        assertThat(s.afterCharacter().shift).isEqualTo(ShiftState.Locked)
    }

    @Test
    fun switchingLayerResetsShift() {
        val s = KeyboardState().onShift().withLayer(Layer.Symbols)
        assertThat(s.shift).isEqualTo(ShiftState.Off)
        assertThat(s.transform("a")).isEqualTo("a")
    }
}
