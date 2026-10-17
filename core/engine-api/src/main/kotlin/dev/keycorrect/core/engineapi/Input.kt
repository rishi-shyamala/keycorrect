package dev.keycorrect.core.engineapi

/** One touch, in keyboard pixel coordinates. */
data class TouchPoint(val x: Float, val y: Float, val timeMillis: Long)

/**
 * The word being composed. [touches] has one entry per char of [text]; an entry is null when the
 * char didn't come from a single tap (long-press popup, paste, shifted key repeat).
 */
data class ComposingInput(val text: String, val touches: List<TouchPoint?>) {
    init {
        require(touches.size == text.length) { "touches must align with text" }
    }

    val isEmpty: Boolean
        get() = text.isEmpty()

    companion object {
        val Empty = ComposingInput("", emptyList())
    }
}

/**
 * A swipe: the finger's path from down to up, in drawing order, in the same pixel space as
 * [KeySpec]s. Points are as sampled by the touch system (not resampled).
 */
data class GestureInput(val points: List<TouchPoint>) {
    init {
        require(points.size >= 2) { "a gesture needs at least two points" }
    }
}

/** A character key's position, so engines can build a spatial (touch) model. */
data class KeySpec(
    val char: Char,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
)

/**
 * The character keys of the layout currently shown, in the same pixel space as [TouchPoint]s. [id]
 * changes whenever geometry changes, so engines can cache derived models per layout.
 */
data class KeyLayoutModel(val id: Long, val keys: List<KeySpec>)
