package dev.keycorrect.neural

import dev.keycorrect.engine.EngineContract
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** The neural pack's default model, opened for reading in place. */
internal sealed interface DefaultModel {
    /** [fd] reads from byte [offset]; [closer] releases it once the model is open. */
    class Found(val fd: Int, val offset: Long, val closer: AutoCloseable) : DefaultModel

    /** [problem] is the pack problem's name; [needsRestart] when only a new process can help. */
    class Missing(val problem: String, val needsRestart: Boolean = false) : DefaultModel
}

/** Finds the default model (a seam for tests; the app reads it from the signed neural pack). */
internal fun interface DefaultModelSource {
    fun open(): DefaultModel
}

/**
 * The state and rules of the built-in engine, apart from Android: which model is configured, the
 * background load, the status, and serialized use of the one open model.
 *
 * One model is open at a time. [configure] returns at once; the load runs on [executor] (one
 * thread), and until it ends [score] and [generate] give null. A configure that changes nothing
 * about a loaded or loading model is ignored, so the keyboard may send it on every connect. Models
 * are only taken from inside [filesRoot]. Nothing here handles or logs user text.
 */
internal class NeuralEngineCore(
    private val filesRoot: File,
    private val runtime: () -> ModelRuntime,
    private val defaultModel: DefaultModelSource,
    private val executor: Executor,
    private val restartProcess: () -> Unit = {},
) {
    private sealed interface State {
        data object NoModel : State

        data object Loading : State

        class Ready(val model: LoadedModel, val description: String) : State

        class Failed(val reason: String) : State
    }

    /** What is loaded or loading: [path] is null for the pack's default. */
    private data class Key(val path: String?, val threads: Int)

    /** Guards [state], [key] and [epoch]; held only briefly. */
    private val stateLock = Any()

    /** Held while the open model is used, replaced or closed; never taken under [stateLock]. */
    private val modelLock = ReentrantLock()

    @Volatile private var state: State = State.NoModel
    private var key: Key? = null
    private var epoch = 0L

    /** The status for [EngineContract.INFO_STATUS]. */
    val status: String
        get() =
            when (val s = state) {
                State.NoModel -> EngineContract.STATUS_NO_MODEL
                State.Loading -> EngineContract.STATUS_LOADING
                is State.Ready -> EngineContract.STATUS_READY
                is State.Failed -> EngineContract.errorStatus(s.reason)
            }

    /** The loaded model's description, or null when none is ready. */
    val modelDescription: String?
        get() = (state as? State.Ready)?.description

    /**
     * Loads the model at [modelPath] (inside [filesRoot]) or, when it is null or empty and
     * [useDefault] is set, the pack's default; with neither, the current model is released.
     * [threads] <= 0 means the default.
     */
    fun configure(modelPath: String?, useDefault: Boolean, threads: Int) {
        val count = if (threads > 0) threads.coerceAtMost(MAX_THREADS) else defaultThreads()
        val wanted: Key? =
            when {
                !modelPath.isNullOrEmpty() -> Key(modelPath, count)
                useDefault -> Key(null, count)
                else -> null
            }
        val old: LoadedModel?
        val job: Long
        val toLoad: Key?
        synchronized(stateLock) {
            val current = state
            if (wanted != null && wanted == key && (current is State.Ready || current === State.Loading)) {
                return
            }
            job = ++epoch
            old = (current as? State.Ready)?.model
            key = wanted
            val problem = wanted?.path?.let(::pathProblem)
            toLoad = wanted?.takeIf { problem == null }
            state =
                when {
                    wanted == null -> State.NoModel
                    problem != null -> State.Failed(problem)
                    else -> State.Loading
                }
        }
        if (old != null || toLoad != null) {
            executor.execute {
                old?.let(::closeModel)
                if (toLoad != null) load(job, toLoad)
            }
        }
    }

    /** Scores [candidates] after [context]; null unless a model is ready and answers in time. */
    fun score(context: String, candidates: Array<String>, timeoutMs: Long): FloatArray? =
        withModel(timeoutMs) { model, remaining -> model.score(context, candidates, remaining) }

    /** Continues [prompt]; null unless a model is ready and answers in time. */
    fun generate(prompt: String, maxTokens: Int, timeoutMs: Long): String? =
        withModel(timeoutMs) { model, remaining -> model.generate(prompt, maxTokens, remaining) }

    /** Closes the model and goes to "no model" (memory pressure); a later [configure] reloads it. */
    fun release() {
        val old: LoadedModel?
        synchronized(stateLock) {
            epoch++
            key = null
            old = (state as? State.Ready)?.model
            state = State.NoModel
        }
        old?.let { executor.execute { closeModel(it) } }
    }

    private fun <T : Any> withModel(timeoutMs: Long, use: (LoadedModel, Long) -> T?): T? {
        val ready = state as? State.Ready ?: return null
        val started = System.nanoTime()
        val locked =
            try {
                modelLock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        if (!locked) return null
        try {
            // Released or replaced while this call waited: that model may be closed.
            if (state !== ready) return null
            val remaining = timeoutMs - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            return if (remaining > 0) use(ready.model, remaining) else null
        } finally {
            modelLock.unlock()
        }
    }

    private fun closeModel(model: LoadedModel) {
        modelLock.lock()
        try {
            model.close()
        } catch (_: Exception) {
            // Nothing more to do with a model that fails to close.
        } finally {
            modelLock.unlock()
        }
    }

    private fun load(job: Long, wanted: Key) {
        val outcome = open(wanted)
        val published: State
        val superseded: Boolean
        synchronized(stateLock) {
            superseded = job != epoch
            published = if (superseded) state else outcome.toState()
            if (!superseded) state = published
        }
        if (superseded) (outcome as? Opened)?.let { closeModel(it.model) }
        if (!superseded && outcome is Missing && outcome.needsRestart) restartProcess()
    }

    private sealed interface Outcome {
        fun toState(): State
    }

    private class Opened(val model: LoadedModel) : Outcome {
        override fun toState() = State.Ready(model, describe(model))
    }

    private class Missing(val reason: String, val needsRestart: Boolean = false) : Outcome {
        override fun toState() = State.Failed(reason)
    }

    @Suppress("TooGenericExceptionCaught") // A runtime may fail in any way; the answer is the same.
    private fun open(wanted: Key): Outcome =
        try {
            val rt = runtime()
            val path = wanted.path
            if (path != null) {
                rt.openFile(File(path).canonicalPath, wanted.threads)?.let(::Opened)
                    ?: Missing(LOAD_FAILED)
            } else {
                openDefault(rt, wanted.threads)
            }
        } catch (_: OutOfMemoryError) {
            Missing(OUT_OF_MEMORY)
        } catch (_: LinkageError) {
            Missing(LOAD_FAILED)
        } catch (_: Exception) {
            Missing(LOAD_FAILED)
        }

    private fun openDefault(rt: ModelRuntime, threads: Int): Outcome =
        when (val found = defaultModel.open()) {
            is DefaultModel.Missing -> Missing(found.problem, found.needsRestart)
            is DefaultModel.Found ->
                try {
                    rt.openFd(found.fd, found.offset, threads)?.let(::Opened) ?: Missing(LOAD_FAILED)
                } finally {
                    try {
                        found.closer.close()
                    } catch (_: IOException) {
                        // The model is open (or failed) either way.
                    }
                }
        }

    /** Why [path] can't be used, or null: it must be a file inside [filesRoot]. */
    private fun pathProblem(path: String): String? {
        val file = File(path)
        if (!file.isAbsolute) return PATH_REJECTED
        val canonical =
            try {
                file.canonicalFile
            } catch (_: IOException) {
                return PATH_REJECTED
            }
        val root =
            try {
                filesRoot.canonicalFile
            } catch (_: IOException) {
                return PATH_REJECTED
            }
        return when {
            !canonical.path.startsWith(root.path + File.separator) -> PATH_REJECTED
            !canonical.isFile -> FILE_MISSING
            else -> null
        }
    }

    private companion object {
        const val MAX_THREADS = 8
        const val DEFAULT_MAX_THREADS = 4

        /** The reason codes of error statuses (besides the pack problems' names). */
        const val PATH_REJECTED = "PathRejected"
        const val FILE_MISSING = "FileMissing"
        const val LOAD_FAILED = "LoadFailed"
        const val OUT_OF_MEMORY = "OutOfMemory"

        fun defaultThreads() = Runtime.getRuntime().availableProcessors().coerceIn(1, DEFAULT_MAX_THREADS)

        @Suppress("TooGenericExceptionCaught")
        fun describe(model: LoadedModel): String =
            try {
                model.describe()
            } catch (_: Exception) {
                ""
            }
    }
}
