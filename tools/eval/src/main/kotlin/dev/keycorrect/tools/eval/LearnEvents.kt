package dev.keycorrect.tools.eval

import dev.keycorrect.core.engineapi.FieldClass
import dev.keycorrect.core.learning.AutocorrectReverted
import dev.keycorrect.core.learning.CommitMethod
import dev.keycorrect.core.learning.DeviceClass
import dev.keycorrect.core.learning.InputProfile
import dev.keycorrect.core.learning.LearningEvent
import dev.keycorrect.core.learning.RevertKind
import dev.keycorrect.core.learning.ShownSuggestion
import dev.keycorrect.core.learning.TouchSample
import dev.keycorrect.core.learning.WordCommitted
import dev.keycorrect.core.learning.WordReplaced

/** Everything the event builder needs to know about one finished word. */
class WordRecord(
    /** Time of the commit (space), in simulated milliseconds. */
    val timeMillis: Long,
    val sessionId: Long,
    /** Last (up to) two committed words before this one, apostrophes folded. */
    val context: List<String>,
    val sentenceInitial: Boolean,
    val typed: TypedWord,
    val target: String,
    /** Suggestion strip after every key; the last entry is the strip at the end of the word. */
    val strips: List<List<String>>,
    val autocorrect: String?,
    val runnerUp: String?,
    val reaction: Reaction,
)

/**
 * Builds the [LearningEvent]s the keyboard would emit for a word, from the engine's answer and the
 * simulated user's [Reaction] (docs/PLAN.md §6.2 and §6.3):
 *
 * * a typed word: `WordCommitted(Typed)`; an applied autocorrection: `WordCommitted(Autocorrected,
 *   tieRunnerUp = runner-up)`; a tapped suggestion: `WordCommitted(SuggestionPicked, pickedRank)`;
 * * an undone autocorrection adds `AutocorrectReverted` after the commit (the commit is emitted
 *   when the correction is applied, the revert follows a moment later);
 * * a tapped replacement after an undone correction adds its own `WordCommitted(SuggestionPicked)`;
 * * a typo deleted and typed again adds `WordReplaced(original -> target)` after a `Typed` commit.
 *
 * Touches are in key units relative to the centre of the key that registered each tap. `shown` is
 * the final strip with the visibility the user had of each entry (see [shown]).
 */
class EventBuilder(
    val profile: InputProfile = DEFAULT_PROFILE,
    private val keyboard: EvalKeyboard,
    private val isKnown: (String) -> Boolean,
    private val tapIntervalMs: Long = 150L,
    private val dwellMs: Long = 200L,
) {
    fun build(w: WordRecord): List<LearningEvent> {
        val literal = w.typed.text
        val shown = shown(w.strips, extra = w.runnerUp)
        val touches = touches(w.typed)
        val t = w.timeMillis

        fun commit(
            method: CommitMethod,
            committed: String,
            pickedRank: Int? = null,
            tie: String? = null,
        ) =
            WordCommitted(
                timeMillis = t,
                profile = profile,
                context = w.context,
                typed = literal,
                committed = committed,
                method = method,
                pickedRank =
                    if (method == CommitMethod.SuggestionPicked) {
                        shown.firstOrNull { it.text.equals(committed, ignoreCase = true) }?.rank
                            ?: pickedRank
                    } else {
                        pickedRank
                    },
                shown = shown,
                touches = touches,
                sentenceInitial = w.sentenceInitial,
                committedIsKnown = isKnown(committed),
                typedIsKnown = isKnown(literal),
                tieRunnerUp = tie,
                sessionId = w.sessionId,
            )

        fun revert(corrected: String, kind: RevertKind, replacement: String, after: Long) =
            AutocorrectReverted(
                t + after,
                profile,
                w.context,
                literal,
                corrected,
                kind,
                replacement,
            )

        val corrected = w.autocorrect?.takeIf { it != literal }
        return when (val r = w.reaction) {
            is Reaction.Accept ->
                if (corrected != null) {
                    listOf(commit(CommitMethod.Autocorrected, corrected, tie = w.runnerUp))
                } else {
                    listOf(commit(CommitMethod.Typed, literal))
                }
            is Reaction.Revert ->
                listOf(
                    commit(CommitMethod.Autocorrected, corrected!!, tie = w.runnerUp),
                    revert(
                        corrected,
                        r.kind,
                        literal,
                        if (r.kind == RevertKind.ImmediateBackspace) 400L else 2_500L,
                    ),
                )
            is Reaction.Switch ->
                listOf(
                    commit(CommitMethod.Autocorrected, corrected!!, tie = w.runnerUp),
                    revert(corrected, RevertKind.SwitchedToSuggestion, r.finalText, 800L),
                    commit(CommitMethod.SuggestionPicked, r.finalText, r.rank).let {
                        it.copy(timeMillis = t + 800L)
                    },
                )
            is Reaction.EditLater ->
                listOf(
                    commit(CommitMethod.Autocorrected, corrected!!, tie = w.runnerUp),
                    revert(corrected, RevertKind.EditedLater, r.finalText, 2_500L),
                )
            is Reaction.Tap -> listOf(commit(CommitMethod.SuggestionPicked, r.finalText, r.rank))
            is Reaction.Retype ->
                listOf(
                    commit(CommitMethod.Typed, literal),
                    WordReplaced(
                        timeMillis = t + 2_000L,
                        profile = profile,
                        context = w.context,
                        original = literal,
                        replacement = r.finalText,
                        originalWasPicked = false,
                        millisAfterCommit = 2_000L,
                    ),
                )
        }
    }

    /** One sample per typed char: its offset from the centre of the key that registered it. */
    fun touches(typed: TypedWord): List<TouchSample?> =
        typed.input.touches.mapIndexed { i, p ->
            val key = if (p == null) null else keyboard.key(typed.text[i])
            if (p == null || key == null) {
                null
            } else {
                TouchSample(
                    key.char.lowercaseChar(),
                    (p.x - key.centerX) / key.width,
                    (p.y - key.centerY) / key.height,
                )
            }
        }

    /**
     * What the user saw: for each suggestion of the final strip (the last of [strips]) the slot and
     * when it appeared, taken as the start of its unbroken run on the strip up to the end of the
     * word. It then stayed visible for the remaining taps plus [dwellMs] before the space. [extra]
     * (the tie runner-up) is added at the end of the list when it is missing: the keyboard offers
     * it for a switch.
     */
    fun shown(strips: List<List<String>>, extra: String? = null): List<ShownSuggestion> {
        if (strips.isEmpty()) return emptyList()
        val n = strips.size
        val out = ArrayList<ShownSuggestion>()
        for ((rank, text) in strips.last().withIndex()) {
            var j = n - 1
            while (j > 0 && strips[j - 1].any { it.equals(text, ignoreCase = true) }) j--
            out.add(ShownSuggestion(text, rank, j + 1, (n - (j + 1)) * tapIntervalMs + dwellMs))
        }
        if (
            extra != null && out.none { it.text.equals(extra, ignoreCase = true) } && out.size < 3
        ) {
            out.add(ShownSuggestion(extra, out.size, n, dwellMs))
        }
        return out
    }

    companion object {
        val DEFAULT_PROFILE = InputProfile(DeviceClass.Phone, false, "qwerty@54dp", FieldClass.Text)
    }
}
