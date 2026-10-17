package dev.keycorrect.core.engineapi

/**
 * A pluggable autocorrect/autocomplete engine (docs/PLAN.md §4). Engines declare what they can do
 * through [EngineInfo.capabilities]; the keyboard uses whatever is available. All calls may be made
 * from a background thread and must be safe to cancel.
 *
 * Only Tier 2 ([PredictiveEngine], [Capability.Predict]) is used today, by the built-in engine.
 * Tiers 0 and 1, the other capabilities, and [load]/[unload] are the plugin contract for neural and
 * external engines (Phase 7); nothing calls them yet.
 */
interface TextEngine {
    val info: EngineInfo

    suspend fun load()

    suspend fun unload()
}

enum class Capability {
    /** Text in, text out ([GenerativeEngine]). */
    Generate,
    /** Log-probability of candidate strings given context ([ScoringEngine]). */
    Score,
    /** Full decoding from touches and context ([PredictiveEngine]). */
    Predict,
    /** Decoding a swipe into words ([GestureEngine], Phase 5). */
    Gesture,
    /** Accepts learning events (Phase 3). */
    Learn,
    /** Accepts personal-vocabulary hints (Phase 3). */
    Hints,
}

data class EngineInfo(
    val id: String,
    val name: String,
    val capabilities: Set<Capability>,
    /** BCP-47 language tags, e.g. "en-US". */
    val languages: Set<String>,
    /** True when text may leave the device. Remote engines are gated per field (§4.3). */
    val remote: Boolean = false,
)

/** Tier 0: any model that maps text to text. Unused until Phase 7. */
interface GenerativeEngine : TextEngine {
    suspend fun generate(request: GenerateRequest): GenerateResult
}

data class GenerateRequest(
    val prompt: String,
    val maxTokens: Int = 16,
    val count: Int = 1,
    val stop: List<String> = emptyList(),
)

data class GenerateResult(val outputs: List<String>)

/**
 * Tier 1: scores candidate strings, the best fit for reranking dictionary candidates. Unused until
 * Phase 7.
 */
interface ScoringEngine : TextEngine {
    /** Natural-log probability of each candidate following [context]; same order as input. */
    suspend fun score(context: TypingContext, candidates: List<String>): FloatArray
}

/** Tier 2: decodes touches + context into ranked candidates (the built-in engine is one). */
interface PredictiveEngine : TextEngine {
    suspend fun predict(request: PredictRequest): PredictResult
}

/**
 * Tier 2b (Phase 5, docs/PHASE5.md): decodes a swipe into ranked words. The built-in engine is one.
 */
interface GestureEngine : TextEngine {
    /**
     * In the result, [PredictResult.candidates] are the words ranked best first (the keyboard
     * commits the first and offers the rest), [PredictResult.literal] is null, and
     * [PredictResult.autocorrect] is the first candidate, or null when nothing plausible was found.
     */
    suspend fun decodeGesture(request: GestureRequest): PredictResult
}

/**
 * A swipe across [layout]'s keys, after [context]. [sequence] works as in [PredictRequest]: the
 * keyboard sends previews while the finger moves and drops results older than the latest.
 */
data class GestureRequest(
    val context: TypingContext,
    val gesture: GestureInput,
    val layout: KeyLayoutModel,
    val maxCandidates: Int = 3,
    val sequence: Long = 0,
)

/**
 * [sequence] increases with every input event; results for an older sequence are stale and the
 * caller drops them.
 */
data class PredictRequest(
    val context: TypingContext,
    val composing: ComposingInput,
    val layout: KeyLayoutModel,
    val maxCandidates: Int = 3,
    val maxPhraseWords: Int = 0,
    val autocorrectAllowed: Boolean = true,
    val sequence: Long = 0,
)

/**
 * [candidates] are ranked best first and never include the literal; [literal] is the typed word
 * (null for next-word prediction). [autocorrect] is the candidate to apply on space/punctuation, or
 * null to keep the literal. [phrase] is an optional multi-word completion (§3.2).
 */
data class PredictResult(
    val candidates: List<Candidate>,
    val literal: Candidate?,
    val autocorrect: Candidate?,
    val phrase: Candidate? = null,
    val sequence: Long = 0,
    /**
     * When [autocorrect] was chosen between near-tied candidates for a non-word literal
     * (docs/PLAN.md §6.3 row 22), the runner-up — so the keyboard can learn from which one the user
     * keeps.
     */
    val autocorrectRunnerUp: Candidate? = null,
)
