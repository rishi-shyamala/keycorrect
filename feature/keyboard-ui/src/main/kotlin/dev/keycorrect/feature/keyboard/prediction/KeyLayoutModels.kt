package dev.keycorrect.feature.keyboard.prediction

import dev.keycorrect.core.engineapi.KeyLayoutModel
import dev.keycorrect.core.engineapi.KeySpec
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyStyle
import dev.keycorrect.core.layout.KeyboardGeometry

/** Builds the engine's view of the keyboard from the geometry being drawn. */
object KeyLayoutModels {
    /**
     * The single-char character keys of [geometry], centred and sized in the geometry's own pixel
     * space (the space touches are reported in), tagged with [id]. The key's base (unshifted) char
     * is used so the model matches lower-case input.
     */
    fun from(geometry: KeyboardGeometry, id: Long): KeyLayoutModel =
        KeyLayoutModel(
            id,
            geometry.slots.mapNotNull { slot ->
                val action = slot.key.action as? KeyAction.Text ?: return@mapNotNull null
                if (slot.key.style != KeyStyle.Character || action.text.length != 1) {
                    return@mapNotNull null
                }
                KeySpec(
                    action.text[0],
                    slot.centerX,
                    slot.centerY,
                    slot.right - slot.left,
                    slot.bottom - slot.top,
                )
            },
        )
}
