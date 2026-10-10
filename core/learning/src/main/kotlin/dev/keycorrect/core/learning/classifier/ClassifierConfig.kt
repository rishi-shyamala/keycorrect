package dev.keycorrect.core.learning.classifier

/**
 * Every weight and threshold the [OutcomeClassifier] uses (docs/PLAN.md §6.3). Weights are
 * relative: 1.0 is one ordinary typed word. Deltas for correction memory are in the same units; the
 * model decides how they move counts or log-biases.
 */
data class ClassifierConfig(
    // ---- positive word evidence -------------------------------------------------------------
    /** Row 3/4/12: a word the user typed (or swiped) and committed. */
    val typedWordWeight: Float = 1.0f,
    /** Row 1: explicit strip tap; stronger than typing because the user actively chose it. */
    val suggestionPickedWeight: Float = 1.5f,
    /** Row 8: tapping the literal slot ("keep what I typed"). */
    val literalPickedWeight: Float = 1.5f,
    /**
     * Tapping a next-word prediction with nothing typed. Lower than typing: it would otherwise
     * reinforce the model's own predictions in a loop.
     */
    val predictionPickedWeight: Float = 0.5f,

    // ---- unknown words (row 4) ---------------------------------------------------------------
    /**
     * Row 4: weight of one [NewWordEvidence] commit; promotion (>=3 weight in >=3 sessions by
     * default) is the model's.
     */
    val newWordEvidenceWeight: Float = 1.0f,
    /** Row 8: tapping the literal of an unknown word is an explicit keep: strong evidence. */
    val literalNewWordEvidenceWeight: Float = 2.0f,
    /**
     * Unknown words also emit WordUsed so the n-gram cache can learn them quickly, but at this
     * fraction of the normal weight so uncorrected typos don't pollute prediction before promotion.
     */
    val unknownWordUsedScale: Float = 0.3f,
    /**
     * Row 4: an unknown typed word with a shown known suggestion within the typo edit distance
     * (below) is probably an unfixed typo: it emits no NewWordEvidence and its WordUsed is
     * multiplied by this on top of [unknownWordUsedScale].
     */
    val likelyTypoScale: Float = 0.25f,
    /** Words up to this length use [typoMaxEditsShort], longer ones [typoMaxEditsLong]. */
    val typoShortLength: Int = 4,
    val typoMaxEditsShort: Int = 1,
    val typoMaxEditsLong: Int = 2,
    /**
     * Unknown words shorter than this are never counted as new words from plain typing (single
     * stray letters); an explicit add, a revert or a literal tap still can.
     */
    val newWordMinLength: Int = 3,

    // ---- visibility (row 2/3) ----------------------------------------------------------------
    /** A suggestion visible at least this long counts as seen (row 2). */
    val fullVisibilityMillis: Long = 300,
    /** ...and it must have appeared at least this many characters before the end of the word. */
    val fullVisibilityCharsBeforeEnd: Int = 2,
    /**
     * Multiplier for suggestions the user probably didn't see (appeared on the last char, or
     * flashed).
     */
    val lowVisibilityScale: Float = 0.2f,

    // ---- pairwise preferences (fusion ranker training) ---------------------------------------
    /** Row 1: picked candidate beats each higher-ranked shown candidate. Weak. */
    val pickedPreferenceWeight: Float = 0.3f,
    /** Row 2: typed-out suggestion beats the other shown candidates (before visibility scaling). */
    val implicitAcceptPreferenceWeight: Float = 0.5f,
    /**
     * Row 3: typed word beats the top shown candidate (before visibility scaling); this is also the
     * per-pair cap.
     */
    val ignoredSuggestionWeight: Float = 0.2f,
    /** Rows 19: weak preference of the user's diverging word over the predicted word. */
    val phrasePreferenceWeight: Float = 0.3f,

    // ---- autocorrect outcomes (rows 5-7, 22, 23) -----------------------------------------------
    /** Row 5: autocorrection kept. */
    val autocorrectKeptDelta: Float = 1.0f,
    /** Row 22: a near-tied pick is kept; weaker because the user may simply not have noticed. */
    val tieKeptDelta: Float = 0.5f,
    /** Rows 6/7/23: first revert (immediate backspace, or deleted-and-retyped literal). Strong. */
    val revertDelta: Float = 2.0f,
    /** Row 7: corrected word later edited into something else: negative for the original pair. */
    val editedLaterNegativeDelta: Float = 1.0f,
    /** Row 7: ...and positive for typed -> replacement. */
    val editedLaterPositiveDelta: Float = 1.0f,
    /** Row 22: switch to the runner-up / another suggestion: negative for the chosen pair. */
    val switchNegativeDelta: Float = 1.0f,
    val switchPositiveDelta: Float = 1.0f,
    /** Row 22: replacement beats the corrected word in a ranker pair. */
    val switchPreferenceWeight: Float = 1.0f,
    /**
     * Row 1 (extension): tapping a correction-like suggestion for a typed non-prefix teaches the
     * pair.
     */
    val pickedCorrectionDelta: Float = 0.5f,
    /**
     * Whether a revert counts toward the adaptive autocorrect threshold's revert rate (the "kept"
     * outcome was already counted when the correction was applied):
     * - [rankingRevertsCount]: the user switched to another suggestion or edited the corrected word
     *   into something else (rows 7/22). The correction was wanted but the wrong word; a stricter
     *   threshold would only leave the typo, and the correction memory handles the pair.
     * - [implicitAddRevertsCount]: the user restored the literal and the revert added it to the
     *   dictionary (rows 6/23). A missing word, not an over-eager threshold.
     */
    val rankingRevertsCount: Boolean = false,
    val implicitAddRevertsCount: Boolean = true,
    /** Row 8: negative for the top correction-like candidate the user declined. */
    val literalRejectDelta: Float = 1.5f,

    // ---- later edits (rows 9, 10) --------------------------------------------------------------
    /** Row 9: a just-tapped suggestion deleted within this window is a mis-tap. */
    val misTapWindowMillis: Long = 3_000,
    /**
     * Row 9: fraction of [suggestionPickedWeight] taken back (1.0 = the accept is fully undone).
     */
    val misTapUndoFraction: Float = 1.0f,
    /** Row 10: edit distance <= ceil(length * this) means "typo fix" rather than rewording. */
    val missedCorrectionMaxEditRatio: Float = 1f / 3f,
    /** Row 10: shorter originals are never turned into correction pairs (too ambiguous). */
    val missedCorrectionMinLength: Int = 3,
    /** Row 10: pair delta for a missed correction. */
    val missedCorrectionDelta: Float = 0.5f,

    // ---- phrases (rows 18-20) ------------------------------------------------------------------
    /** Weight of an accepted phrase word; phrase words are explicit taps. */
    val phraseAcceptedWordWeight: Float = 1.5f,
    /** Negative WordUsed for the first predicted word the user did not follow. */
    val phraseRejectedWordWeight: Float = 0.3f,

    // ---- global (rows 14-16) -------------------------------------------------------------------
    /** Row 16: multiplier for every weight learned from dictated text. */
    val voiceScale: Float = 0.3f,
    /**
     * Row 14: cap on the summed negative magnitude (negative WordUsed + negative CorrectionEvidence
     * + Preference weights, i.e. what a "loser" receives) in one event. Updates are scaled
     *   proportionally when it is exceeded.
     */
    val maxNegativeMassPerEvent: Float = 2.5f,

    // ---- touch alignment (row 5, §6.5) ---------------------------------------------------------
    /**
     * Touch evidence is only used when typed and committed have the same length (the keyboard emits
     * samples relative to the intended key for those words) and differ in at most this many
     * positions; with more mismatches the word is more likely a different word than sloppy taps.
     */
    val alignmentMaxMismatches: Int = 2,

    // ---- defensive input filtering (row 15) ----------------------------------------------------
    /** Longer "words" are bulk text (paste-like) and ignored. */
    val maxWordLength: Int = 40,
) {
    init {
        require(voiceScale in 0f..1f) { "voiceScale must be in 0..1" }
        require(maxNegativeMassPerEvent >= 0f) { "maxNegativeMassPerEvent must be >= 0" }
        require(lowVisibilityScale in 0f..1f) { "lowVisibilityScale must be in 0..1" }
        require(misTapUndoFraction in 0f..1f) { "misTapUndoFraction must be in 0..1" }
        require(maxWordLength > 0) { "maxWordLength must be > 0" }
    }
}
