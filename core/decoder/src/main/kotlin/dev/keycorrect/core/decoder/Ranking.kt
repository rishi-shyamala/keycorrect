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
 */
data class ScoreBreakdown(
    val spatial: Float,
    val lm: Float,
    val rightLm: Float,
    val edits: Float,
    val editCount: Int,
    val completionChars: Int,
    val source: CandidateSource,
) {
    /** The engine's combined score: spatial + edits + completion + lambda * lm + w * rightLm. */
    fun total(config: ScoringConfig): Float =
        spatial +
            edits +
            completionChars * config.completionCharPenalty +
            config.lambda * lm +
            config.rightContextWeight * rightLm
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
    val source: CandidateSource
        get() = breakdown.source

    fun toCandidate(): Candidate = Candidate(text, score, source)

    override fun toString(): String = "RankedCandidate($text, $score, $breakdown)"
}

/** Everything one decode produced; [ClassicEngine.predictNow] reduces it to a `PredictResult`. */
class RankedPrediction(
    val literal: RankedCandidate?,
    val candidates: List<RankedCandidate>,
    val autocorrect: RankedCandidate?,
    val phrase: Candidate?,
) {
    companion object {
        /** What a stale (abandoned) request returns. */
        val Empty = RankedPrediction(null, emptyList(), null, null)
    }
}
