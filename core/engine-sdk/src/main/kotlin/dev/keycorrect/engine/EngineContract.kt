package dev.keycorrect.engine

/** The constants of the text-engine plugin contract (see [ITextEngine]). */
object EngineContract {
    /** The intent action a plugin's service answers to; the keyboard binds it explicitly. */
    const val ACTION = "dev.keycorrect.TEXT_ENGINE"

    /** The version of [ITextEngine] in this SDK. */
    const val API_VERSION = 1

    /** No call may run longer than this, whatever timeout was asked for. */
    const val MAX_TIMEOUT_MS = 2000L

    /** The most candidates one score call may carry; more are refused (null). */
    const val MAX_CANDIDATES = 32

    /** The most characters of context, prompt or one candidate; more are refused (null). */
    const val MAX_TEXT_CHARS = 8192

    /** The most tokens one generate call may ask for; more are lowered to this. */
    const val MAX_GENERATE_TOKENS = 64

    // ---- Keys of the Bundle returned by ITextEngine.info.

    /** A stable id of the engine (String). */
    const val INFO_ID = "id"

    /** A name for the settings screen (String). */
    const val INFO_NAME = "name"

    /** What the engine can do: [CAPABILITY_SCORE] and [CAPABILITY_GENERATE] (String[]). */
    const val INFO_CAPABILITIES = "capabilities"

    /** BCP-47 tags of the languages the engine handles (String[]). */
    const val INFO_LANGUAGES = "languages"

    /** One of [STATUS_READY], [STATUS_NO_MODEL], [STATUS_LOADING] or `error:<reason>` (String). */
    const val INFO_STATUS = "status"

    /** An optional short description of the loaded model, for the settings screen (String). */
    const val INFO_MODEL = "model"

    // ---- Capability names.

    /** The engine answers [ITextEngine.score]. */
    const val CAPABILITY_SCORE = "Score"

    /** The engine answers [ITextEngine.generate]. */
    const val CAPABILITY_GENERATE = "Generate"

    // ---- Status values.

    /** The engine can answer now. */
    const val STATUS_READY = "ready"

    /** The engine has no model (not configured, or released under memory pressure). */
    const val STATUS_NO_MODEL = "no_model"

    /** The engine is loading its model; calls give no answer until it is ready. */
    const val STATUS_LOADING = "loading"

    /** The prefix of an error status; the reason follows. */
    const val STATUS_ERROR_PREFIX = "error:"

    /** The status for a failure; [reason] is a short code, never user text. */
    fun errorStatus(reason: String): String = STATUS_ERROR_PREFIX + reason

    /** Whether [status] is an error status. */
    fun isError(status: String?): Boolean = status?.startsWith(STATUS_ERROR_PREFIX) == true

    // ---- Options of the built-in engine's configure call.

    /** Absolute path of an imported model file inside the app's files (String). */
    const val OPTION_MODEL_PATH = "modelPath"

    /** Use the default model of the neural pack when true (Boolean). */
    const val OPTION_USE_DEFAULT = "useDefault"

    /** How many threads to use for inference (Int). */
    const val OPTION_THREADS = "threads"

    /** The timeout the engine really applies: at least 1 ms and at most [MAX_TIMEOUT_MS]. */
    fun clampTimeout(timeoutMs: Long): Long = timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS)
}
