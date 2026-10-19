package dev.keycorrect.core.llama

/**
 * The calls the native library offers; [LlamaNative] is the real one. It exists so the wrappers can
 * be tested without the library. Handles are native pointers as [Long]; 0 means failure.
 */
interface LlamaBackend {
    /** Loads a GGUF from [path]. Returns a model handle or 0. */
    fun loadModelFile(path: String, useMmap: Boolean): Long

    /**
     * Loads a GGUF that starts at [offset] in the open file [fd] (the fd stays owned by the
     * caller). Maps it only when [useMmap] is set and `offset % 32 == 0`. Returns a handle or 0.
     */
    fun loadModelFd(fd: Int, offset: Long, useMmap: Boolean): Long

    /** A context with [nCtx] tokens of KV cache and [nThreads] threads, or 0. */
    fun newContext(model: Long, nCtx: Int, nThreads: Int): Long

    fun freeContext(ctx: Long)

    fun freeModel(model: Long)

    /**
     * For each candidate, the sum of log P over the tokens of `" " + candidate` given [context], or
     * null on timeout or error.
     */
    fun score(ctx: Long, context: String, candidates: Array<String>, timeoutMs: Long): FloatArray?

    /** Greedy continuation of [prompt], or null on timeout or error. */
    fun generate(ctx: Long, prompt: String, maxTokens: Int, timeoutMs: Long): String?

    /** Architecture, parameter count and vocabulary size, for the settings screen. */
    fun describe(model: Long): String
}

/**
 * The JNI surface of `libkcllama.so` (`core/llama/src/main/cpp/kc_llama_jni.cpp`); see
 * [LlamaBackend] for what each function does. Nothing here checks its arguments or tracks
 * lifetimes: use [LlamaModel] and [LlamaSession], which do.
 *
 * Loading the library is the caller's job (see [LlamaLibrary]): Android loads it from the neural
 * pack by absolute path, the desktop from a build directory.
 */
object LlamaNative : LlamaBackend {
    external override fun loadModelFile(path: String, useMmap: Boolean): Long

    external override fun loadModelFd(fd: Int, offset: Long, useMmap: Boolean): Long

    external override fun newContext(model: Long, nCtx: Int, nThreads: Int): Long

    external override fun freeContext(ctx: Long)

    external override fun freeModel(model: Long)

    external override fun score(
        ctx: Long,
        context: String,
        candidates: Array<String>,
        timeoutMs: Long,
    ): FloatArray?

    external override fun generate(
        ctx: Long,
        prompt: String,
        maxTokens: Int,
        timeoutMs: Long,
    ): String?

    external override fun describe(model: Long): String
}
