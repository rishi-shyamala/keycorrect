package dev.keycorrect.feature.keyboard

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Constraints
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardGeometry
import dev.keycorrect.core.layout.KeyboardLayer
import kotlin.math.roundToInt

/**
 * A semantics-only mirror of the drawn keys for accessibility services: one invisible node per key,
 * positioned where the key is drawn. The nodes have no pointer input, so sighted touches pass
 * through to the key surface underneath. Recomposes only when [layer] or [enterDescription]
 * changes: [shift] is read inside the shift key's semantics node alone, so a shift change updates
 * that node without recomposing or re-laying out the rest. The same goes for the Ctrl and Alt keys
 * and [terminalModifiers].
 */
@Suppress("LongParameterList") // one read-only input per drawn state
@Composable
internal fun KeySemantics(
    layer: KeyboardLayer,
    shift: () -> ShiftState,
    enterDescription: String,
    onKey: (KeyAction) -> Unit,
    modifier: Modifier = Modifier,
    terminalModifiers: TerminalModifiers? = null,
    onClipboard: (() -> Unit)? = null,
    onVoice: (() -> Unit)? = null,
) {
    val clipboardLabel = stringResource(R.string.clipboard_key_action)
    val voiceLabel = stringResource(R.string.voice_key_action)
    Layout(
        content = {
            for (row in layer.rows) {
                for (key in row.keys) {
                    if (key.isSpacer) continue
                    val isShift = key.action == KeyAction.Shift
                    Box(
                        Modifier.clearAndSetSemantics {
                            contentDescription =
                                keyDescription(
                                    key,
                                    if (isShift) shift() else null,
                                    enterDescription,
                                    latchOf(key, terminalModifiers),
                                )
                            role = Role.Button
                            if (key.action == KeyAction.Emoji && onClipboard != null) {
                                // The long press has no gesture for a screen reader.
                                customActions = clipboardActions(clipboardLabel, onClipboard)
                            } else if (key.longPress == KeyAction.Voice && onVoice != null) {
                                customActions = clipboardActions(voiceLabel, onVoice)
                            }
                            onClick {
                                // A failing host (e.g. a dead input connection) must not take
                                // down the accessibility service's call.
                                try {
                                    onKey(key.action)
                                    true
                                } catch (_: Exception) {
                                    false
                                }
                            }
                        }
                    )
                }
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val geometry =
            KeyboardGeometry(layer, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val slots = geometry.slots.filter { !it.key.isSpacer }
        val placeables = measurables.mapIndexed { i, measurable ->
            val slot = slots[i]
            measurable.measure(
                Constraints.fixed(
                    (slot.right - slot.left).roundToInt(),
                    (slot.bottom - slot.top).roundToInt(),
                )
            )
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { i, placeable ->
                placeable.place(slots[i].left.roundToInt(), slots[i].top.roundToInt())
            }
        }
    }
}

private fun clipboardActions(label: String, open: () -> Unit) =
    listOf(
        CustomAccessibilityAction(label) {
            try {
                open()
                true
            } catch (_: Exception) {
                false
            }
        }
    )
