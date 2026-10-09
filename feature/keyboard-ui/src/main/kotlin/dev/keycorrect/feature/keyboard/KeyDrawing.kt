package dev.keycorrect.feature.keyboard

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyStyle
import dev.keycorrect.core.layout.ResolvedKey

/** Space between neighbouring keys; each key is drawn inset by half of it inside its slot. */
internal val KeyGap = 6.dp
internal val KeyCornerRadius = 10.dp

/** The text drawn on [key] under [shift]; the Enter key shows [enterLabel]. */
internal fun keyLabel(key: ResolvedKey, shift: ShiftState, enterLabel: String): String =
    when (key.action) {
        KeyAction.Enter -> enterLabel
        KeyAction.Shift ->
            when (shift) {
                ShiftState.Off -> "⇧"
                ShiftState.Auto,
                ShiftState.Once -> "⬆"
                ShiftState.Locked -> "⇪"
            }
        else -> if (shift != ShiftState.Off) key.shiftedLabel ?: key.label else key.label
    }

/**
 * The accessibility label of [key]. [shift] is the current shift state for the shift key and null
 * for every other key.
 */
internal fun keyDescription(key: ResolvedKey, shift: ShiftState?): String =
    when (shift) {
        null,
        ShiftState.Off -> key.description
        ShiftState.Auto,
        ShiftState.Once -> "${key.description}, on"
        ShiftState.Locked -> "Caps lock on"
    }

/** Fill and content colours of [key]; shift looks filled whenever it is on. */
internal fun keyColors(
    key: ResolvedKey,
    pressed: Boolean,
    shift: ShiftState,
    colors: ColorScheme,
): Pair<Color, Color> {
    if (pressed) return colors.primaryContainer to colors.onPrimaryContainer
    return when (key.style) {
        KeyStyle.Action -> colors.primary to colors.onPrimary
        KeyStyle.Modifier ->
            if (key.action == KeyAction.Shift && shift != ShiftState.Off) {
                colors.tertiaryContainer to colors.onTertiaryContainer
            } else {
                colors.secondaryContainer to colors.onSecondaryContainer
            }
        KeyStyle.Character,
        KeyStyle.Space -> colors.surfaceContainerHighest to colors.onSurface
    }
}
