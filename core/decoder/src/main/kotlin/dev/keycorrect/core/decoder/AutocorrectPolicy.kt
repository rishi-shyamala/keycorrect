package dev.keycorrect.core.decoder

import dev.keycorrect.core.engineapi.CandidateSource
import dev.keycorrect.core.engineapi.FieldClass

/** What [AutocorrectPolicy] needs to know about one decode. */
class AutocorrectInput(
    val literal: String,
    val literalScore: Float,
    val literalIsDictionaryWord: Boolean,
    val literalIsUserWord: Boolean,
    /** Ranked non-literal candidates, best first. */
    val candidates: List<RankedCandidate>,
    val field: FieldClass,
    /** `PredictRequest.autocorrectAllowed`. */
    val allowed: Boolean,
)

/**
 * Decides whether the best suggestion may replace the typed word on space/punctuation (§6.3).
 * Deliberately conservative: a wrong autocorrect costs more than a missed one.
 *
 * A candidate is applied only if all of these hold: autocorrect is allowed and the field is not
 * e-mail/URL; the literal has at least [AutocorrectConfig.minLiteralLength] chars, no digits, `@`
 * or `/`, is not a user word and not an ALL CAPS acronym; the best complete-word candidate
 * (completions are ignored: the word is finished) needs at most [AutocorrectConfig.maxEdits] edits;
 * it beats the literal by [threshold] (or by [AutocorrectConfig.realWordMargin] when the literal is
 * a dictionary word) and beats the runner-up by [AutocorrectConfig.runnerUpMargin].
 */
class AutocorrectPolicy(val config: AutocorrectConfig = AutocorrectConfig()) {
    /**
     * Minimum score advantage over the literal for a misspelling. Starts at the configured value;
     * Phase 3 raises it when users revert corrections and lowers it when they accept them.
     */
    @Volatile var threshold: Float = config.threshold

    /** True if autocorrect must never touch input in [field]. */
    fun blocksField(field: FieldClass): Boolean =
        field == FieldClass.Email || field == FieldClass.Url

    /** True if the literal's shape rules out any correction (digits, e-mail, URL, path, caps). */
    fun protects(literal: String): Boolean =
        Text.isNonProse(literal) ||
            literal.contains("://") ||
            (!config.correctAllCaps && Casing.isAllCaps(literal))

    /** The candidate to apply, or null to keep the literal. */
    fun choose(input: AutocorrectInput): RankedCandidate? {
        if (!input.allowed || blocksField(input.field)) return null
        if (input.literal.length < config.minLiteralLength || protects(input.literal)) return null
        if (input.literalIsUserWord) return null
        // Autocorrect fires when the word is finished, so only complete words compete.
        var top: RankedCandidate? = null
        var runnerUp: RankedCandidate? = null
        for (c in input.candidates) {
            if (c.breakdown.completionChars > 0) continue
            if (top == null) top = c else if (runnerUp == null) runnerUp = c
        }
        if (top == null) return null
        if (
            top.source != CandidateSource.Dictionary && top.source != CandidateSource.UserDictionary
        ) {
            return null
        }
        if (top.breakdown.editCount > config.maxEdits) return null
        var required = if (input.literalIsDictionaryWord) config.realWordMargin else threshold
        if (input.field == FieldClass.PersonName || input.field == FieldClass.PostalAddress) {
            required += config.nameFieldExtraMargin
        }
        if (top.score - input.literalScore < required) return null
        if (runnerUp != null && top.score - runnerUp.score < config.runnerUpMargin) return null
        return top
    }
}
