package dev.keycorrect.feature.keyboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.keycorrect.core.input.FieldKind
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.layout.BottomRowVariant
import dev.keycorrect.core.layout.ComposeOptions
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.layout.KeyboardLayer
import dev.keycorrect.core.layout.KeyboardSet
import dev.keycorrect.core.settings.KeyboardSettings

/**
 * The composed layer being shown and the options and bottom-row variant it was composed with, plus
 * the keyboard height. The layer id and shift state stay with [KeyboardController].
 */
internal class LayerComposition(
    private val composer: KeyboardComposer,
    private val profile: () -> FieldProfile,
) {
    private val keyboardSet = KeyboardSet.Default
    private var variant = BottomRowVariant.Text

    /** The options the current layers were composed with. */
    var options = ComposeOptions()
        private set

    /** The layer being shown, composed for the current field and settings. */
    var layer: KeyboardLayer by mutableStateOf(composer.compose(keyboardSet, KeyboardSet.LETTERS))
        private set

    /**
     * Keyboard height in row units (multiples of the key height setting), the same on every layer
     * of the field: the letters layer's total row weight for the current field and options. Shorter
     * layers (symbols, numpad) stretch their rows to fill it, so switching layers never resizes the
     * IME window.
     */
    var heightUnits: Float by mutableFloatStateOf(DEFAULT_HEIGHT_UNITS)
        private set

    /** Starts over for a field of [kind]: its home layer composed with [newOptions]. */
    fun rebuild(kind: FieldKind, newOptions: ComposeOptions, layerId: String) {
        variant = variantFor(kind)
        options = newOptions
        layer = composer.compose(keyboardSet, layerId, variant, options)
        heightUnits = referenceHeightUnits(layer.takeIf { layerId == KeyboardSet.LETTERS })
    }

    /**
     * Shows [layerId]; false (nothing changed) when the layout names a layer that doesn't exist: a
     * user-edited layout may, and the keyboard stays put rather than crash.
     */
    fun show(layerId: String): Boolean {
        val next =
            runCatching { composer.compose(keyboardSet, layerId, variant, options) }.getOrNull()
                ?: return false
        layer = next
        return true
    }

    /** Adopts [newOptions]; recomposes the current layer and the height if they changed. */
    fun applyOptions(newOptions: ComposeOptions, layerId: String) {
        if (newOptions == options) return
        options = newOptions
        layer = composer.compose(keyboardSet, layerId, variant, options)
        heightUnits = referenceHeightUnits(layer.takeIf { layerId == KeyboardSet.LETTERS })
    }

    /**
     * Total row weight of the letters layer for the current field. The number row is left out for
     * numeric fields: their pads already have the digits, so it would only make them taller.
     */
    private fun referenceHeightUnits(composedLetters: KeyboardLayer? = null): Float {
        val kind = profile().kind
        val numeric =
            kind == FieldKind.Number || kind == FieldKind.Phone || kind == FieldKind.DateTime
        // The letters layer just composed with these options is the reference: compose it once.
        val reference =
            composedLetters?.takeIf { !numeric }
                ?: runCatching {
                    composer.compose(
                        keyboardSet,
                        KeyboardSet.LETTERS,
                        variant,
                        if (numeric) options.copy(numberRow = false) else options,
                    )
                }
                    .getOrNull()
                ?: return heightUnits
        return reference.rows.sumOf { it.height.toDouble() }.toFloat()
    }

    companion object {
        const val NUMPAD = "numpad"
        const val PHONE = "phone"
        const val DATETIME = "datetime"
        const val DEFAULT_HEIGHT_UNITS = 4f

        fun variantFor(kind: FieldKind): BottomRowVariant =
            when (kind) {
                FieldKind.Email -> BottomRowVariant.Email
                FieldKind.Url -> BottomRowVariant.Url
                else -> BottomRowVariant.Text
            }

        /** The layer a field of [kind] opens on, and ABC returns to from a panel. */
        fun homeLayerFor(kind: FieldKind): String =
            when (kind) {
                FieldKind.Number -> NUMPAD
                FieldKind.Phone -> PHONE
                FieldKind.DateTime -> DATETIME
                else -> KeyboardSet.LETTERS
            }

        /**
         * The compose options for a [profile] and [settings]; [fieldModes] when the field is text.
         */
        fun composeOptions(
            profile: FieldProfile,
            settings: KeyboardSettings,
            fieldModes: Boolean,
        ): ComposeOptions =
            if (profile.kind == FieldKind.Number) {
                ComposeOptions(
                    numberRow = settings.numberRow || profile.sensitive,
                    signedNumber = profile.signedNumber,
                    decimalNumber = profile.decimalNumber,
                    fieldModes = fieldModes,
                )
            } else {
                ComposeOptions(
                    numberRow = settings.numberRow || profile.sensitive,
                    terminalRow = profile.kind == FieldKind.Terminal,
                    bottomKeyWidth = settings.bottomKeyWidth,
                    modeEnterKeyWidth = settings.modeEnterKeyWidth,
                    symbolHints = settings.symbolHints,
                    // No emoji (nor, from PR C, clipboard) in password fields.
                    emojiKey = !profile.sensitive,
                    fieldModes = fieldModes,
                    // Voice typing (long-press the comma) is off in password fields and by setting.
                    voiceKey = settings.voiceEnabled && !profile.sensitive,
                )
            }
    }
}
