package dev.keycorrect.feature.keyboard

/**
 * A key's slot in pixels: the full cell including half the gap on each side, so slots tile the
 * keyboard with no dead zones. The drawn key is the slot inset by half the gap.
 */
data class KeySlot(
    val index: Int,
    val key: Key,
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
    val slots: List<KeySlot>

    init {
        val unitsPerRow = layer.rows.maxOf { row -> row.sumOf { it.width.toDouble() } }.toFloat()
        val unit = width / unitsPerRow
        val rowHeight = height / layer.rows.size
        var index = 0
        slots = buildList {
            layer.rows.forEachIndexed { rowIndex, row ->
                val rowWidth = row.sumOf { it.width.toDouble() }.toFloat() * unit
                var x = (width - rowWidth) / 2f // centre short rows (e.g. "asdfghjkl")
                val top = rowIndex * rowHeight
                row.forEach { key ->
                    val right = x + key.width * unit
                    add(KeySlot(index++, key, x, top, right, top + rowHeight))
                    x = right
                }
            }
        }
    }

    /**
     * Returns the key under ([x], [y]), or the nearest key if the touch lands outside every slot
     * (e.g. the margins beside a centred row). Null only for an empty layer.
     */
    fun hitTest(x: Float, y: Float): KeySlot? =
        slots.firstOrNull { it.contains(x, y) }
            ?: slots.minByOrNull {
                val dx = it.centerX - x
                val dy = it.centerY - y
                dx * dx + dy * dy
            }
}
