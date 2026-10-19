package dev.keycorrect.neural

/**
 * Opens language models (GGUF files) for the engine process. The llama.cpp adapter implements it;
 * until one is installed in [ModelRuntimes] nothing opens and the engine stays without a model.
 */
interface ModelRuntime {
    /**
     * Why this runtime can never open a model on this device (a short code such as
     * `UnsupportedCpu`, shown as `error:<code>`), or null. Checked before any open.
     */
    fun problem(): String? = null

    /** Opens the model file at [path]; null when it can't be opened. [threads] is for inference. */
    fun openFile(path: String, threads: Int): LoadedModel?

    /**
     * Opens a model stored in a file starting at byte [offset] (an uncompressed asset of the neural
     * pack). [fd] is only valid during the call: a runtime that needs it longer must duplicate it.
     */
    fun openFd(fd: Int, offset: Long, threads: Int): LoadedModel?
}

/**
 * An open model. The engine never calls it from two threads at once, and closes it when the model
 * is replaced, released under memory pressure, or the service ends.
 */
interface LoadedModel : AutoCloseable {
    /**
     * Sum of log probabilities of each candidate's tokens after [context] (empty = start of text),
     * in order; null when it can't be done within [timeoutMs].
     */
    fun score(context: String, candidates: Array<String>, timeoutMs: Long): FloatArray?

    /** A short continuation of [prompt] (greedy), or null when not done within [timeoutMs]. */
    fun generate(prompt: String, maxTokens: Int, timeoutMs: Long): String?

    /** A short description for the settings screen (architecture, size). Never user text. */
    fun describe(): String
}

/** A runtime that opens nothing: the engine reports no model. */
internal object NoModelRuntime : ModelRuntime {
    override fun openFile(path: String, threads: Int): LoadedModel? = null

    override fun openFd(fd: Int, offset: Long, threads: Int): LoadedModel? = null
}

/** Where the engine process gets its [ModelRuntime]. */
object ModelRuntimes {
    /**
     * Makes the runtime; called each time a model is opened. Replace it early in the process
     * (`Application.onCreate` runs in the engine process too).
     */
    @Volatile var provider: () -> ModelRuntime = { NoModelRuntime }
}
