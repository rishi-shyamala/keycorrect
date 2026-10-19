package dev.keycorrect.core.llama

/** Loading or creating something in the native library failed. */
class LlamaException(message: String) : RuntimeException(message)

/**
 * A loaded language model (the weights). Open one with [openFile] or [openFd], then make a
 * [LlamaSession] per thread that will score text. The model owns its sessions: [close] closes the
 * ones still open before it frees the weights. Opening, [newSession] and [close] may be called from
 * any thread; the sessions themselves are not thread-safe.
 */
class LlamaModel private constructor(private val backend: LlamaBackend, private var handle: Long) :
    AutoCloseable {
    private val sessions = LinkedHashSet<LlamaSession>()

    /** Architecture, parameter count and vocabulary size, for the settings screen. */
    val description: String
        get() = backend.describe(requireOpen())

    /**
     * A session with its own KV cache of [nCtx] tokens (the context is capped to the last half), on
     * [nThreads] threads.
     */
    @Synchronized
    fun newSession(nCtx: Int = DEFAULT_CTX, nThreads: Int = DEFAULT_THREADS): LlamaSession {
        require(nCtx >= MIN_CTX) { "nCtx must be at least $MIN_CTX" }
        require(nThreads >= 1) { "nThreads must be at least 1" }
        val ctx = backend.newContext(requireOpen(), nCtx, nThreads)
        if (ctx == 0L) throw LlamaException("could not create a llama context")
        return LlamaSession(backend, ctx, this).also { sessions.add(it) }
    }

    /** True once [close] has been called. */
    @get:Synchronized
    val isClosed: Boolean
        get() = handle == 0L

    /** Closes the open sessions, then frees the model. Safe to call twice. */
    @Synchronized
    override fun close() {
        if (handle == 0L) return
        sessions.toList().forEach { it.close() }
        backend.freeModel(handle)
        handle = 0L
    }

    internal fun forget(session: LlamaSession) {
        synchronized(this) { sessions.remove(session) }
    }

    private fun requireOpen(): Long {
        check(handle != 0L) { "LlamaModel is closed" }
        return handle
    }

    companion object {
        /** Default KV cache size of a session, in tokens. */
        const val DEFAULT_CTX = 512

        /** Default thread count of a session. */
        const val DEFAULT_THREADS = 4

        private const val MIN_CTX = 16

        /** llama.cpp can map a file region only when the tensor data starts 32-byte aligned. */
        const val MMAP_ALIGNMENT = 32L

        /** True when a model that starts at [offset] in its file may be memory-mapped. */
        fun canMmap(offset: Long): Boolean = offset >= 0 && offset % MMAP_ALIGNMENT == 0L

        /**
         * Loads the GGUF at [path], memory-mapped. Throws [LlamaException] if it cannot be read.
         */
        fun openFile(path: String): LlamaModel = openFile(LlamaNative, path)

        /**
         * Loads a GGUF that starts at [offset] in the open file [fd] (an uncompressed asset of the
         * pack). The model is memory-mapped only when [canMmap] says so; otherwise it is read into
         * memory. The caller keeps ownership of [fd].
         */
        fun openFd(fd: Int, offset: Long = 0L): LlamaModel = openFd(LlamaNative, fd, offset)

        internal fun openFile(backend: LlamaBackend, path: String): LlamaModel =
            wrap(backend, backend.loadModelFile(path, useMmap = true))

        internal fun openFd(backend: LlamaBackend, fd: Int, offset: Long): LlamaModel =
            wrap(backend, backend.loadModelFd(fd, offset, useMmap = canMmap(offset)))

        private fun wrap(backend: LlamaBackend, handle: Long): LlamaModel {
            if (handle == 0L) throw LlamaException("could not load the model")
            return LlamaModel(backend, handle)
        }
    }
}

/**
 * One llama context: a KV cache plus the scratch the scorer needs. **Not thread-safe**: use it from
 * one thread at a time (the keyboard keeps one session on its scorer thread) and never call [close]
 * while another thread is inside [score] or [generate]. After [close], every call throws
 * [IllegalStateException].
 *
 * The session remembers the tokens of the last context it scored, so a call whose context extends
 * the previous one (the usual case while typing) decodes only the new tokens.
 */
class LlamaSession
internal constructor(
    private val backend: LlamaBackend,
    private var handle: Long,
    private val owner: LlamaModel,
) : AutoCloseable {
    /** True once [close] has been called. */
    val isClosed: Boolean
        get() = handle == 0L

    /**
     * For each candidate, the sum of log P over the tokens of `" " + candidate` given [context]
     * (empty = start of text), or null when [timeoutMs] runs out or decoding fails. A timeout of 0
     * expires at once; a negative one never expires. Candidates are scored in batches, in order.
     */
    fun score(context: String, candidates: List<String>, timeoutMs: Long): FloatArray? {
        val ctx = requireOpen()
        if (candidates.isEmpty()) return FloatArray(0)
        return backend.score(ctx, context, candidates.toTypedArray(), timeoutMs)
    }

    /**
     * Greedy continuation of [prompt]: at most [maxTokens] tokens, stopping at a newline (not
     * included) or after the first token with `. ! ? ,`. Returns null on timeout or error. The
     * score cache survives unless the cache is too full for the prompt, in which case the next
     * [score] decodes its context again.
     */
    fun generate(prompt: String, maxTokens: Int, timeoutMs: Long): String? {
        val ctx = requireOpen()
        require(maxTokens > 0) { "maxTokens must be positive" }
        return backend.generate(ctx, prompt, maxTokens, timeoutMs)
    }

    /** Frees the context. Safe to call twice. */
    override fun close() {
        if (handle == 0L) return
        backend.freeContext(handle)
        handle = 0L
        owner.forget(this)
    }

    private fun requireOpen(): Long {
        check(handle != 0L) { "LlamaSession is closed" }
        return handle
    }
}
