package dev.keycorrect.core.engineapi

/** Where a candidate came from. Used for ranking features and UI hints. */
enum class CandidateSource {
    /** Exactly what the user typed. */
    Literal,
    Dictionary,
    UserDictionary,
    Shortcut,
    /** Produced by a pluggable engine. */
    Engine,
}

/**
 * A suggestion. [text] replaces the composing word (or, for next-word predictions, is inserted
 * after the context). [score] is a natural-log probability-like value: higher is better; only
 * comparable between candidates of one result. A phrase spans several words ([isPhrase]).
 */
data class Candidate(
    val text: String,
    val score: Float,
    val source: CandidateSource,
    val isPhrase: Boolean = false,
)
