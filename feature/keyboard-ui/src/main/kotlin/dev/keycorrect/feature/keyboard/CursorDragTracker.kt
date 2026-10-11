package dev.keycorrect.feature.keyboard

import kotlin.math.abs

/** Whole steps to move since the last report: [dx] characters, [dy] lines. */
internal data class DragSteps(val dx: Int, val dy: Int)

/**
 * Turns a spacebar drag into cursor steps.
 *
 * - Horizontal: finger travel is accumulated in steps of [stepPx]. A drag faster than
 *   [ACCEL_FREE_RATE] steps a second is accelerated: the gain is `1 + (rate - FREE) / RAMP`, capped
 *   at [ACCEL_MAX], and applies to that move's travel only, so slowing down never loses steps.
 * - Vertical: one line per [lineStepPx] of travel, never accelerated.
 * - Both are signed running sums truncated toward zero, so dragging back undoes steps.
 */
internal class CursorDragTracker(
    private val stepPx: Float,
    private val lineStepPx: Float,
    private var lastX: Float,
    private var lastY: Float,
    private var lastTime: Long,
) {
    private var sumX = 0f
    private var sumY = 0f
    private var emittedX = 0
    private var emittedY = 0

    /** The new whole steps since the previous call, or null if the step counts did not change. */
    fun move(x: Float, y: Float, now: Long): DragSteps? {
        val dxSteps = (x - lastX) / stepPx
        val dt = now - lastTime
        sumX += dxSteps * gain(abs(dxSteps), dt)
        sumY += (y - lastY) / lineStepPx
        lastX = x
        lastY = y
        lastTime = now
        val tx = sumX.toInt()
        val ty = sumY.toInt()
        if (tx == emittedX && ty == emittedY) return null
        val steps = DragSteps(tx - emittedX, ty - emittedY)
        emittedX = tx
        emittedY = ty
        return steps
    }

    private fun gain(absSteps: Float, dtMs: Long): Float {
        if (dtMs <= 0L) return 1f
        val rate = absSteps * MS_PER_SECOND / dtMs
        return (1f + (rate - ACCEL_FREE_RATE) / ACCEL_RAMP).coerceIn(1f, ACCEL_MAX)
    }

    private companion object {
        const val MS_PER_SECOND = 1000f

        /** Steps a second (before gain) up to which a drag is not accelerated. */
        const val ACCEL_FREE_RATE = 10f

        /** Each further [ACCEL_RAMP] steps a second adds 1 to the gain. */
        const val ACCEL_RAMP = 20f
        const val ACCEL_MAX = 4f
    }
}
