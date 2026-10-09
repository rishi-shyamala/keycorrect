package dev.keycorrect.core.decoder

/**
 * All tunables of the classic engine (docs/PLAN.md §4.4). Log-likelihoods and penalties are natural
 * logs (higher is better, penalties are negative). Defaults are hand-tuned against the synthetic
 * test dictionary; Phase 3's learning layer adjusts [AutocorrectConfig.threshold] and the touch
 * model at runtime rather than editing these.
 */
data class ClassicConfig(
    val spatial: SpatialConfig = SpatialConfig(),
    val edits: EditConfig = EditConfig(),
    val search: SearchConfig = SearchConfig(),
    val scoring: ScoringConfig = ScoringConfig(),
    val autocorrect: AutocorrectConfig = AutocorrectConfig(),
    val phrase: PhraseConfig = PhraseConfig(),
    val user: UserDataConfig = UserDataConfig(),
)

/** Per-key Gaussian touch model (§4.4). */
data class SpatialConfig(
    /** Standard deviation as a fraction of key width. */
    // 1.0 chosen by the eval harness sweep (docs/EVAL.md): vs 0.45, typist sigma 0.25 gives
    // 93.83% vs 93.68% accuracy with 2.48 vs 3.88 false corrections per 1000 words, and sigma 0.35
    // gives 77.90% vs 75.87% with 1.07 vs 1.81.
    val sigmaXFactor: Float = 1.0f,
    /** Standard deviation as a fraction of key height. */
    val sigmaYFactor: Float = 1.0f,
    /** Lower bound of a per-char log-likelihood, so one far touch never dominates a word. */
    val floorLogLikelihood: Float = -12f,
    /** Added when a base letter's key is used for an accented letter (e -> é). */
    val accentPenalty: Float = -1f,
    /** Cost of matching a different char against a touch-less input char (popup, paste). */
    val nullMismatchPenalty: Float = -4.5f,
)

/** Edit operations of the candidate search; penalties are added to the spatial score. */
data class EditConfig(
    /** Extra cost of replacing the typed char (on top of its spatial log-likelihood). */
    val substitutionPenalty: Float = -1f,
    /** A typed char the word doesn't have. */
    val insertionPenalty: Float = -3.5f,
    /** A word char that wasn't typed. */
    val deletionPenalty: Float = -4f,
    /** Two adjacent typed chars in the wrong order. */
    val transpositionPenalty: Float = -3f,
    /** Insertion/deletion of a char that repeats its neighbour ("helllo", "helo"). */
    val doubleLetterPenalty: Float = -2f,
    /** Insertion/deletion of an apostrophe ("dont", "don't"). */
    val apostrophePenalty: Float = -0.8f,
    /** Extra cost of any edit at the first char (people rarely get it wrong). */
    val firstCharPenalty: Float = -1.5f,
    /** A substitution is only explored when the touch likelihood of the new char is above this. */
    val substitutionCutoff: Float = -8f,
    /** Both chars of a transposition must have at least this touch likelihood. */
    val transpositionCutoff: Float = -3f,
    /** Typed chars per allowed edit: budget = (length + 1) / charsPerEdit, capped by [maxEdits]. */
    val charsPerEdit: Int = 3,
    val maxEdits: Int = 3,
) {
    /** Number of edits allowed for an input of [length] chars. */
    fun budget(length: Int): Int = minOf(maxEdits, (length + 1) / charsPerEdit)
}

/** Bounds of the best-first trie search. */
data class SearchConfig(
    /** Distinct words kept for context re-scoring. */
    val poolSize: Int = 48,
    /** Hard cap on search-state expansions per request. */
    val maxExpansions: Int = 2500,
    /** Capacity of the reusable state pool; pushes beyond it are dropped. */
    val maxStates: Int = 16384,
    /** States whose optimistic score is this far below the best result are not explored. */
    val beamWidth: Float = 10f,
    /** Longest completion (chars beyond the typed input) that is explored. */
    val maxCompletionChars: Int = 10,
    /** Longer inputs get no suggestions. */
    val maxInputChars: Int = 32,
)

/** How the components of a candidate's score are combined. */
data class ScoringConfig(
    /** Weight of the left-context language model. */
    val lambda: Float = 1f,
    /** Weight of `log P(next word | candidate)` when a right context is known. */
    val rightContextWeight: Float = 0.5f,
    /** Cost of each char a completion adds beyond the typed input. */
    val completionCharPenalty: Float = -0.4f,
    /**
     * Log-probability penalty (ln units, <= 0) when a stored trigram context has no entry for the
     * word and scoring falls back to the bigram level. The dictionary stores no back-off weights.
     * The default matches `tools/dictbuild` (`Compile.penalty3`, `EvalLm --penalty3`): the value
     * the dictionary's gain pruning assumes and `eval-lm` found best; the eval harness may re-tune
     * it.
     */
    val trigramBackoffPenalty: Float = -0.8f,
    /**
     * Same for the bigram -> unigram step (`Compile.penalty2` / `EvalLm --penalty2`, default -1.3).
     * Also applied to `P(next word | candidate)` when the right-context bigram is not stored.
     */
    val bigramBackoffPenalty: Float = -1.3f,
    /** The unknown-word log-probability is the dictionary's rarest word plus this offset. */
    val oovOffset: Float = -1f,
)

/** When a suggestion may be applied without the user tapping it (§6.3). */
data class AutocorrectConfig(
    /** The candidate must beat the literal by this much. Phase 3 tunes it through the policy. */
    val threshold: Float = 2.5f,
    /** Same, when the literal is itself a dictionary word (real-word correction). */
    val realWordMargin: Float = 6f,
    /** The candidate must beat the runner-up by this much. */
    val runnerUpMargin: Float = 0.7f,
    /** Added to the threshold in person-name and address fields, where unknown words are normal. */
    val nameFieldExtraMargin: Float = 2f,
    val minLiteralLength: Int = 2,
    /** Corrections needing more edits than this are suggested but never applied. */
    val maxEdits: Int = 2,
    /** ALL CAPS input is usually an acronym; leave it alone. */
    val correctAllCaps: Boolean = false,
    /**
     * A capitalised word that is neither sentence-initial nor a known word is probably a name or
     * brand: it is only corrected when the sole edit is one adjacent transposition ("Teh").
     */
    val protectMidSentenceCapitals: Boolean = true,
    /**
     * Apostrophe-less contractions ("dont", "im", "youre") that are not words themselves are
     * corrected regardless of the spatial model (see [ContractionIndex]). Real words such as "cant"
     * or "wont" follow the normal real-word margin.
     */
    val contractions: Boolean = true,
)

/** Multi-word completion gates (§3.2). */
data class PhraseConfig(
    /** Every extension word must have at least this conditional probability. */
    val minStepProbability: Float = 0.3f,
    /** The product of the extension probabilities must reach this. */
    val minTotalProbability: Float = 0.15f,
)

/** User dictionary and shortcuts. */
data class UserDataConfig(
    /** Log-probability prior of a user word (a moderately common word). */
    val wordLogProb: Float = -7f,
    /** Words beyond this many are ignored by the linear scan. */
    val maxWords: Int = 4000,
)
