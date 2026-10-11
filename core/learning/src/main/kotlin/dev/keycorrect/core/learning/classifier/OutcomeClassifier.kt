package dev.keycorrect.core.learning.classifier

import dev.keycorrect.core.engineapi.FieldClass
import dev.keycorrect.core.learning.AutocorrectReverted
import dev.keycorrect.core.learning.CommitMethod
import dev.keycorrect.core.learning.LearningEvent
import dev.keycorrect.core.learning.LearningUpdate
import dev.keycorrect.core.learning.LearningUpdate.AddUserWord
import dev.keycorrect.core.learning.LearningUpdate.AutocorrectOutcome
import dev.keycorrect.core.learning.LearningUpdate.BlockWord
import dev.keycorrect.core.learning.LearningUpdate.CasingEvidence
import dev.keycorrect.core.learning.LearningUpdate.CorrectionEvidence
import dev.keycorrect.core.learning.LearningUpdate.NewWordEvidence
import dev.keycorrect.core.learning.LearningUpdate.PhraseFeedback
import dev.keycorrect.core.learning.LearningUpdate.Preference
import dev.keycorrect.core.learning.LearningUpdate.RevertKept
import dev.keycorrect.core.learning.LearningUpdate.SuppressCorrection
import dev.keycorrect.core.learning.LearningUpdate.TouchEvidence
import dev.keycorrect.core.learning.LearningUpdate.WordUsed
import dev.keycorrect.core.learning.PhraseOutcome
import dev.keycorrect.core.learning.RevertKind
import dev.keycorrect.core.learning.ShownSuggestion
import dev.keycorrect.core.learning.TouchSample
import dev.keycorrect.core.learning.UserAction
import dev.keycorrect.core.learning.WordCommitted
import dev.keycorrect.core.learning.WordReplaced
import dev.keycorrect.core.learning.contextBucket
import kotlin.math.ceil

/**
 * Turns one observed [LearningEvent] into model-agnostic [LearningUpdate]s following the outcome
 * table in docs/PLAN.md §6.3. Pure and stateless: everything it needs (picked-ness, timing,
 * visibility, known-ness, sessions) is carried by the event, so the same input always yields the
 * same output. Promotion rules ("≥ 2 commits in ≥ 2 sessions") belong to the model; the classifier
 * only forwards the session id.
 *
 * Post-processing, in order: voice scaling (row 16), per-event negative cap (row 14), and a
 * sanitiser that drops anything non-finite.
 */
class OutcomeClassifier(private val config: ClassifierConfig = ClassifierConfig()) {

    fun classify(event: LearningEvent): List<LearningUpdate> {
        val raw =
            when (event) {
                is WordCommitted -> wordCommitted(event)
                is AutocorrectReverted -> autocorrectReverted(event)
                is WordReplaced -> wordReplaced(event)
                is PhraseOutcome -> phraseOutcome(event)
                is UserAction -> userAction(event)
            }
        val voiced =
            if (event is WordCommitted && event.method == CommitMethod.Voice) {
                raw.map { it.scaled(config.voiceScale) }
            } else {
                raw
            }
        val field = event.profile.field
        return capNegatives(voiced)
            .filter { it.isFinite() }
            .map { if (it is Preference) it.copy(field = field) else it }
    }

    // ---- WordCommitted ---------------------------------------------------------------------

    private fun wordCommitted(e: WordCommitted): List<LearningUpdate> {
        if (!isTrainableWord(e.committed)) return emptyList() // row 15 (defensive)
        return when (e.method) {
            CommitMethod.Typed -> typed(e)
            CommitMethod.Autocorrected -> autocorrected(e)
            CommitMethod.SuggestionPicked -> suggestionPicked(e)
            CommitMethod.LiteralPicked -> literalPicked(e)
            // Row 15/predictions: a single tapped prediction is user-chosen but self-reinforcing.
            CommitMethod.PredictionPicked ->
                listOf(WordUsed(e.context, e.committed, config.predictionPickedWeight))
            // Row 12: gesture-specific memory is Phase 5; for now just word use. No touches.
            CommitMethod.Gesture -> wordEvidence(e, config.typedWordWeight, 1f, typoCheck = true)
            // Row 16: evidence like typing; the 0.3x scaling happens in classify().
            CommitMethod.Voice -> wordEvidence(e, config.typedWordWeight, 1f, typoCheck = true)
        }
    }

    /** Rows 2, 3, 4, 13: user typed [WordCommitted.committed] and finished it themselves. */
    private fun typed(e: WordCommitted): List<LearningUpdate> {
        val out = ArrayList<LearningUpdate>()
        // Row 4: an unknown typed word with a close known neighbour on the strip is probably a
        // typo the user did not bother to fix; it is not counted as a new word.
        val likelyTypo = isLikelyTypo(e)
        out += wordEvidence(e, config.typedWordWeight, 1f, typoCheck = true)

        val match = e.shown.firstOrNull { sameWord(it.text, e.committed) }
        if (match != null) {
            // Row 2: implicit accept; weight by whether the user could have seen it.
            val w = config.implicitAcceptPreferenceWeight * visibility(match, e.typed.length)
            for (other in e.shown) {
                if (other === match || sameWord(other.text, e.committed)) continue
                out += Preference(e.typed, e.context, e.committed, other.text, w)
            }
        } else if (!likelyTypo) {
            // Row 3: typed something else; one capped, visibility-weighted pair against the top.
            val top = e.shown.minByOrNull { it.rank }
            if (top != null) {
                val w = config.ignoredSuggestionWeight * visibility(top, e.typed.length)
                out += Preference(e.typed, e.context, e.committed, top.text, w)
            }
        }
        out += touchEvidence(e)
        out += casing(e)
        return out
    }

    /** Rows 5 and 22 (kept): the autocorrection stood. */
    private fun autocorrected(e: WordCommitted): List<LearningUpdate> {
        if (e.typed.isBlank()) return emptyList()
        val out = ArrayList<LearningUpdate>()
        if (!sameWord(e.typed, e.committed)) {
            val delta =
                if (e.tieRunnerUp != null) config.tieKeptDelta else config.autocorrectKeptDelta
            out += CorrectionEvidence(e.typed, e.committed, bucket(e), delta)
        }
        out += WordUsed(e.context, e.committed, config.typedWordWeight)
        out += AutocorrectOutcome(reverted = false)
        out += touchEvidence(e) // only the safely aligned positions; see alignedTouches
        return out
    }

    /** Rows 1 and 13. */
    private fun suggestionPicked(e: WordCommitted): List<LearningUpdate> {
        val out = ArrayList<LearningUpdate>()
        out += WordUsed(e.context, e.committed, config.suggestionPickedWeight)
        val rank = e.pickedRank ?: e.shown.firstOrNull { sameWord(it.text, e.committed) }?.rank
        if (rank != null) {
            for (above in e.shown.filter { it.rank < rank && !sameWord(it.text, e.committed) }) {
                out +=
                    Preference(
                        e.typed,
                        e.context,
                        e.committed,
                        above.text,
                        config.pickedPreferenceWeight,
                    )
            }
        }
        // Extension of row 1: tapping a correction (not a completion) also teaches the pair.
        if (isCorrectionLike(e.typed, e.committed)) {
            out += CorrectionEvidence(e.typed, e.committed, bucket(e), config.pickedCorrectionDelta)
        }
        out += casing(e)
        return out
    }

    /**
     * Row 8. Decision: tapping the literal of an *unknown* word does not add it to the dictionary
     * (the keyboard shows an add-word chip for that); it records strong [NewWordEvidence] only.
     */
    private fun literalPicked(e: WordCommitted): List<LearningUpdate> {
        val out = ArrayList<LearningUpdate>()
        out += wordEvidence(e, config.literalPickedWeight, 1f, config.literalNewWordEvidenceWeight)
        val declined =
            e.shown
                .sortedBy { it.rank }
                .firstOrNull {
                    !sameWord(it.text, e.committed) && isCorrectionLike(e.typed, it.text)
                }
        if (declined != null) {
            out += SuppressCorrection(e.typed, declined.text)
            out += CorrectionEvidence(e.typed, declined.text, bucket(e), -config.literalRejectDelta)
        }
        out += touchEvidence(e)
        out += casing(e)
        return out
    }

    /**
     * WordUsed (+ NewWordEvidence for unknown words, row 4). Decision: unknown words emit both,
     * with WordUsed scaled by [ClassifierConfig.unknownWordUsedScale] so the n-gram cache can learn
     * a genuinely new word quickly without fully trusting something that may be a typo. With
     * [typoCheck], an unknown word that looks like an unfixed typo of a shown known word emits no
     * NewWordEvidence and only a minimal WordUsed ([ClassifierConfig.likelyTypoScale]).
     */
    private fun wordEvidence(
        e: WordCommitted,
        usedWeight: Float,
        scale: Float,
        newWordWeight: Float = config.newWordEvidenceWeight,
        typoCheck: Boolean = false,
    ): List<LearningUpdate> =
        when {
            e.committedIsKnown -> listOf(WordUsed(e.context, e.committed, usedWeight * scale))
            typoCheck && isLikelyTypo(e) ->
                listOf(
                    WordUsed(
                        e.context,
                        e.committed,
                        usedWeight * config.unknownWordUsedScale * config.likelyTypoScale * scale,
                    )
                )
            else ->
                listOf(
                    NewWordEvidence(e.committed, e.sessionId, newWordWeight * scale),
                    WordUsed(
                        e.context,
                        e.committed,
                        usedWeight * config.unknownWordUsedScale * scale,
                    ),
                )
        }

    /**
     * An unknown committed word with a shown suggestion (a known word) within edit distance
     * [ClassifierConfig.typoMaxEditsShort] (length <= [ClassifierConfig.typoShortLength]) or
     * [ClassifierConfig.typoMaxEditsLong]: most likely a typo the user didn't bother to fix, not a
     * new word. The shown list also carries the user's own words, so "Damiabo" next to a learned
     * "Damiano" is covered too.
     */
    private fun isLikelyTypo(e: WordCommitted): Boolean {
        if (e.committedIsKnown) return false
        if (e.committed.length < config.newWordMinLength) return true // a stray tap, not a word
        val c = fold(e.committed)
        val limit =
            if (c.length <= config.typoShortLength) config.typoMaxEditsShort
            else config.typoMaxEditsLong
        return e.shown.any { editDistance(fold(it.text), c) in 1..limit }
    }

    /** 1 when the user plausibly saw [s] in time to act on it, else the low-visibility scale. */
    private fun visibility(s: ShownSuggestion, typedLength: Int): Float {
        val seen =
            s.visibleMillis >= config.fullVisibilityMillis &&
                s.shownAtChars <= typedLength - config.fullVisibilityCharsBeforeEnd
        return if (seen) 1f else config.lowVisibilityScale
    }

    // ---- touches (§6.5) ----------------------------------------------------------------------

    /**
     * [TouchSample]s are relative to the INTENDED key (the committed word's char at that position),
     * and the keyboard only emits them for substitution-aligned words (same length, substitutions
     * only, |dx| or |dy| > 1.2 dropped). Every non-null sample of such a word is therefore usable,
     * including taps that landed on a neighbouring key (autocorrected or picked words): using only
     * taps that registered on the intended key would learn offsets shrunk toward zero. Unknown
     * committed words are skipped (typo risk: the intended key is unknown).
     */
    private fun touchEvidence(e: WordCommitted): List<LearningUpdate> {
        if (e.method !in TOUCH_METHODS || e.typed.isEmpty() || e.touches.isEmpty())
            return emptyList()
        if (!e.committedIsKnown) return emptyList()
        val aligned = alignedTouches(e.typed, e.committed, e.touches)
        return if (aligned.isEmpty()) emptyList()
        else listOf(TouchEvidence(e.profile.touchKey, aligned))
    }

    private fun alignedTouches(
        typed: String,
        committed: String,
        touches: List<TouchSample?>,
    ): List<TouchSample> {
        if (typed.length != committed.length || touches.size != typed.length) return emptyList()
        var mismatches = 0
        for (i in typed.indices) if (typed[i].lowercaseChar() != committed[i].lowercaseChar())
            mismatches++
        if (mismatches > config.alignmentMaxMismatches) return emptyList()
        if (isTransposition(typed, committed)) return emptyList()
        val out = ArrayList<TouchSample>()
        for (i in typed.indices) {
            val t = touches[i] ?: continue
            if (t.char.lowercaseChar() != committed[i].lowercaseChar()) continue
            out += t
        }
        return out
    }

    /** Adjacent letters swapped: the taps belong to the other key, not to an offset. */
    private fun isTransposition(typed: String, committed: String): Boolean {
        for (i in 0 until typed.length - 1) {
            val a = typed[i].lowercaseChar()
            val b = typed[i + 1].lowercaseChar()
            if (
                a != committed[i].lowercaseChar() &&
                    a == committed[i + 1].lowercaseChar() &&
                    b == committed[i].lowercaseChar()
            ) {
                return true
            }
        }
        return false
    }

    // ---- casing (row 13) ---------------------------------------------------------------------

    /**
     * Non-trivial casing = not all lowercase, not an ordinary sentence-initial capital, not ALL
     * CAPS shouting, not a lone capital. Only user-typed commits in text-like fields count.
     */
    private fun casing(e: WordCommitted): List<LearningUpdate> {
        if (e.profile.field == FieldClass.Email || e.profile.field == FieldClass.Url)
            return emptyList()
        val w = e.committed
        if (w.none { it.isLetter() } || w.length < 2) return emptyList()
        val letters = w.filter { it.isLetter() }
        if (letters.all { it.isLowerCase() }) return emptyList()
        if (letters.length > 1 && letters.all { it.isUpperCase() }) return emptyList()
        val onlyFirstUpper = w[0].isUpperCase() && w.drop(1).none { it.isUpperCase() }
        if (e.sentenceInitial && onlyFirstUpper) return emptyList()
        return listOf(CasingEvidence(w))
    }

    // ---- reverts (rows 6, 7, 22, 23)
    // -------------------------------------------------------------

    private fun autocorrectReverted(e: AutocorrectReverted): List<LearningUpdate> {
        if (e.typed.isBlank() || e.corrected.isBlank() || sameWord(e.typed, e.corrected)) {
            return emptyList()
        }
        val b = contextBucket(e.context, e.profile.field.name)
        val kind =
            if (e.kind == RevertKind.EditedLater && sameWord(e.replacement, e.typed)) {
                RevertKind.DeletedAndRetyped // row 7: X = T behaves exactly like row 6
            } else {
                e.kind
            }
        val out = ArrayList<LearningUpdate>()
        var countsForThreshold: Boolean
        when (kind) {
            RevertKind.ImmediateBackspace,
            RevertKind.DeletedAndRetyped -> {
                // Revised 2026-10-10: the restored literal is a "keep". Whether it is added to the
                // user dictionary / the pair suppressed depends on how many keeps there have been
                // (the model's runtime thresholds), so only the evidence is emitted here.
                countsForThreshold = true
                out += RevertKept(e.typed, e.corrected)
                out += CorrectionEvidence(e.typed, e.corrected, b, -config.revertDelta)
                out += WordUsed(e.context, e.typed, config.typedWordWeight)
            }
            RevertKind.EditedLater -> {
                if (e.replacement.isBlank() || sameWord(e.replacement, e.corrected))
                    return emptyList()
                countsForThreshold = config.rankingRevertsCount
                out += CorrectionEvidence(e.typed, e.corrected, b, -config.editedLaterNegativeDelta)
                out +=
                    CorrectionEvidence(e.typed, e.replacement, b, config.editedLaterPositiveDelta)
                out += WordUsed(e.context, e.replacement, config.typedWordWeight)
            }
            RevertKind.SwitchedToSuggestion -> {
                // The literal wasn't what they wanted: no implicit add, no suppression. The
                // replacement is not given WordUsed here; its own WordCommitted (picked) does that.
                if (e.replacement.isBlank() || sameWord(e.replacement, e.corrected))
                    return emptyList()
                countsForThreshold = config.rankingRevertsCount
                out += CorrectionEvidence(e.typed, e.corrected, b, -config.switchNegativeDelta)
                out += CorrectionEvidence(e.typed, e.replacement, b, config.switchPositiveDelta)
                out +=
                    Preference(
                        e.typed,
                        e.context,
                        e.replacement,
                        e.corrected,
                        config.switchPreferenceWeight,
                    )
            }
        }
        if (countsForThreshold) out += AutocorrectOutcome(reverted = true)
        return out
    }

    // ---- later replacement (rows 9, 10)
    // ----------------------------------------------------------

    private fun wordReplaced(e: WordReplaced): List<LearningUpdate> {
        if (e.original.isBlank()) return emptyList()
        val out = ArrayList<LearningUpdate>()
        if (e.originalWasPicked && e.millisAfterCommit in 0..config.misTapWindowMillis) {
            // Row 9: mis-tap. Take back the accept; weak, no correction pair.
            out +=
                WordUsed(
                    e.context,
                    e.original,
                    -config.suggestionPickedWeight * config.misTapUndoFraction,
                )
            if (isTrainableWord(e.replacement)) {
                out += WordUsed(e.context, e.replacement, config.typedWordWeight)
            }
            return out
        }
        if (!isTrainableWord(e.replacement)) return emptyList()
        val a = fold(e.original)
        val b = fold(e.replacement)
        if (a != b && a.length >= config.missedCorrectionMinLength) {
            val limit = ceil(a.length * config.missedCorrectionMaxEditRatio).toInt()
            if (editDistance(a, b) <= limit) {
                // Row 10: typo fix of a committed word → missed correction.
                out +=
                    CorrectionEvidence(
                        e.original,
                        e.replacement,
                        contextBucket(e.context, e.profile.field.name),
                        config.missedCorrectionDelta,
                    )
            }
        }
        out += WordUsed(e.context, e.replacement, config.typedWordWeight) // rewording: only this
        return out
    }

    // ---- phrases (rows 18-20)
    // ----------------------------------------------------------------------

    private fun phraseOutcome(e: PhraseOutcome): List<LearningUpdate> {
        if (e.phrase.isEmpty()) return emptyList()
        val accepted = e.acceptedWords.coerceIn(0, e.phrase.size)
        val out = ArrayList<LearningUpdate>()
        var ctx = e.context
        for (i in 0 until accepted) {
            out +=
                WordUsed(ctx.takeLast(CONTEXT_WORDS), e.phrase[i], config.phraseAcceptedWordWeight)
            ctx = ctx + e.phrase[i]
        }
        if (accepted < e.phrase.size) {
            val c = ctx.takeLast(CONTEXT_WORDS)
            val predicted = e.phrase[accepted]
            out += WordUsed(c, predicted, -config.phraseRejectedWordWeight)
            val diverged = e.divergedWith
            if (!diverged.isNullOrBlank() && isTrainableWord(diverged)) {
                out += WordUsed(c, diverged, config.typedWordWeight) // as row 3
                if (!sameWord(diverged, predicted)) {
                    out += Preference("", c, diverged, predicted, config.phrasePreferenceWeight)
                }
            }
        }
        out += PhraseFeedback(offeredWords = e.phrase.size, acceptedWords = accepted)
        return out
    }

    // ---- explicit actions (row 11)
    // -------------------------------------------------------------------

    private fun userAction(e: UserAction): List<LearningUpdate> {
        if (e.word.isBlank()) return emptyList()
        return when (e.kind) {
            UserAction.Kind.NeverSuggest -> listOf(BlockWord(e.word))
            UserAction.Kind.AddToDictionary ->
                listOf(AddUserWord(e.word, AddUserWord.Reason.Explicit))
        }
    }

    // ---- helpers
    // --------------------------------------------------------------------------------------

    /** Row 15 (defensive): single, letter-bearing, word-sized tokens only; no paste-like bulk. */
    private fun isTrainableWord(w: String): Boolean =
        w.isNotBlank() &&
            w.length <= config.maxWordLength &&
            w.none { it.isWhitespace() } &&
            !w.contains("://") &&
            w.any { it.isLetter() }

    /**
     * A plausible spelling correction of [typed] (not a prefix completion, small edit distance).
     */
    private fun isCorrectionLike(typed: String, candidate: String): Boolean {
        if (typed.isBlank() || candidate.isBlank()) return false
        val t = fold(typed)
        val c = fold(candidate)
        if (t == c || c.startsWith(t)) return false
        val limit = maxOf(1, ceil(t.length * config.missedCorrectionMaxEditRatio).toInt())
        return editDistance(t, c) <= limit
    }

    private fun sameWord(a: String, b: String) = fold(a) == fold(b)

    private fun bucket(e: WordCommitted) = contextBucket(e.context, e.profile.field.name)

    // ---- post-processing
    // ---------------------------------------------------------------------------------

    /** Row 14: scale down the "loser" side of an event when its total exceeds the cap. */
    private fun capNegatives(updates: List<LearningUpdate>): List<LearningUpdate> {
        val mass = updates.sumOf { it.negativeMass().toDouble() }.toFloat()
        val cap = config.maxNegativeMassPerEvent
        if (mass <= cap || mass <= 0f) return updates
        val factor = cap / mass
        return updates.map { it.scaleNegative(factor) }
    }

    private fun LearningUpdate.negativeMass(): Float =
        when (this) {
            is WordUsed -> if (weight < 0f) -weight else 0f
            is CorrectionEvidence -> if (delta < 0f) -delta else 0f
            is Preference -> if (weight > 0f) weight else 0f
            else -> 0f
        }

    private fun LearningUpdate.scaleNegative(factor: Float): LearningUpdate =
        when (this) {
            is WordUsed -> if (weight < 0f) copy(weight = weight * factor) else this
            is CorrectionEvidence -> if (delta < 0f) copy(delta = delta * factor) else this
            is Preference -> if (weight > 0f) copy(weight = weight * factor) else this
            else -> this
        }

    private fun LearningUpdate.scaled(f: Float): LearningUpdate =
        when (this) {
            is WordUsed -> copy(weight = weight * f)
            is NewWordEvidence -> copy(weight = weight * f)
            is CorrectionEvidence -> copy(delta = delta * f)
            is Preference -> copy(weight = weight * f)
            else -> this
        }

    private fun LearningUpdate.isFinite(): Boolean =
        when (this) {
            is WordUsed -> weight.isFinite()
            is NewWordEvidence -> weight.isFinite()
            is CorrectionEvidence -> delta.isFinite()
            is Preference -> weight.isFinite()
            is TouchEvidence -> samples.all { it.dx.isFinite() && it.dy.isFinite() }
            else -> true
        }

    private companion object {
        const val CONTEXT_WORDS = 2
        /** Methods whose typed chars were really tapped keys (row 1/5/8 and plain typing). */
        val TOUCH_METHODS =
            setOf(
                CommitMethod.Typed,
                CommitMethod.Autocorrected,
                CommitMethod.SuggestionPicked,
                CommitMethod.LiteralPicked,
            )
    }
}
