package dev.keycorrect.ime

import android.content.Context
import android.util.Log
import dev.keycorrect.core.packs.PackProblem
import dev.keycorrect.core.packs.SwipePackLoader
import dev.keycorrect.core.packs.SwipePackResult
import dev.keycorrect.core.swipenet.SwipeEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The neural swipe encoder for the keyboard (docs/PHASE10.md, 10a-3). The swipe pack is opened the
 * first time a swipe asks for the encoder, on [io] (never on the main or the decoder thread), and
 * the encoder is kept for the life of the process: there is one host per process (`AppContainer`),
 * on the application scope, so recreating the keyboard service neither loads a second encoder nor
 * loses one whose load was still running. Until it has loaded, or when the pack is missing, signed
 * with another key or fails to load, [encoder] returns null and swipes decode with SHARK² alone.
 *
 * A pack that isn't installed is looked for again at most every [RETRY_NANOS], so installing it
 * takes effect without a restart. Any other failure is final for the process: the pack's library
 * may already be loaded, and native code can't be unloaded (an updated pack needs a restart, as for
 * the voice pack). Failures are logged by problem name only.
 */
class SwipeNetHost(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val enabled: () -> Boolean = { true },
    private val clock: () -> Long = System::nanoTime,
    private val open: () -> SwipePackResult,
) {
    constructor(
        context: Context,
        scope: CoroutineScope,
        io: CoroutineDispatcher,
        enabled: () -> Boolean,
    ) : this(scope, io, enabled, open = SwipePackLoader(context.applicationContext)::open)

    @Volatile private var loaded: SwipeEncoder? = null
    private val loading = AtomicBoolean(false)
    @Volatile private var settled = false
    @Volatile private var retryAt = Long.MIN_VALUE

    /**
     * The encoder if it has loaded; otherwise starts loading it (once) and returns null. While the
     * "Neural swipe" setting is off ([enabled]) this returns null without loading anything, and an
     * encoder that was already loaded is kept but not used (decoding is SHARK² alone).
     */
    fun encoder(): SwipeEncoder? {
        if (!enabled()) return null
        val ready = loaded
        if (ready == null) startLoad()
        return ready
    }

    private fun startLoad() {
        if (!settled && clock() >= retryAt && loading.compareAndSet(false, true)) {
            scope.launch(io) {
                try {
                    load()
                } finally {
                    loading.set(false)
                }
            }
        }
    }

    private fun load() {
        when (val result = runCatching(open).getOrElse { SwipePackResult.Unavailable(null) }) {
            is SwipePackResult.Available -> {
                loaded = result.encoder
                settled = true
            }
            is SwipePackResult.Unavailable -> {
                if (result.problem == PackProblem.NotInstalled) {
                    retryAt = clock() + RETRY_NANOS
                } else {
                    settled = true
                    Log.w(TAG, "Swipe pack unavailable: ${result.problem ?: "encoder"}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "SwipeNetHost"

        /** How long to wait before looking for a pack that wasn't installed: one minute. */
        const val RETRY_NANOS = 60_000_000_000L
    }
}
