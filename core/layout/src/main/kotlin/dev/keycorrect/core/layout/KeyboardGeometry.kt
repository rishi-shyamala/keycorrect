package dev.keycorrect.core.layout

/**
 * A key's slot in pixels: the full cell including half the gap on each side, so slots tile the
 * keyboard with no dead zones. The drawn key is the slot inset by half the gap.
 */
data class KeySlot(
    val index: Int,
    val key: ResolvedKey,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centerX: Float
        get() = (left + right) / 2f

    val centerY: Float
        get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
}

/** Lays out a [KeyboardLayer] in a pixel box and maps touches to keys. */
class KeyboardGeometry(val layer: KeyboardLayer, val width: Float, val height: Float) {
    /** Every key in row order, spacers included. */
    val slots: List<KeySlot>

    init {
        val unitsPerRow = layer.rows.maxOfOrNull { it.width }?.takeIf { it > 0f } ?: 1f
        val unit = width / unitsPerRow
        val totalWeight = layer.rows.sumOf { it.height.toDouble() }
        var index = 0
        var weightAbove = 0.0
        slots = buildList {
            layer.rows.forEach { row ->
                val top = (height * weightAbove / totalWeight).toFloat()
                weightAbove += row.height
                val bottom = (height * weightAbove / totalWeight).toFloat()
                var x = (width - row.width * unit) / 2f // centre short rows (e.g. "asdfghjkl")
                row.keys.forEach { key ->
                    val right = x + key.width * unit
                    add(KeySlot(index++, key, x, top, right, bottom))
                    x = right
                }
            }
        }
    }

    /**
     * Returns the key under ([x], [y]), or the nearest real key if the touch lands outside every
     * slot (e.g. the margins beside a centred row) or on a spacer. Null only if there are no keys.
     */
    fun hitTest(x: Float, y: Float): KeySlot? =
        slots.firstOrNull { !it.key.isSpacer && it.contains(x, y) }
            ?: slots
                .filter { !it.key.isSpacer }
                .minByOrNull {
                    val dx = it.centerX - x
                    val dy = it.centerY - y
                    dx * dx + dy * dy
                }
}
