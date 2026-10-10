package dev.keycorrect.core.decoder

import dev.keycorrect.core.engineapi.CandidateSource
import dev.keycorrect.core.engineapi.FieldClass
import kotlin.random.Random

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
    /** True at the start of a sentence or of the text (a leading capital is then expected). */
    val sentenceInitial: Boolean = true,
    /**
     * Learned shift of the autocorrect threshold (`Personalization.autocorrectThresholdDelta`):
     * added to the misspelling threshold; for a real-word literal only a positive shift counts
     * (learning may make non-word corrections bolder but never real-word ones).
     */
    val thresholdDelta: Float = 0f,
    /**
     * True for a candidate text whose (literal -> candidate) pair the user has repeatedly declined.
     * A suppressed top candidate vetoes autocorrect for the word (no silent promotion of the next
     * best); a suppressed runner-up is never picked in a tie.
     */
    val isSuppressed: (String) -> Boolean = { false },
)

/** [chosen] is the correction to apply (or null); [runnerUp] the near-tied alternative, if any. */
class AutocorrectDecision(val chosen: RankedCandidate?, val runnerUp: RankedCandidate?) {
    companion object {
        val None = AutocorrectDecision(null, null)
    }
}

/**
 * Decides whether the best suggestion may replace the typed word on space/punctuation (§6.3).
 * Deliberately conservative: a wrong autocorrect costs more than a missed one.
 *
 * A candidate is applied only if all of these hold: autocorrect is allowed and the field is not
 * e-mail/URL; the literal has at least [AutocorrectConfig.minLiteralLength] chars, no digits, `@`
 * or `/`, is not a user word and not an ALL CAPS acronym; the best complete-word candidate
 * (completions are ignored: the word is finished) needs at most [AutocorrectConfig.maxEdits] edits;
 * it beats the literal by [threshold] (or by [AutocorrectConfig.realWordMargin] when the literal is
 * a dictionary word) and, when the literal is a known word, beats the runner-up by
 * [AutocorrectConfig.runnerUpMargin].
 *
 * Tie rule (§6.3 row 22): when the literal is NOT a known word the runner-up margin does not apply,
 * so "tset" is corrected to "test" or "set" rather than left alone. All other gates still do. If
 * the top two are within [AutocorrectConfig.tieEpsilon] and both are eligible the pick is random
 * (via the `random` of [decide]); the other is reported as the runner-up whenever the top two are
 * closer than [AutocorrectConfig.nearTieMargin].
 *
 * Capitalised words in mid-sentence ([AutocorrectConfig.protectMidSentenceCapitals]) are likely
 * names or brands: an unknown one is corrected only when the sole edit is one adjacent
 * transposition, and a known one is never turned into a lowercase dictionary word.
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

    /** The candidate to apply, or null to keep the literal; exact ties go to the first. */
    fun choose(input: AutocorrectInput): RankedCandidate? = decide(input, null).chosen

    /** Like [choose] plus the near-tied runner-up; [random] breaks ties within `tieEpsilon`. */
    fun decide(input: AutocorrectInput, random: Random?): AutocorrectDecision {
        if (!input.allowed || blocksField(input.field)) return AutocorrectDecision.None
        if (input.literal.length < config.minLiteralLength || protects(input.literal)) {
            return AutocorrectDecision.None
        }
        if (input.literalIsUserWord) return AutocorrectDecision.None
        // Autocorrect fires when the word is finished, so only complete words compete.
        var top: RankedCandidate? = null
        var runnerUp: RankedCandidate? = null
        for (c in input.candidates) {
            if (c.breakdown.completionChars > 0) continue
            if (top == null) top = c else if (runnerUp == null) runnerUp = c
        }
        if (top == null || !eligible(input, top)) return AutocorrectDecision.None
        // A declined pair vetoes the word: abstain rather than promote the next-best candidate.
        if (input.isSuppressed(top.text)) return AutocorrectDecision.None
        var required =
            if (input.literalIsDictionaryWord) {
                config.realWordMargin + maxOf(input.thresholdDelta, 0f)
            } else {
                threshold + input.thresholdDelta
            }
        if (input.field == FieldClass.PersonName || input.field == FieldClass.PostalAddress) {
            required += config.nameFieldExtraMargin
        }
        if (top.score - input.literalScore < required) return AutocorrectDecision.None
        val gap = if (runnerUp != null) top.score - runnerUp.score else Float.POSITIVE_INFINITY
        val known = input.literalIsDictionaryWord || input.literalIsUserWord
        if ((known || !config.nonWordTieRule) && gap < config.runnerUpMargin) {
            return AutocorrectDecision.None
        }
        var chosen: RankedCandidate = top
        var other: RankedCandidate? = runnerUp
        if (
            random != null &&
                runnerUp != null &&
                !known &&
                gap <= config.tieEpsilon &&
                eligible(input, runnerUp) &&
                !input.isSuppressed(runnerUp.text) &&
                runnerUp.score - input.literalScore >= required &&
                random.nextBoolean()
        ) {
            chosen = runnerUp
            other = top
        }
        return AutocorrectDecision(chosen, if (gap < config.nearTieMargin) other else null)
    }

    /** Source, edit budget and capitalisation rules for applying [c] automatically. */
    private fun eligible(input: AutocorrectInput, c: RankedCandidate): Boolean {
        if (c.source != CandidateSource.Dictionary && c.source != CandidateSource.UserDictionary) {
            return false
        }
        if (c.breakdown.editCount > config.maxEdits) return false
        if (config.protectMidSentenceCapitals && !input.sentenceInitial) {
            val first = input.literal.firstOrNull { it.isLetter() }
            if (first != null && first.isUpperCase() && input.literal[0] == first) {
                val known = input.literalIsDictionaryWord || input.literalIsUserWord
                if (known) {
                    val form = c.dictionaryForm ?: c.text
                    if (form == form.lowercase()) return false
                } else if (!Text.isSingleTransposition(input.literal, c.text)) {
                    return false
                }
            }
        }
        return true
    }
}
