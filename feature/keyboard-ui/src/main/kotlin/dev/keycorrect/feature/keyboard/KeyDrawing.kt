package dev.keycorrect.feature.keyboard

import android.view.inputmethod.EditorInfo
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.keycorrect.core.input.EnterAction
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
 * for every other key; the Enter key is spoken as [enterDescription] ("Search", "Send", ...).
 */
internal fun keyDescription(
    key: ResolvedKey,
    shift: ShiftState?,
    enterDescription: String = key.description,
): String =
    when {
        key.action == KeyAction.Enter -> enterDescription
        shift == null || shift == ShiftState.Off -> key.description
        shift == ShiftState.Locked -> "Caps lock on"
        else -> "${key.description}, on"
    }

/** The text on the Enter key for [action]: its action name, or a return arrow for a line break. */
internal fun enterLabel(action: EnterAction): String =
    when (action) {
        EnterAction.NewLine -> "↵"
        is EnterAction.Editor ->
            // "Previous" is too wide for a key.
            if (action.actionId == EditorInfo.IME_ACTION_PREVIOUS) "Prev"
            else editorActionName(action.actionId) ?: "↵"
    }

/** What TalkBack says for the Enter key: the action name, or [fallback] for a plain line break. */
internal fun enterDescription(action: EnterAction, fallback: String): String =
    when (action) {
        EnterAction.NewLine -> fallback
        is EnterAction.Editor -> editorActionName(action.actionId) ?: fallback
    }

private fun editorActionName(actionId: Int): String? =
    when (actionId) {
        EditorInfo.IME_ACTION_GO -> "Go"
        EditorInfo.IME_ACTION_SEARCH -> "Search"
        EditorInfo.IME_ACTION_SEND -> "Send"
        EditorInfo.IME_ACTION_NEXT -> "Next"
        EditorInfo.IME_ACTION_PREVIOUS -> "Previous"
        EditorInfo.IME_ACTION_DONE -> "Done"
        else -> null
    }

/** Fill colour of [key]; shift looks filled whenever it is on. */
internal fun keyFill(key: ResolvedKey, pressed: Boolean, shift: ShiftState, colors: ColorScheme) =
    when {
        pressed -> colors.primaryContainer
        key.style == KeyStyle.Action -> colors.primary
        key.style == KeyStyle.Modifier ->
            if (key.action == KeyAction.Shift && shift != ShiftState.Off) {
                colors.tertiaryContainer
            } else {
                colors.secondaryContainer
            }
        else -> colors.surfaceContainerHighest
    }

/** Colour of the label on [key], matching [keyFill]. */
internal fun keyContent(
    key: ResolvedKey,
    pressed: Boolean,
    shift: ShiftState,
    colors: ColorScheme,
): Color =
    when {
        pressed -> colors.onPrimaryContainer
        key.style == KeyStyle.Action -> colors.onPrimary
        key.style == KeyStyle.Modifier ->
            if (key.action == KeyAction.Shift && shift != ShiftState.Off) {
                colors.onTertiaryContainer
            } else {
                colors.onSecondaryContainer
            }
        else -> colors.onSurface
    }
