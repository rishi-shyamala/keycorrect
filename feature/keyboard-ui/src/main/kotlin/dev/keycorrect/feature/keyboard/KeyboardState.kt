package dev.keycorrect.feature.keyboard

import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardSet

/** Shift mode of the letters layer. */
enum class ShiftState {
    Off,

    /** Upper-case for the next character, set and cleared by auto-capitalisation. */
    Auto,

    /** Upper-case for the next character, set by tapping shift. */
    Once,

    /** Caps lock. */
    Locked,
}

/**
 * Immutable keyboard mode: the current layer id and shift state. Every transition is pure (time is
 * passed in) so it can be unit-tested.
 */
data class KeyboardState(
    val layerId: String = KeyboardSet.LETTERS,
    val shift: ShiftState = ShiftState.Off,
    private val lastShiftTapMillis: Long? = null,
) {
    /** True when the next character is typed shifted. */
    val isShifted: Boolean
        get() = shift != ShiftState.Off

    /**
     * Handles a press on shift at [nowMillis]. Off goes to Once, and Once and Auto go to Off (a tap
     * cancels the automatic capital, as on Gboard), except that a press within [DOUBLE_TAP_MS] of
     * the previous one engages caps lock; Locked goes to Off.
     */
    fun onShift(nowMillis: Long): KeyboardState {
        val doubleTap = lastShiftTapMillis?.let { nowMillis - it <= DOUBLE_TAP_MS } == true
        val next =
            when (shift) {
                ShiftState.Locked -> ShiftState.Off
                ShiftState.Off,
                ShiftState.Auto,
                ShiftState.Once ->
                    when {
                        doubleTap -> ShiftState.Locked
                        shift == ShiftState.Off -> ShiftState.Once
                        else -> ShiftState.Off
                    }
            }
        return copy(shift = next, lastShiftTapMillis = nowMillis)
    }

    /**
     * Shift is being held while a key is typed: makes sure the key is shifted even if the press
     * itself turned shift off (Auto or Once, or Locked, were just cancelled).
     */
    fun withChordShift(): KeyboardState =
        if (shift == ShiftState.Off) copy(shift = ShiftState.Once) else this

    /**
     * Shift was released after being used as a modifier: back to Off, and the chord does not count
     * as a tap towards caps lock. A caps lock engaged by that same press (double tap) survives.
     */
    fun afterShiftChord(): KeyboardState =
        if (shift == ShiftState.Locked) this
        else copy(shift = ShiftState.Off, lastShiftTapMillis = null)

    /** Sets or clears [ShiftState.Auto] from auto-capitalisation; never touches a manual shift. */
    fun withAutoShift(on: Boolean): KeyboardState =
        when {
            shift == ShiftState.Locked || shift == ShiftState.Once -> this
            on -> copy(shift = ShiftState.Auto)
            else -> copy(shift = ShiftState.Off)
        }

    /**
     * Called after a character is committed: one-shot (Auto or Once) shift is consumed, unless
     * shift is still held ([shiftHeld]) and so keeps applying to the next character too.
     */
    fun afterCharacter(shiftHeld: Boolean = false): KeyboardState =
        if (!shiftHeld && (shift == ShiftState.Auto || shift == ShiftState.Once)) {
            copy(shift = ShiftState.Off)
        } else {
            this
        }

    /** Switching layer resets shift to Off. */
    fun withLayer(layerId: String): KeyboardState = KeyboardState(layerId)

    /** What [action] types under the current shift state. */
    fun transform(action: KeyAction.Text): String =
        if (isShifted) action.shiftedText ?: action.text else action.text

    companion object {
        /** Maximum gap between two shift taps that count as a double tap. */
        const val DOUBLE_TAP_MS = 300L
    }
}
