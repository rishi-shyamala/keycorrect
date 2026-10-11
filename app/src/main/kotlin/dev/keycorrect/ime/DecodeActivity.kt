package dev.keycorrect.ime

import java.util.concurrent.atomic.AtomicInteger

/**
 * Tells the learning thread whether the decoder is (or just was) busy with a typing decode, so it
 * can keep its own use of the engine out of the typing path. `ClassicEngine.predictNow` and
 * `featuresFor` share one lock, so a learning-side decode makes a keystroke wait for at most one
 * decode; this keeps even that from happening while the user is typing.
 *
 * Wrap every typing decode in [track]. [busy] is true during a decode and for [quietNanos] after
 * the last one.
 */
internal class DecodeActivity(
    private val nanoClock: () -> Long = System::nanoTime,
    private val quietNanos: Long = QUIET_NANOS,
) {
    private val inFlight = AtomicInteger(0)
    @Volatile private var lastEnd = Long.MIN_VALUE / 2

    inline fun <T> track(block: () -> T): T {
        begin()
        try {
            return block()
        } finally {
            end()
        }
    }

    fun begin() {
        inFlight.incrementAndGet()
    }

    fun end() {
        lastEnd = nanoClock()
        inFlight.decrementAndGet()
    }

    fun busy(): Boolean = inFlight.get() > 0 || nanoClock() - lastEnd < quietNanos

    companion object {
        const val QUIET_NANOS = 300_000_000L
    }
}

/**
 * Caps how many engine decodes the learning thread may start per time window. The model retries
 * preferences it could not featurise on every update, and each attempt is a full decode; the budget
 * bounds that work however many are pending. Used from the learning thread only.
 */
internal class DecodeBudget(
    private val maxCalls: Int = MAX_CALLS,
    private val windowNanos: Long = WINDOW_NANOS,
    private val nanoClock: () -> Long = System::nanoTime,
) {
    private var windowStart = Long.MIN_VALUE / 2
    private var used = 0

    fun tryAcquire(): Boolean {
        val now = nanoClock()
        if (now - windowStart >= windowNanos) {
            windowStart = now
            used = 0
        }
        if (used >= maxCalls) return false
        used++
        return true
    }

    companion object {
        const val MAX_CALLS = 24
        const val WINDOW_NANOS = 1_000_000_000L
    }
}
