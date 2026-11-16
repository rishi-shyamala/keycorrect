package dev.keycorrect.core.engineapi

/**
 * A pluggable autocorrect/autocomplete engine (docs/PLAN.md §4). Engines declare what they can do
 * through [EngineInfo.capabilities]. All calls may be made from a background thread and must be
 * safe to cancel. The built-in engine is the only implementation: it decodes touches
 * ([PredictiveEngine]) and swipes ([GestureEngine]). Optional neural and plugin engines are not
 * [TextEngine]s; they are reached over the engine service (`:core:engine-sdk`).
 */
interface TextEngine {
    val info: EngineInfo

    suspend fun load()

    suspend fun unload()
}

enum class Capability {
    /** Full decoding from touches and context ([PredictiveEngine]). */
    Predict,
    /** Decoding a swipe into words ([GestureEngine], Phase 5). */
    Gesture,
}

data class EngineInfo(
    val id: String,
    val name: String,
    val capabilities: Set<Capability>,
    /** BCP-47 language tags, e.g. "en-US". */
    val languages: Set<String>,
)

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
    /**
     * Language-model re-ranking: a score to add to the candidate (or the literal) whose text equals
     * the key, after the fused score is computed and before the final ranking and the autocorrect
     * decision, so a bonus can change both the strip order and whether a correction is applied.
     * Positive favours a text, negative demotes it; texts not listed are untouched. Empty (the
     * default) leaves the decode exactly as without it.
     */
    val lmBonus: Map<String, Float> = emptyMap(),
    /**
     * True when the keyboard lets an optional engine (a neural scorer, a plugin) see this request's
     * text. Set only for ordinary, non-sensitive, learning-enabled, non-incognito fields with the
     * feature switched on; the classic engine ignores it.
     */
    val engineAllowed: Boolean = false,
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
