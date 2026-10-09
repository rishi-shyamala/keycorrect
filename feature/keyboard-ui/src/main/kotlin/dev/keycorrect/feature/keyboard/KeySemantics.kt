package dev.keycorrect.feature.keyboard

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
 * through to the key surface underneath. Recomposes only when [layer] or [shift] changes.
 */
@Composable
internal fun KeySemantics(
    layer: KeyboardLayer,
    shift: ShiftState,
    onKey: (KeyAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        content = {
            for (row in layer.rows) {
                for (key in row.keys) {
                    if (key.isSpacer) continue
                    val keyShift = if (key.action == KeyAction.Shift) shift else null
                    Box(
                        Modifier.clearAndSetSemantics {
                            contentDescription = keyDescription(key, keyShift)
                            role = Role.Button
                            onClick {
                                onKey(key.action)
                                true
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
