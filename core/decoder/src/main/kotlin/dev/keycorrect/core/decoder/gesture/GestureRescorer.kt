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

    /**
     * Scores the first [count] shortlist entries of [decoder] and returns them best first, or null
     * when [stale] fires. [personalization] is null when the user has none.
     */
    fun rescore(
        count: Int,
        request: GestureRequest,
        personalization: Personalization?,
        stale: () -> Boolean,
    ): List<Candidate>? {
        pers = personalization
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
            val score =
                decoder.channel[i] + gc.lmWeight * (bestLm + gc.rightContextWeight * rightLm)
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

    private companion object {
        const val EXCLUDED_FLAGS = WordFlags.POSSIBLY_OFFENSIVE or WordFlags.NOT_A_WORD
    }
}
