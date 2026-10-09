package dev.keycorrect.feature.keyboard

/** Handle for a scheduled timeout. */
internal fun interface TimeoutHandle {
    fun cancel()
}

/** Runs [action] once after [delayMs] on the thread the coordinator is used from. */
internal fun interface TimeoutScheduler {
    fun schedule(delayMs: Long, action: Runnable): TimeoutHandle
}

/**
 * Collects asynchronously inflated items and publishes them as one list, so the suggestion strip
 * never shows a half-built set that then reshuffles as more views arrive.
 *
 * Each [begin] starts a new batch of `size` slots (one per suggestion, in the platform's order) and
 * invalidates the previous one. A batch publishes once every slot has resolved (an item or `null`
 * for a failed inflate, which is skipped). If some slots are still pending, [timeoutMs] after the
 * first item arrives the batch publishes what is ready, so one slow inflate cannot hold the strip
 * back. Items that arrive after that are added and the list is published again, so a slow chip
 * still shows up eventually. Callbacks from superseded or cancelled batches are ignored.
 *
 * Single-threaded: use from the main thread only.
 */
internal class InflateCoordinator<T : Any>(
    private val timeoutMs: Long,
    private val scheduler: TimeoutScheduler,
    private val publish: (List<T>) -> Unit,
) {
    private var current: Batch? = null

    /** Starts a new batch of [size] slots and drops the previous one. Publishes nothing itself. */
    fun begin(size: Int): Batch {
        cancel()
        return Batch(size).also { current = it }
    }

    /** Drops the current batch and its pending timeout; later callbacks for it are ignored. */
    fun cancel() {
        current?.timeout?.cancel()
        current = null
    }

    inner class Batch internal constructor(size: Int) {
        private val slots = arrayOfNulls<Any>(size)
        private val resolved = BooleanArray(size)
        private var resolvedCount = 0
        internal var timeout: TimeoutHandle? = null
        private var published = false

        /** Resolves slot [index] with [item], or `null` if inflation failed. First call wins. */
        fun resolve(index: Int, item: T?) {
            if (current !== this || resolved[index]) return
            resolved[index] = true
            resolvedCount++
            slots[index] = item
            when {
                resolvedCount == resolved.size -> {
                    timeout?.cancel()
                    timeout = null
                    if (!published || item != null) publishReady()
                }
                published -> if (item != null) publishReady()
                item != null && timeout == null ->
                    timeout = scheduler.schedule(timeoutMs) { onTimeout() }
            }
        }

        private fun onTimeout() {
            if (current !== this || published) return
            timeout = null
            publishReady()
        }

        private fun publishReady() {
            published = true
            @Suppress("UNCHECKED_CAST") publish(slots.filterNotNull() as List<T>)
        }
    }
}
