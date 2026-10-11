package dev.keycorrect.core.learning

import dev.keycorrect.core.engineapi.FieldClass

/**
 * The outcome classifier's output: small, model-agnostic updates (docs/PLAN.md §6.3). Weights are
 * relative (1.0 = one ordinary typed word). The classifier is pure: events in, updates out.
 */
sealed interface LearningUpdate {
    /** Evidence the user uses [word] after [context] (context may be empty). */
    data class WordUsed(val context: List<String>, val word: String, val weight: Float) :
        LearningUpdate

    /** Evidence for a word missing from every dictionary (row 4); promoted by the model's rules. */
    data class NewWordEvidence(val word: String, val sessionId: Long, val weight: Float) :
        LearningUpdate

    /**
     * Add to the user dictionary now (explicit add, or implicit add from enough keeps, rows 6/7).
     */
    data class AddUserWord(val word: String, val reason: Reason) : LearningUpdate {
        enum class Reason {
            Explicit,
            RevertedAutocorrect,
            Promoted,
        }
    }

    /**
     * Correction memory: typed → target, in a context bucket; delta > 0 favours, < 0 disfavours.
     */
    data class CorrectionEvidence(
        val typed: String,
        val target: String,
        val contextBucket: String,
        val delta: Float,
    ) : LearningUpdate

    /**
     * The user undid the autocorrection [typed] -> [corrected] and the restored [typed] was
     * finished unchanged (rows 6/7: "keep [typed]"). One keep is only evidence; the model counts
     * keeps per word and per pair and adds [typed] to the user dictionary / suppresses the pair
     * when the runtime-configurable thresholds are reached (`PersonalModel.updateThresholds`).
     */
    data class RevertKept(val typed: String, val corrected: String) : LearningUpdate

    /** Never correct [typed] to [target] again (rows 8, and rows 6/7 once enough keeps counted). */
    data class SuppressCorrection(val typed: String, val target: String) : LearningUpdate

    /** Never suggest [word] (row 11). */
    data class BlockWord(val word: String) : LearningUpdate

    /** Touch offsets from a word the user accepted (§6.1 touch model, §6.5 keyboard fit). */
    data class TouchEvidence(val touchKey: String, val samples: List<TouchSample>) : LearningUpdate

    /**
     * Ranking preference: [preferred] should have outranked [other] for [typed] in this context
     * (pairwise example for the fusion ranker, §6.1). [weight] scales visibility/confidence.
     */
    data class Preference(
        val typed: String,
        val context: List<String>,
        val preferred: String,
        val other: String,
        val weight: Float,
        /** Field class of the event it came from (for the feature extraction's context bucket). */
        val field: FieldClass = FieldClass.Text,
    ) : LearningUpdate

    /** One autocorrection outcome for the adaptive threshold: true = reverted. */
    data class AutocorrectOutcome(val reverted: Boolean) : LearningUpdate

    /** Phrase-gate feedback (rows 18–20). */
    data class PhraseFeedback(val offeredWords: Int, val acceptedWords: Int) : LearningUpdate

    /** Casing preference (row 13): the user typed [surface] for the lowercase key mid-sentence. */
    data class CasingEvidence(val surface: String) : LearningUpdate
}
