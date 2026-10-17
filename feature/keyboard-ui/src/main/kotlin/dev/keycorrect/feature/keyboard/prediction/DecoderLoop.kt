package dev.keycorrect.feature.keyboard.prediction

import android.util.Log
import dev.keycorrect.core.engineapi.GestureRequest
import dev.keycorrect.core.engineapi.KeyLayoutModel
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.PredictResult
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs predictions off the main thread and drops the ones that arrive late (docs/PLAN.md §3).
 *
 * Every input event calls [advance], which bumps the [sequence]; a request carries the sequence it
 * was built for, and a result is delivered only if that is still the current one. The sequence is
 * also what the predictor's `stale` lambda checks, so a newer keystroke makes the running decode
 * give up early instead of making the user wait for it.
 *
 * Separator policy ([resultForSeparator]): the main thread must never queue up behind the engine
 * lock that a background decode holds. The decoder thread publishes every finished result in
 * [completed] before posting it to the main thread, so at a separator the result for the current
 * sequence is usually already there, even if its delivery hasn't run yet. If it isn't:
 * - a decode is still outstanding (running, or queued behind one): wait at most [separatorWaitMs]
 *   (30 ms by default) for the result for the current sequence, or for the decoder to go idle;
 * - then, if the result arrived, use it; if the decoder is idle, decode on the calling thread
 *   (nothing holds the engine, and only the main thread submits, so nothing starts meanwhile); if
 *   it is still busy, give up: the caller commits the typed word as it is, without autocorrect.
 *
 * [executor] must run tasks one at a time, in order (a single-thread executor); [postToMain] hands
 * a result back to the main thread, where [submit]'s callback runs. Failures inside the predictor
 * are logged by type only (messages could carry typed text) and produce no result.
 */
class DecoderLoop(
    private val executor: Executor,
    private val postToMain: (Runnable) -> Unit,
    private val separatorWaitMs: Long = SEPARATOR_WAIT_MS,
    /** How long the main thread waits for the final decode of a swipe on lift. */
    val gestureWaitMs: Long = GESTURE_WAIT_MS,
    private val predictor: () -> Predictor?,
) {
    private val counter = AtomicLong()

    /** The latest finished result of the decoder thread, whether or not it was delivered yet. */
    private val completed = AtomicReference<PredictResult?>(null)

    /** Submitted decodes not yet finished or skipped. */
    private val outstanding = AtomicInteger()

    private val monitor = Object()

    /** Decode durations, for tuning. Holds numbers only. */
    val latency = LatencyStats()

    /** Swipe decode durations (previews and the final decode on lift). */
    val gestureLatency = LatencyStats()

    /** The sequence of the latest input event. */
    val sequence: Long
        get() = counter.get()

    /** Starts a new input event: everything built for an earlier sequence is stale from now on. */
    fun advance(): Long = counter.incrementAndGet()

    /**
     * Decodes [request] on the decoder thread and delivers the result to [onResult] on the main
     * thread, unless a newer event arrived first. Does nothing without a predictor.
     */
    fun submit(request: PredictRequest, onResult: (PredictResult) -> Unit) {
        if (predictor() == null) return
        val seq = request.sequence
        outstanding.incrementAndGet()
        try {
            executor.execute {
                try {
                    if (counter.get() != seq) return@execute
                    val result = decode(request) ?: return@execute
                    completed.set(result)
                    if (counter.get() != seq) return@execute
                    postToMain { onResult(result) }
                } finally {
                    outstanding.decrementAndGet()
                    synchronized(monitor) { monitor.notifyAll() }
                }
            }
        } catch (e: RuntimeException) {
            outstanding.decrementAndGet()
            throw e
        }
    }

    /**
     * Decodes the swipe preview [request] on the decoder thread and delivers the result to
     * [onResult] on the main thread, unless a newer event (the next preview, or anything else)
     * arrived first; a decode that has not started yet is skipped and a running one gives up early.
     * Does nothing without a predictor.
     */
    fun submitGesture(request: GestureRequest, onResult: (PredictResult) -> Unit) {
        if (predictor() == null) return
        val seq = request.sequence
        outstanding.incrementAndGet()
        try {
            executor.execute {
                try {
                    if (counter.get() != seq) return@execute
                    val result = decodeGesture(request) ?: return@execute
                    if (counter.get() != seq) return@execute
                    postToMain { onResult(result) }
                } finally {
                    outstanding.decrementAndGet()
                    synchronized(monitor) { monitor.notifyAll() }
                }
            }
        } catch (e: RuntimeException) {
            outstanding.decrementAndGet()
            throw e
        }
    }

    /**
     * The final decode when the finger lifts, on the decoder thread: the main thread never touches
     * the engine lock (or builds the swipe index). The caller has advanced the sequence, so an
     * outstanding preview gives up early. Returns a handle to wait on ([FinalGesture.await], at
     * most [gestureWaitMs]) or to take the answer late ([FinalGesture.whenDone]); null without a
     * predictor. The result is only ever one decoded from [request], the complete path.
     */
    fun submitFinalGesture(request: GestureRequest): FinalGesture? {
        if (predictor() == null) return null
        val seq = request.sequence
        val pending = FinalGesture(seq)
        outstanding.incrementAndGet()
        try {
            executor.execute {
                try {
                    val result = if (counter.get() == seq) decodeGesture(request) else null
                    pending.complete(result) { cb, r ->
                        if (counter.get() == seq) postToMain { cb(r) }
                    }
                } finally {
                    outstanding.decrementAndGet()
                    synchronized(monitor) { monitor.notifyAll() }
                }
            }
        } catch (e: RuntimeException) {
            outstanding.decrementAndGet()
            Log.w(TAG, "Gesture decode rejected: ${e.javaClass.name}")
            pending.complete(null) { _, _ -> }
        }
        return pending
    }

    /** Builds the swipe index of [layout] on the decoder thread, ahead of the first swipe. */
    fun warmUpGestures(layout: KeyLayoutModel) {
        if (predictor() == null) return
        try {
            executor.execute {
                try {
                    predictor()?.warmUpGestures(layout)
                } catch (e: Exception) {
                    Log.w(TAG, "Gesture warm-up failed: ${e.javaClass.name}")
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Gesture warm-up rejected: ${e.javaClass.name}")
        }
    }

    /**
     * The answer for the input event [sequence] at a separator, under the policy in the class
     * comment: never blocks on the engine lock, waits at most [separatorWaitMs]. [request] builds
     * the request lazily, only when a synchronous decode turns out to be safe. Null means "no
     * answer in time" (or no predictor, or a failure).
     */
    fun resultForSeparator(sequence: Long, request: () -> PredictRequest): PredictResult? {
        current(sequence)?.let {
            return it
        }
        if (predictor() == null) return null
        if (outstanding.get() > 0) {
            val deadline = System.nanoTime() + separatorWaitMs * 1_000_000
            try {
                synchronized(monitor) {
                    while (outstanding.get() > 0 && current(sequence) == null) {
                        val remaining = deadline - System.nanoTime()
                        if (remaining <= 0) break
                        monitor.wait(remaining / 1_000_000, (remaining % 1_000_000).toInt())
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null // no answer: the caller commits the word as typed
            }
            current(sequence)?.let {
                return it
            }
            if (outstanding.get() > 0) return null
        }
        return decode(request())
    }

    private fun current(sequence: Long): PredictResult? =
        completed.get()?.takeIf { it.sequence == sequence }

    /**
     * Decodes [request] on the calling thread. Only for callers that know no background decode can
     * hold the engine ([resultForSeparator] checks that); returns null without a predictor or when
     * the predictor fails.
     */
    fun predictNow(request: PredictRequest): PredictResult? = decode(request)

    private fun decode(request: PredictRequest): PredictResult? {
        val p = predictor() ?: return null
        val seq = request.sequence
        val start = System.nanoTime()
        val result =
            try {
                p.predict(request) { counter.get() != seq }
                    .let {
                        if (it.sequence == seq) it else it.copy(sequence = seq)
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Prediction failed: ${e.javaClass.name}")
                return null
            }
        latency.record((System.nanoTime() - start) / 1_000)
        return result
    }

    @Suppress("ReturnCount") // no predictor / failure exits
    private fun decodeGesture(request: GestureRequest): PredictResult? {
        val p = predictor() ?: return null
        val seq = request.sequence
        val start = System.nanoTime()
        val result =
            try {
                p.predictGesture(request) { counter.get() != seq }
                    .let { if (it.sequence == seq) it else it.copy(sequence = seq) }
            } catch (e: Exception) {
                Log.w(TAG, "Gesture decode failed: ${e.javaClass.name}")
                return null
            }
        gestureLatency.record((System.nanoTime() - start) / 1_000)
        return result
    }

    companion object {
        /** How long a separator may wait for an outstanding decode. */
        const val SEPARATOR_WAIT_MS = 30L

        /**
         * How long the main thread waits for a swipe's final decode (on lift, and again at a key).
         */
        const val GESTURE_WAIT_MS = 50L
        private const val TAG = "DecoderLoop"
    }
}

private const val NANOS_PER_MS = 1_000_000L

/**
 * The final decode of a swipe in flight (see [DecoderLoop.submitFinalGesture]). The main thread
 * waits for it a little ([await]); if it is late it either registers to be told ([whenDone], the
 * callback runs on the main thread, and only if no other input happened meanwhile) or drops it
 * ([cancel]).
 */
class FinalGesture
internal constructor(
    /** The decoder sequence of the request: the answer is only good while it is current. */
    val sequence: Long
) {
    private val lock = Object()
    private var done = false
    private var answer: PredictResult? = null
    private var callback: ((PredictResult) -> Unit)? = null

    /** The decoded result once finished; null if it failed or has not finished. */
    val result: PredictResult?
        get() = synchronized(lock) { answer }

    /** Waits at most [waitMs] for the decode; true if it has finished (see [result]). */
    fun await(waitMs: Long): Boolean {
        val deadline = System.nanoTime() + waitMs * NANOS_PER_MS
        synchronized(lock) {
            try {
                while (!done) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) break
                    lock.wait(remaining / NANOS_PER_MS, (remaining % NANOS_PER_MS).toInt())
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return done
        }
    }

    /**
     * Asks to be told on the main thread when the decode finishes with a result. False, with no
     * callback registered, if it has finished already (read [result]).
     */
    fun whenDone(onResult: (PredictResult) -> Unit): Boolean =
        synchronized(lock) {
            if (done) return false
            callback = onResult
            true
        }

    /** Nobody wants the answer any more. */
    fun cancel() = synchronized(lock) { callback = null }

    internal fun complete(
        result: PredictResult?,
        deliver: (((PredictResult) -> Unit), PredictResult) -> Unit,
    ) {
        val cb: ((PredictResult) -> Unit)?
        synchronized(lock) {
            answer = result
            done = true
            cb = callback
            callback = null
            lock.notifyAll()
        }
        if (cb != null && result != null) deliver(cb, result)
    }
}

/**
 * Rolling window of decode durations in microseconds, readable from any thread. Logs a one-line
 * summary of numbers every [LOG_EVERY] samples at debug level.
 */
class LatencyStats {
    private val samples = LongArray(WINDOW)
    private var count = 0L

    @Synchronized
    fun record(micros: Long) {
        samples[(count % WINDOW).toInt()] = micros
        count++
        if (count % LOG_EVERY == 0L) {
            Log.d(
                TAG,
                "decode p50=${percentileLocked(0.5)}us p95=${percentileLocked(0.95)}us n=$count",
            )
        }
    }

    /** Number of decodes recorded so far. */
    @get:Synchronized
    val total: Long
        get() = count

    /** The [fraction] percentile (0..1) of the recent window in microseconds, 0 if empty. */
    @Synchronized fun percentile(fraction: Double): Long = percentileLocked(fraction)

    private fun percentileLocked(fraction: Double): Long {
        val n = minOf(count, WINDOW.toLong()).toInt()
        if (n == 0) return 0
        val sorted = samples.copyOf(n).also { it.sort() }
        return sorted[((n - 1) * fraction).toInt().coerceIn(0, n - 1)]
    }

    private companion object {
        const val TAG = "DecoderLatency"
        const val WINDOW = 256
        const val LOG_EVERY = 200
    }
}
