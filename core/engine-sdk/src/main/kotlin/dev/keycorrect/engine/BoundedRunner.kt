package dev.keycorrect.engine

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Runs a call on a worker thread and waits for it for a limited time, so a call that hangs (a
 * plugin that never answers) can't hold the caller. At most [maxThreads] calls run at once: when
 * they are all stuck, new calls are refused at once (null) instead of piling up threads.
 */
internal class BoundedRunner(name: String, maxThreads: Int) {
    private val pool =
        ThreadPoolExecutor(
            0,
            maxThreads,
            IDLE_SECONDS,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            { task ->
                Thread(task, name).apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY
                }
            },
        )

    /**
     * The result of [block], or null when it returns null, throws, doesn't finish within
     * [timeoutMs] (it is then interrupted), or every worker is busy.
     */
    fun <T : Any> run(timeoutMs: Long, block: () -> T?): T? {
        val future =
            try {
                pool.submit(Callable { block() })
            } catch (_: RejectedExecutionException) {
                return null
            }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            null
        } catch (_: ExecutionException) {
            null
        } catch (_: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            null
        }
    }

    /** Refuses new calls and interrupts the running ones. */
    fun shutdown() {
        pool.shutdownNow()
    }

    private companion object {
        const val IDLE_SECONDS = 10L
    }
}
