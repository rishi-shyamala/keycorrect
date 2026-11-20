package dev.keycorrect.core.swipenet

import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardGeometry

/**
 * The encoder's coordinate frame: the bounding box of the letter keys' slots, each axis normalised
 * to [0, 1] on its own. On QWERTY that puts `q` at (0.05, 1/6) and `a` at (0.10, 1/2), the frame
 * FUTO Swipe was trained in. Any unit works (pixels, key widths) as long as touches and keys use
 * the same one.
 */
data class SwipeFrame(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(right > left && bottom > top) { "empty frame" }
    }

    fun x(px: Float): Float = (px - left) / (right - left)

    fun y(py: Float): Float = (py - top) / (bottom - top)
}

/**
 * The keys handed to the encoder: up to [MAX_KEYS] keys with their centres in the [frame]. [chars]
 * gives the character of each slot; slot `i` is emission class `i` (the blank is
 * [SwipeEmissions.BLANK]).
 */
class SwipeLayout(val frame: SwipeFrame, val chars: List<Char>, centres: List<Pair<Float, Float>>) {
    init {
        require(chars.size == centres.size) { "one centre per key" }
        require(chars.size in 1..MAX_KEYS) { "1..$MAX_KEYS keys, got ${chars.size}" }
        require(chars.map { it.lowercaseChar() }.toSet().size == chars.size) {
            "two keys type the same letter"
        }
    }

    /** The `layout_keys` input: (x, y) per slot, padding (0, 0). */
    val keys: FloatArray =
        FloatArray(2 * MAX_KEYS).also { out ->
            centres.forEachIndexed { i, (x, y) ->
                out[2 * i] = x
                out[2 * i + 1] = y
            }
        }

    /** The `layout_mask` input: 1 for a real key, 0 for padding (bool on the native side). */
    val mask: ByteArray = ByteArray(MAX_KEYS) { if (it < chars.size) 1 else 0 }

    /** The encoder features of a gesture given in the layout's own units (see [SwipeResampler]). */
    fun features(x: FloatArray, y: FloatArray, t: FloatArray): FloatArray =
        SwipeResampler.features(
            FloatArray(x.size) { frame.x(x[it]) },
            FloatArray(y.size) { frame.y(y[it]) },
            t,
        )

    companion object {
        /** The most keys the encoder takes (its export-time padding bound). */
        const val MAX_KEYS = 64

        /**
         * The letter keys of [geometry] (keys that type one letter), in row order, framed by the
         * bounding box of their slots. Null when the layer has no letters, more than [MAX_KEYS] of
         * them, or two slots typing the same letter (ignoring case), i.e. when the encoder can't be
         * used.
         */
        fun of(geometry: KeyboardGeometry): SwipeLayout? =
            of(
                geometry.slots.mapNotNull { slot ->
                    val text = (slot.key.action as? KeyAction.Text)?.text
                    if (text == null || text.length != 1 || !text[0].isLetter()) null
                    else text[0] to SwipeFrame(slot.left, slot.top, slot.right, slot.bottom)
                }
            )

        /**
         * The layout of [keys]: each the letter it types and its slot's bounds, in any one unit.
         * Keys that don't type a letter are left out. Null under the same conditions as the
         * geometry overload (no letters, more than [MAX_KEYS], or a letter typed twice).
         */
        fun of(keys: List<Pair<Char, SwipeFrame>>): SwipeLayout? {
            val letters = keys.filter { it.first.isLetter() }
            // Two slots typing one letter would make the encoder's key table ambiguous.
            val typed = letters.map { it.first.lowercaseChar() }
            if (letters.isEmpty() || letters.size > MAX_KEYS || typed.toSet().size != typed.size) {
                return null
            }
            val frame =
                SwipeFrame(
                    letters.minOf { it.second.left },
                    letters.minOf { it.second.top },
                    letters.maxOf { it.second.right },
                    letters.maxOf { it.second.bottom },
                )
            return SwipeLayout(
                frame,
                typed,
                letters.map { (_, box) ->
                    frame.x((box.left + box.right) / 2) to frame.y((box.top + box.bottom) / 2)
                },
            )
        }
    }
}
