package dev.keycorrect.core.decoder

import dev.keycorrect.core.engineapi.Candidate
import dev.keycorrect.core.engineapi.CandidateSource

/**
 * The parts of a candidate's score, kept separate so Phase 3's learning layer can re-weight them as
 * features (§6.1). All values are natural-log scale; penalties are negative.
 *
 * @property spatial summed touch log-likelihood of the typed chars against the word.
 * @property lm left-context language-model log-probability (or the user-word prior).
 * @property rightLm log P(next word | candidate); 0 when no right context is known.
 * @property edits summed edit penalties (insertion, deletion, substitution, transposition).
 * @property editCount number of edit operations.
 * @property completionChars chars the word has beyond the typed input.
 * @property userLm what the personal n-gram added to [lm] (interpolated minus base, ln units; 0
 *   without personalization), so the base language-model score is `lm - userLm`.
 * @property correctionBias learned bias for correcting the literal to this candidate (0 for the
 *   literal and without personalization); part of the combined score.
 */
data class ScoreBreakdown(
    val spatial: Float,
    val lm: Float,
    val rightLm: Float,
    val edits: Float,
    val editCount: Int,
    val completionChars: Int,
    val source: CandidateSource,
    val userLm: Float = 0f,
    val correctionBias: Float = 0f,
) {
    /**
     * The engine's combined score: spatial + edits + completion + lambda * lm + w * rightLm +
     * correction bias (before any learned ranker score).
     */
    fun total(config: ScoringConfig): Float =
        spatial +
            edits +
            completionChars * config.completionCharPenalty +
            config.lambda * lm +
            config.rightContextWeight * rightLm +
            correctionBias
}

/**
 * A scored suggestion with its [breakdown]. [wordId] is the dictionary word id, or -1 for user
 * words, shortcuts and the literal. [dictionaryForm] is the word as stored (before the user's
 * capitalisation is applied to [text]); null for the literal and shortcuts.
 */
class RankedCandidate(
    val text: String,
    val score: Float,
    val breakdown: ScoreBreakdown,
    val wordId: Int = -1,
    val dictionaryForm: String? = null,
) {
    /**
     * Position in the pool before the learned ranker score was added (0 = best, -1 for the literal,
     * shortcuts and anything not ranked); the `rank` of
     * [dev.keycorrect.core.learning .RankingFeatures], defined this way so training and inference
     * see the same value.
     */
    var rank: Int = -1
        internal set

    val source: CandidateSource
        get() = breakdown.source

    /** Same candidate with another [score] (the learned ranker score added), keeping [rank]. */
    fun withScore(score: Float): RankedCandidate =
        RankedCandidate(text, score, breakdown, wordId, dictionaryForm).also { it.rank = rank }

    fun toCandidate(): Candidate = Candidate(text, score, source)

    override fun toString(): String = "RankedCandidate($text, $score, $breakdown)"
}

/** Everything one decode produced; [ClassicEngine.predictNow] reduces it to a `PredictResult`. */
class RankedPrediction(
    val literal: RankedCandidate?,
    val candidates: List<RankedCandidate>,
    val autocorrect: RankedCandidate?,
    val phrase: Candidate?,
    /**
     * Set with [autocorrect] when the top two complete-word candidates were within the near-tie
     * margin (see [AutocorrectConfig.nearTieMargin]); the other of the two.
     */
    val autocorrectRunnerUp: RankedCandidate? = null,
) {
    companion object {
        /** What a stale (abandoned) request returns. */
        val Empty = RankedPrediction(null, emptyList(), null, null)
    }
}
