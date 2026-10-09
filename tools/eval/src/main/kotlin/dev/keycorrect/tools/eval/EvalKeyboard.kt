package dev.keycorrect.tools.eval

import dev.keycorrect.core.engineapi.KeyLayoutModel
import dev.keycorrect.core.engineapi.KeySpec
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.layout.KeyboardGeometry
import dev.keycorrect.core.layout.KeyboardSet

/**
 * A QWERTY letters layer laid out in a phone-sized pixel box. Besides the [KeyLayoutModel] handed
 * to the engine, it can map a touch to the key it lands on ([charAt]), which is what a real
 * keyboard inserts: a sloppy tap produces the neighbour's letter, not the intended one.
 */
class EvalKeyboard(
    val widthPx: Float = DEFAULT_WIDTH_PX,
    val rows: Int = DEFAULT_ROWS,
    val rowHeightDp: Float = DEFAULT_ROW_DP,
    val density: Float = DEFAULT_DENSITY,
) {
    private val geometry =
        KeyboardGeometry(
            KeyboardComposer(BuiltInLayouts.repository())
                .compose(KeyboardSet.Default, KeyboardSet.LETTERS),
            width = widthPx,
            height = rows * rowHeightDp * density,
        )

    val heightPx: Float
        get() = geometry.height

    val layout: KeyLayoutModel =
        KeyLayoutModel(
            id = 1L,
            keys =
                geometry.slots
                    .filter { (it.key.action as? KeyAction.Text)?.text?.length == 1 }
                    .map {
                        KeySpec(
                            (it.key.action as KeyAction.Text).text[0],
                            it.centerX,
                            it.centerY,
                            it.right - it.left,
                            it.bottom - it.top,
                        )
                    },
        )

    private val byChar: Map<Char, KeySpec> = layout.keys.associateBy { it.char.lowercaseChar() }

    /** The letter key for [c] (case-insensitive), or null when [c] isn't on the letters layer. */
    fun key(c: Char): KeySpec? = byChar[c.lowercaseChar()]

    /** True if [c] is typed with a single tap on the letters layer. */
    fun hasKey(c: Char): Boolean = key(c) != null

    /** The char a tap at ([x], [y]) inserts (lowercase), or null when no text key is there. */
    fun charAt(x: Float, y: Float): Char? {
        val slot = geometry.hitTest(x, y) ?: return null
        return (slot.key.action as? KeyAction.Text)?.text?.singleOrNull()?.lowercaseChar()
    }

    /** Text keys whose centre is closest to [c]'s key, nearest first (excluding [c] itself). */
    fun neighbours(c: Char, count: Int = 4): List<KeySpec> {
        val k = key(c) ?: return emptyList()
        return layout.keys
            .filter { it.char.lowercaseChar() != k.char.lowercaseChar() && it.char.isLetter() }
            .sortedBy { sq(it.centerX - k.centerX) + sq(it.centerY - k.centerY) }
            .take(count)
    }

    private fun sq(v: Float) = v * v

    companion object {
        const val DEFAULT_WIDTH_PX = 1080f
        const val DEFAULT_ROWS = 4
        const val DEFAULT_ROW_DP = 54f
        const val DEFAULT_DENSITY = 2.75f
    }
}
