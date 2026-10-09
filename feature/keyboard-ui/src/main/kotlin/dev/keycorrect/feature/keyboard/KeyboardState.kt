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
     * Handles a tap on shift at [nowMillis]. Off/Auto go to Once and Once back to Off, except that
     * a tap within [DOUBLE_TAP_MS] of the previous shift tap engages caps lock; Locked goes to Off.
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
                        shift == ShiftState.Once -> ShiftState.Off
                        else -> ShiftState.Once
                    }
            }
        return copy(shift = next, lastShiftTapMillis = nowMillis)
    }

    /** Sets or clears [ShiftState.Auto] from auto-capitalisation; never touches a manual shift. */
    fun withAutoShift(on: Boolean): KeyboardState =
        when {
            shift == ShiftState.Locked || shift == ShiftState.Once -> this
            on -> copy(shift = ShiftState.Auto)
            else -> copy(shift = ShiftState.Off)
        }

    /** Called after a character is committed: one-shot (Auto or Once) shift is consumed. */
    fun afterCharacter(): KeyboardState =
        if (shift == ShiftState.Auto || shift == ShiftState.Once) copy(shift = ShiftState.Off)
        else this

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
