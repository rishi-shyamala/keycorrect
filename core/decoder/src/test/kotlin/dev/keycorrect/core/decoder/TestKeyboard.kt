package dev.keycorrect.core.decoder

import dev.keycorrect.core.engineapi.ComposingInput
import dev.keycorrect.core.engineapi.KeyLayoutModel
import dev.keycorrect.core.engineapi.KeySpec
import dev.keycorrect.core.engineapi.TouchPoint
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.layout.KeyboardGeometry
import dev.keycorrect.core.layout.KeyboardSet

/** The real QWERTY letters layer at phone size, as a [KeyLayoutModel], plus synthetic touches. */
object TestKeyboard {
    private val geometry =
        KeyboardGeometry(
            KeyboardComposer(BuiltInLayouts.repository())
                .compose(KeyboardSet.Default, KeyboardSet.LETTERS),
            width = 1080f,
            height = 520f,
        )

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

    val keyWidth: Float
        get() = layout.keys.first { it.char == 'q' }.width

    /**
     * A tap in the middle of the key for [c] shifted by ([dx], [dy]) pixels; null if no such key.
     */
    fun touch(c: Char, dx: Float = 0f, dy: Float = 0f, t: Long = 0): TouchPoint? {
        val k = layout.keys.firstOrNull { it.char == c.lowercaseChar() } ?: return null
        return TouchPoint(k.centerX + dx, k.centerY + dy, t)
    }

    /** Input typed with every tap in the centre of the intended key. */
    fun typed(text: String): ComposingInput =
        ComposingInput(text, text.mapIndexed { i, c -> touch(c, t = i * 120L) })

    /**
     * Like [typed] but with the tap for char [index] landing at ([dx], [dy]) from its key centre.
     */
    fun typedWithSlip(text: String, index: Int, dx: Float, dy: Float): ComposingInput =
        ComposingInput(
            text,
            text.mapIndexed { i, c ->
                if (i == index) touch(c, dx, dy, i * 120L) else touch(c, t = i * 120L)
            },
        )

    /** Input whose chars have no touch (popup, paste). */
    fun pasted(text: String): ComposingInput = ComposingInput(text, text.map { null })
}
