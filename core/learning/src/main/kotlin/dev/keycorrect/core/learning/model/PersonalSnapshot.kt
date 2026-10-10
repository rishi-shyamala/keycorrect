package dev.keycorrect.core.learning.model

import dev.keycorrect.core.learning.Personalization
import dev.keycorrect.core.learning.RankingFeatures

/**
 * Immutable, lock-free view of everything learned at one moment, read by the decoder thread. Built
 * by [PersonalModel] on the learning thread and published through an atomic reference; never
 * mutated afterwards (collections are private copies, table shards are shared copy-on-write).
 */
class PersonalSnapshot
internal constructor(
    /** Increases with every publish. */
    val version: Long,
    private val ngrams: FrozenNgrams?,
    private val corrections: FrozenCorrections?,
    private val suppressed: Set<String>,
    private val blocked: Set<String>,
    private val userWords: Set<String>,
    private val casing: Map<String, String>,
    private val touch: FrozenTouch?,
    private val rankerWeights: FloatArray,
    private val rankerMaxScore: Float,
    override val autocorrectThresholdDelta: Float,
    override val phraseGateDelta: Float,
) : Personalization {
    override fun userLogProb(context: List<String>, word: String): Float? =
        ngrams?.logProb(context, word)

    override fun correctionBias(typed: String, candidate: String, contextBucket: String): Float =
        corrections?.bias(typed, candidate, contextBucket) ?: 0f

    override fun isSuppressed(typed: String, candidate: String): Boolean =
        suppressed.isNotEmpty() &&
            suppressed.contains(Text.join(Text.key(typed), Text.key(candidate)))

    override fun isBlocked(word: String): Boolean =
        blocked.isNotEmpty() && blocked.contains(Text.key(word))

    override fun preferredCasing(key: String): String? =
        if (casing.isEmpty()) null else casing[Text.key(key)]

    override fun touchOffset(touchKey: String, char: Char): Pair<Float, Float>? =
        touch?.offset(touchKey, char)

    override fun rankerScore(features: RankingFeatures): Float =
        FusionRanker.score(rankerWeights, features, rankerMaxScore)

    /** True when [word] is on the user dictionary the model knows about (promoted or added). */
    fun isUserWord(word: String): Boolean = userWords.contains(Text.key(word))

    /** Aggregate touch statistics for the keyboard-fit report (§6.5); computed on demand. */
    fun touchStatistics(): TouchStatistics = touch?.statistics() ?: TouchStatistics(emptyList())

    companion object {
        /** Snapshot of a model that has learned nothing. */
        val Empty: PersonalSnapshot =
            PersonalSnapshot(
                0,
                null,
                null,
                emptySet(),
                emptySet(),
                emptySet(),
                emptyMap(),
                null,
                FloatArray(FusionRanker.DIM),
                3f,
                0f,
                0f,
            )
    }
}
