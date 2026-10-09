package dev.keycorrect.feature.keyboard

enum class ShiftState {
    Off,
    /** Next character is upper-case, then shift turns off. */
    Once,
    /** Caps lock. */
    Locked,
}

/** Immutable keyboard mode. All transitions are pure so they can be unit-tested. */
data class KeyboardState(val layer: Layer = Layer.Letters, val shift: ShiftState = ShiftState.Off) {
    fun onShift(): KeyboardState =
        copy(
            shift =
                when (shift) {
                    ShiftState.Off -> ShiftState.Once
                    ShiftState.Once -> ShiftState.Locked
                    ShiftState.Locked -> ShiftState.Off
                }
        )

    /** Called after a character is committed: one-shot shift is consumed. */
    fun afterCharacter(): KeyboardState =
        if (shift == ShiftState.Once) copy(shift = ShiftState.Off) else this

    fun withLayer(layer: Layer): KeyboardState = copy(layer = layer, shift = ShiftState.Off)

    /** Applies the current shift state to a typed character. */
    fun transform(text: String): String =
        if (shift == ShiftState.Off || layer != Layer.Letters) text else text.uppercase()
}
