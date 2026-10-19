package dev.keycorrect.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException

/** What the keyboard asks of an engine; [EngineConnection] is the one that binds a service. */
interface TextEngineLink : AutoCloseable {
    /** The engine's [info][ITextEngine.info], or null when it isn't connected or doesn't answer. */
    fun info(): Bundle?

    /** Sends [options]; false when the engine isn't connected or doesn't take them. */
    fun configure(options: Bundle): Boolean

    /**
     * The scores of [candidates] after [context], or null (not connected, no answer, bad answer).
     */
    fun score(context: String, candidates: List<String>, timeoutMs: Long): FloatArray?

    /** A continuation of [prompt], or null. */
    fun generate(prompt: String, maxTokens: Int, timeoutMs: Long): String?

    /** Counts the engines adopted so far; an engine that reconnects gets a new number. */
    val generation: Long
        get() = 0L

    /**
     * Lets calls through to the engine adopted as [generation] (see `requireAdmission` of
     * [EngineConnection]); ignored when a newer engine was adopted meanwhile.
     */
    fun admit(generation: Long) = Unit
}

/** Binds and unbinds the engine's service (a seam for tests). */
internal interface ServiceBinding {
    /** Starts binding; false when the service can't be bound (the caller then unbinds). */
    fun bind(connection: ServiceConnection): Boolean

    fun unbind(connection: ServiceConnection)
}

private class AndroidBinding(private val context: Context, private val component: ComponentName) :
    ServiceBinding {
    override fun bind(connection: ServiceConnection): Boolean =
        try {
            context.bindService(
                Intent(EngineContract.ACTION).setComponent(component),
                connection,
                // The engine does the work of the visible keyboard, so it isn't kept at foreground
                // priority beyond that; not WAIVE_PRIORITY, so it isn't the first process to go.
                Context.BIND_AUTO_CREATE or Context.BIND_NOT_FOREGROUND,
            )
        } catch (_: SecurityException) {
            false
        }

    override fun unbind(connection: ServiceConnection) {
        try {
            context.unbindService(connection)
        } catch (_: IllegalArgumentException) {
            // Not bound (a failed bind, or already unbound).
        }
    }
}

/**
 * The keyboard's connection to a text-engine service (the built-in one or a plugin): binds it and
 * offers its calls synchronously. Every call returns null at once when the engine isn't connected,
 * and never throws or waits longer than the (limited) timeout.
 *
 * **Threads:** the calls may be made from any thread except the main one (they wait for the
 * engine). Up to two calls run at once; when both are stuck, further calls give null.
 *
 * **Reconnecting:** the binding is made at construction. When the engine dies or the connection is
 * lost, the next call (not a timer, so an idle keyboard costs nothing) binds again, but not sooner
 * than [MIN_RETRY_MS] after the last try, and the wait doubles with each failure up to
 * [MAX_RETRY_MS].
 *
 * [onConnected] runs on the main thread each time the engine (re)connects, e.g. to send [configure]
 * again; hand the work to another thread.
 *
 * With [requireAdmission] every newly connected engine gets no calls (they give null) until [admit]
 * is called for it: a plugin is checked (approval, signing certificate) on each connect, because
 * this connection binds its component again by itself.
 */
@Suppress("TooManyFunctions") // The four calls plus the connection's life cycle.
class EngineConnection
internal constructor(
    private val binding: ServiceBinding,
    private val clock: () -> Long,
    private val resolve: (IBinder) -> ITextEngine?,
    private val onConnected: () -> Unit,
    private val requireAdmission: Boolean = false,
) : TextEngineLink {
    constructor(
        context: Context,
        component: ComponentName,
        onConnected: () -> Unit = {},
        requireAdmission: Boolean = false,
    ) : this(
        AndroidBinding(context.applicationContext, component),
        System::currentTimeMillis,
        { ITextEngine.Stub.asInterface(it) },
        onConnected,
        requireAdmission,
    )

    private val runner = BoundedRunner("kc-engine-client", MAX_CONCURRENT_CALLS)
    private val lock = Any()

    @Volatile private var engine: ITextEngine? = null
    @Volatile private var admitted = true
    @Volatile
    override var generation = 0L
        private set

    private var bound = false
    private var closed = false

    /** The binder [engine] came from, and the recipient linked to it; guarded by [lock]. */
    private var adoptedBinder: IBinder? = null
    private var adoptedRecipient: IBinder.DeathRecipient? = null
    private var lastAttemptMs = 0L
    private var failures = 0

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) return
                adopt(service)
            }

            // The system connects again by itself when the service restarts.
            override fun onServiceDisconnected(name: ComponentName?) = lost()

            // The binding itself is dead (the app was updated, or the service refused): bind again.
            override fun onBindingDied(name: ComponentName?) = lost()

            override fun onNullBinding(name: ComponentName?) = lost()
        }

    init {
        attemptBind()
    }

    override fun admit(generation: Long) {
        synchronized(lock) { if (generation == this.generation && engine != null) admitted = true }
    }

    /** Whether an engine is connected right now. */
    val isConnected: Boolean
        get() = engine != null

    override fun info(): Bundle? = call(EngineContract.MAX_TIMEOUT_MS) { it.info() }

    override fun configure(options: Bundle): Boolean =
        call(EngineContract.MAX_TIMEOUT_MS) {
            it.configure(options)
            true
        } == true

    override fun score(context: String, candidates: List<String>, timeoutMs: Long): FloatArray? =
        call(EngineContract.clampTimeout(timeoutMs)) {
                it.score(context, candidates.toTypedArray(), timeoutMs)
            }
            ?.takeIf { it.size == candidates.size && it.none(Float::isNaN) }

    override fun generate(prompt: String, maxTokens: Int, timeoutMs: Long): String? =
        call(EngineContract.clampTimeout(timeoutMs)) { it.generate(prompt, maxTokens, timeoutMs) }

    /** Unbinds; later calls give null. */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            unbindLocked()
        }
        lost()
        runner.shutdown()
    }

    private fun <T : Any> call(limitMs: Long, block: (ITextEngine) -> T?): T? {
        val target = current() ?: return null
        return runner.run(limitMs + GRACE_MS) {
            try {
                block(target)
            } catch (_: DeadObjectException) {
                lost(target)
                null
            } catch (_: RemoteException) {
                null
            } catch (_: RuntimeException) {
                // A plugin's failure comes across as a RuntimeException (or a subclass).
                null
            }
        }
    }

    /** The connected engine, or null; binds again first when it is time to. */
    private fun current(): ITextEngine? {
        engine?.let {
            return if (admitted) it else null
        }
        synchronized(lock) {
            if (!closed && engine == null && clock() - lastAttemptMs >= retryDelayMs(failures)) {
                failures++
                unbindLocked()
                attemptBindLocked()
            }
        }
        return engine
    }

    private fun attemptBind() = synchronized(lock) { attemptBindLocked() }

    private fun attemptBindLocked() {
        if (closed) return
        lastAttemptMs = clock()
        bound = binding.bind(connection)
        if (!bound) binding.unbind(connection)
    }

    private fun unbindLocked() {
        if (bound) binding.unbind(connection)
        bound = false
    }

    @Suppress("ReturnCount")
    private fun adopt(service: IBinder) {
        val resolved = resolve(service) ?: return
        val recipient = IBinder.DeathRecipient { binderDied(service) }
        val live =
            try {
                service.linkToDeath(recipient, 0)
                true
            } catch (_: RemoteException) {
                false
            }
        if (!live) return
        synchronized(lock) {
            if (closed) {
                service.unlinkToDeathQuietly(recipient)
                return
            }
            // Replacing an earlier engine: drop its link, so its late death notice finds nothing.
            adoptedBinder?.unlinkToDeathQuietly(adoptedRecipient)
            failures = 0
            generation++
            admitted = !requireAdmission
            adoptedBinder = service
            adoptedRecipient = recipient
            engine = resolved
        }
        onConnected()
    }

    /**
     * The death notice of [binder]. A notice for a binder that is no longer the adopted one (a
     * newer engine replaced it) must not touch the new connection.
     */
    internal fun binderDied(binder: IBinder) {
        synchronized(lock) {
            if (adoptedBinder !== binder) return
            dropLocked()
        }
    }

    /**
     * The connection is gone. With [dead] (the engine a call found dead) only when it is still the
     * adopted one; without, whichever is adopted.
     */
    private fun lost(dead: ITextEngine? = null) {
        synchronized(lock) {
            if (dead != null && engine !== dead) return
            dropLocked()
        }
    }

    private fun dropLocked() {
        engine = null
        adoptedBinder?.unlinkToDeathQuietly(adoptedRecipient)
        adoptedBinder = null
        adoptedRecipient = null
    }

    private fun IBinder.unlinkToDeathQuietly(recipient: IBinder.DeathRecipient?) {
        if (recipient == null) return
        try {
            unlinkToDeath(recipient, 0)
        } catch (_: NoSuchElementException) {
            // Already unlinked.
        }
    }

    internal companion object {
        /** The shortest wait between two tries to bind. */
        const val MIN_RETRY_MS = 5000L

        /** The longest wait between two tries to bind. */
        const val MAX_RETRY_MS = 60_000L

        private const val MAX_CONCURRENT_CALLS = 2
        private const val GRACE_MS = 150L
        private const val MAX_DOUBLINGS = 4

        /** The wait before the next try after [failures] retries that didn't connect. */
        fun retryDelayMs(failures: Int): Long =
            (MIN_RETRY_MS shl failures.coerceIn(0, MAX_DOUBLINGS)).coerceAtMost(MAX_RETRY_MS)
    }
}
