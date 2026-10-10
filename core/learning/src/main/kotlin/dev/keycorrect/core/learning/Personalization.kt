package dev.keycorrect.core.learning

/**
 * Read-only view of everything learned, consumed by the decoder on its own thread (§6.1). All
 * methods must be cheap (no I/O, no locks on the hot path) — implementations publish immutable
 * snapshots. Words are compared case-insensitively with apostrophes folded.
 */
interface Personalization {
    /** ln P_user(word | context) from the personal n-gram cache, or null with too little data. */
    fun userLogProb(context: List<String>, word: String): Float?

    /** Learned bias for correcting [typed] to [candidate] (ln units; > 0 favours). */
    fun correctionBias(typed: String, candidate: String, contextBucket: String): Float

    fun isSuppressed(typed: String, candidate: String): Boolean

    fun isBlocked(word: String): Boolean

    /** Preferred surface form for a lowercase key ("iphone" → "iPhone"), if learned. */
    fun preferredCasing(key: String): String?

    /** Learned mean touch offset for [char] (key units), or null below the evidence threshold. */
    fun touchOffset(touchKey: String, char: Char): Pair<Float, Float>?

    /** Additive adjustment to the autocorrect threshold from the observed revert rate. */
    val autocorrectThresholdDelta: Float

    /** Extra ranking score from the fusion ranker for one candidate's features. */
    fun rankerScore(features: RankingFeatures): Float

    /** Whether phrase completions are currently worth offering, and the learned gate. */
    val phraseGateDelta: Float

    companion object {
        /** No learning (incognito, learning disabled, or nothing learned yet). */
        val None: Personalization =
            object : Personalization {
                override fun userLogProb(context: List<String>, word: String): Float? = null

                override fun correctionBias(
                    typed: String,
                    candidate: String,
                    contextBucket: String,
                ) = 0f

                override fun isSuppressed(typed: String, candidate: String) = false

                override fun isBlocked(word: String) = false

                override fun preferredCasing(key: String): String? = null

                override fun touchOffset(touchKey: String, char: Char): Pair<Float, Float>? = null

                override val autocorrectThresholdDelta = 0f

                override fun rankerScore(features: RankingFeatures) = 0f

                override val phraseGateDelta = 0f
            }
    }
}

/**
 * Decomposed candidate score the fusion ranker learns over (mirrors the decoder's ScoreBreakdown).
 * Keep fields dimensionless/log-domain so weights transfer across engines.
 */
data class RankingFeatures(
    val spatial: Float,
    val lm: Float,
    val edits: Float,
    val completionChars: Int,
    val userLm: Float,
    val correctionBias: Float,
    val isLiteral: Boolean,
    val isUserWord: Boolean,
    val rank: Int,
)

/** Context bucket for correction memory: previous word (lowercase, folded) + field class. */
fun contextBucket(context: List<String>, field: String): String =
    (context.lastOrNull()?.lowercase()?.replace('’', '\'')?.replace('‘', '\'') ?: "^") + "|" + field
