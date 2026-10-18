package dev.keycorrect.feature.keyboard

import dev.keycorrect.core.engineapi.GestureInput
import dev.keycorrect.core.engineapi.TouchPoint

/**
 * The points of the swipe in progress (docs/PHASE5.md), in keyboard pixel coordinates. The touch
 * layer appends on the main thread; readers on the main thread (the trail, the controller) see it
 * grow. Primitive arrays, so a swipe allocates only when it outgrows the capacity. [toGestureInput]
 * copies the points for the decoder thread.
 */
class SwipePath(initialCapacity: Int = 256) {
    private var xs = FloatArray(initialCapacity)
    private var ys = FloatArray(initialCapacity)
    private var ts = LongArray(initialCapacity)

    /** How many points the path has. */
    var size: Int = 0
        private set

    fun x(i: Int): Float = xs[i]

    fun y(i: Int): Float = ys[i]

    fun time(i: Int): Long = ts[i]

    fun add(x: Float, y: Float, timeMillis: Long) {
        if (size == xs.size) {
            val grown = size * 2
            xs = xs.copyOf(grown)
            ys = ys.copyOf(grown)
            ts = ts.copyOf(grown)
        }
        xs[size] = x
        ys[size] = y
        ts[size] = timeMillis
        size++
    }

    fun clear() {
        size = 0
    }

    /** A copy for the engine; null below two points. */
    fun toGestureInput(): GestureInput? =
        if (size < 2) null else GestureInput(List(size) { TouchPoint(xs[it], ys[it], ts[it]) })
}
