package dev.keycorrect.core.decoder.gesture

import dev.keycorrect.core.decoder.ClassicConfig
import dev.keycorrect.core.decoder.LanguageModel
import dev.keycorrect.core.decoder.Text
import dev.keycorrect.core.decoder.UserLexicon
import dev.keycorrect.core.dictionary.Dictionary
import dev.keycorrect.core.dictionary.WordFlags
import dev.keycorrect.core.engineapi.Candidate
import dev.keycorrect.core.engineapi.CandidateSource
import dev.keycorrect.core.engineapi.GestureRequest
import dev.keycorrect.core.learning.Personalization

/**
 * The second stage of swipe decoding: adds the contextual language model (with the personal n-gram
 * and the right context), user words and blocked words to the [GestureDecoder] shortlist and ranks
 * it. Driven by the engine under its lock, after it bound the language model's personalization.
 *
 * Word set: a word the dictionary flags possibly offensive or not-a-word is never suggested, even
 * when it is also a user word (on the typed path such a word is left to the trie search, which
 * skips flagged words too); such a user word is not swipeable. Blocked words are dropped,
 * [Personalization.preferredCasing] sets the surface form of dictionary words.
 */
internal class GestureRescorer(
    private val dictionary: Dictionary,
    private val config: ClassicConfig,
    private val lm: LanguageModel,
    private val lexicon: UserLexicon,
    private val decoder: GestureDecoder,
) {
    private var pers: Personalization? = null
    private var bestLm = Float.NEGATIVE_INFINITY
    private var neural: FloatArray? = null

    /** When set, [rescore] adds every candidate's score parts here (for tuning, tools/eval). */
    var trace: ((GestureFusionFeatures) -> Unit)? = null

    /**
     * Scores the first [count] shortlist entries of [decoder] and returns them best first, or null
     * when [stale] fires. [personalization] is null when the user has none. With [neuralScores]
     * (the encoder's log-likelihood of each entry) the score is the fused one of
     * [dev.keycorrect.core.decoder.NeuralSwipeConfig]; without, SHARK²'s own.
     */
    fun rescore(
        count: Int,
        request: GestureRequest,
        personalization: Personalization?,
        stale: () -> Boolean,
        neuralScores: FloatArray? = null,
    ): List<Candidate>? {
        pers = personalization
        neural = neuralScores
        lm.setContext(request.context.before)
        val right = lm.parseRightContext(request.context.after)
        val out = ArrayList<Candidate>(count)
        for (i in 0 until count) {
            if (stale()) return null
            candidate(i, right)?.let { out.add(it) }
        }
        out.sortWith(compareByDescending<Candidate> { it.score }.thenBy { it.text })
        return out
    }

    private fun candidate(i: Int, right: Int): Candidate? {
        val key = decoder.keys[i] ?: return null
        val ids = dictionary.idsForKey(key)
        val id = best(ids)
        val user = decoder.userIndex[i]
        val surface: String?
        var source = CandidateSource.Dictionary
        when {
            id >= 0 -> {
                // Dictionary casing (lowercase, "I", "Monday", ...) unless the user prefers one.
                val form = dictionary.word(id)
                surface = pers?.preferredCasing(Text.key(form)) ?: form
            }
            user >= 0 && ids.isEmpty() -> {
                surface = lexicon.surface(user).takeUnless { pers?.isBlocked(it) == true }
                source = CandidateSource.UserDictionary
                bestLm = lm.blend(config.user.wordLogProb, lexicon.surface(user))
            }
            else -> surface = null
        }
        return surface?.let {
            val gc = config.gesture
            val rightLm = if (right >= 0) lm.rightLogProb(id, right) else 0f
            val lmTerm = bestLm + gc.rightContextWeight * rightLm
            val n = neural
            val score =
                if (n == null) {
                    decoder.channel[i] + gc.lmWeight * lmTerm
                } else {
                    val nc = config.neural
                    maxOf(decoder.channel[i], -gc.maxChannelCost) +
                        nc.neuralWeight * n[i] +
                        nc.lmWeight * lmTerm +
                        nc.lengthBonus * letters(it)
                }
            trace?.invoke(
                GestureFusionFeatures(it, decoder.channel[i], n?.get(i) ?: Float.NaN, lmTerm)
            )
            Candidate(it, score, source)
        }
    }

    /** The id of [ids] with the best contextual score (left in [bestLm]), or -1. */
    private fun best(ids: IntArray): Int {
        var bestId = -1
        bestLm = Float.NEGATIVE_INFINITY
        for (id in ids) {
            val excluded = dictionary.flags(id) and EXCLUDED_FLAGS != 0
            val word = dictionary.word(id)
            if (!excluded && pers?.isBlocked(word) != true) {
                val v = lm.blend(lm.baseLogProb(id), word)
                if (v > bestLm) {
                    bestLm = v
                    bestId = id
                }
            }
        }
        return bestId
    }

    private fun letters(s: String): Int {
        var n = 0
        for (c in s) if (c.isLetter()) n++
        return n
    }

    private companion object {
        const val EXCLUDED_FLAGS = WordFlags.POSSIBLY_OFFENSIVE or WordFlags.NOT_A_WORD
    }
}

/**
 * The parts of one swipe candidate's score, for tuning the fusion weights offline (tools/eval):
 * SHARK²'s [channel], the encoder's [neural] log-likelihood (NaN without an encoder) and the
 * contextual language-model term [lm] (with the right context folded in).
 */
data class GestureFusionFeatures(
    val text: String,
    val channel: Float,
    val neural: Float,
    val lm: Float,
)
