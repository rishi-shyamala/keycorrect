package dev.keycorrect.tools.eval

import dev.keycorrect.core.learning.RevertKind
import java.util.Random

/**
 * How the simulated user reacts to what the keyboard did with one word (learn mode). Every
 * probability is a plain constant so the model is easy to reason about and to test (0 and 1 make it
 * deterministic).
 */
data class ReactionConfig(
    /** A wrong autocorrection is noticed (and undone) with this probability. */
    val notice: Float = 0.95f,
    /** Of the undone correct-literal corrections, the share undone by one backspace. */
    val immediateBackspaceShare: Float = 0.7f,
    /** The target is in the strip but was not applied: the user taps it with this probability. */
    val tapProbability: Float = 0.6f,
    /** Not tapped: of the rest, the share who delete the word and type it again correctly. */
    val retypeAfterIgnoredStrip: Float = 0.5f,
    /** A typo with no help from the strip is noticed and retyped with this probability. */
    val retypeUnaidedTypo: Float = 0.3f,
)

/** What the keyboard did with a word, as far as the user can tell. */
class Situation(
    /** The word the user meant. */
    val target: String,
    /** What the touches produced. */
    val literal: String,
    /** The suggestion strip (up to 3, never the literal), best first. */
    val strip: List<String>,
    /** The correction applied on space, or null. */
    val autocorrect: String?,
    /** The near-tied alternative to [autocorrect], or null. */
    val runnerUp: String?,
) {
    val fired: Boolean
        get() = autocorrect != null && autocorrect != literal

    val literalRight: Boolean
        get() = literal.equals(target, ignoreCase = true)

    /** Slot of the target in the strip, or -1. */
    val targetRank: Int
        get() = strip.indexOfFirst { it.equals(target, ignoreCase = true) }
}

/** The user's response. [finalText] is what ends up in the text field. */
sealed interface Reaction {
    val finalText: String

    /** Nothing done: the autocorrection or the typed word stands. */
    data class Accept(override val finalText: String) : Reaction

    /** A correct literal was changed; the user restored it ([kind] says how). */
    data class Revert(val kind: RevertKind, override val finalText: String) : Reaction

    /** A wrong correction was undone and a strip suggestion (the target) tapped instead. */
    data class Switch(val rank: Int, override val finalText: String) : Reaction

    /** A wrong correction was deleted and the target typed again. */
    data class EditLater(override val finalText: String) : Reaction

    /** The user tapped the target in the strip instead of leaving the typo. */
    data class Tap(val rank: Int, override val finalText: String) : Reaction

    /** The typo was deleted and the target typed again. */
    data class Retype(override val finalText: String) : Reaction
}

/**
 * The reaction model (docs/EVAL.md, "Learning"):
 *
 * * autocorrect produced the target: accepted;
 * * autocorrect changed a correctly typed word: noticed with probability `notice`, then undone: one
 *   backspace (`immediateBackspaceShare`, else delete and retype the literal);
 * * autocorrect produced something else for a typo: noticed with probability `notice`; if the
 *   target is in the strip (or was the tie runner-up) the user taps it, otherwise deletes the word
 *   and types it again;
 * * no autocorrection and the literal is wrong: if the target is in the strip, tapped with
 *   probability `tapProbability`, else retyped with probability `retypeAfterIgnoredStrip`, else
 *   left; with no help from the strip the typo is retyped with probability `retypeUnaidedTypo`.
 *
 * Four uniform draws are taken per word whatever the branch, so two arms with the same seed consume
 * identical randomness.
 */
class ReactionModel(val config: ReactionConfig = ReactionConfig()) {
    fun react(s: Situation, rng: Random): Reaction {
        val uNotice = rng.nextFloat()
        val uKind = rng.nextFloat()
        val uTap = rng.nextFloat()
        val uRetype = rng.nextFloat()
        if (s.fired) {
            val corrected = s.autocorrect!!
            if (corrected.equals(s.target, ignoreCase = true)) return Reaction.Accept(corrected)
            if (uNotice >= config.notice) return Reaction.Accept(corrected)
            if (s.literalRight) {
                val kind =
                    if (uKind < config.immediateBackspaceShare) RevertKind.ImmediateBackspace
                    else RevertKind.DeletedAndRetyped
                return Reaction.Revert(kind, s.literal)
            }
            val rank = s.targetRank
            return when {
                rank >= 0 -> Reaction.Switch(rank, s.target)
                s.runnerUp?.equals(s.target, ignoreCase = true) == true ->
                    Reaction.Switch(minOf(1, s.strip.size), s.target)
                else -> Reaction.EditLater(s.target)
            }
        }
        if (s.literalRight) return Reaction.Accept(s.literal)
        val rank = s.targetRank
        if (rank >= 0) {
            return when {
                uTap < config.tapProbability -> Reaction.Tap(rank, s.target)
                uRetype < config.retypeAfterIgnoredStrip -> Reaction.Retype(s.target)
                else -> Reaction.Accept(s.literal)
            }
        }
        return if (uRetype < config.retypeUnaidedTypo) Reaction.Retype(s.target)
        else Reaction.Accept(s.literal)
    }
}
