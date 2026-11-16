package dev.keycorrect.feature.keyboard.prediction

import dev.keycorrect.core.engineapi.Candidate
import dev.keycorrect.core.engineapi.PredictResult

/**
 * Words the user said never to suggest, hidden at once; the model takes over from there. Holds the
 * most recent [MAX_BLOCKED], oldest dropped first. Main-thread only.
 */
internal class BlockedSuggestions {
    private val words = ArrayDeque<String>()

    fun add(word: String) {
        words.addLast(word)
        while (words.size > MAX_BLOCKED) words.removeFirst()
    }

    fun clear() = words.clear()

    /** Hides what the user said never to suggest, until the model takes over. */
    fun filter(result: PredictResult): PredictResult {
        if (words.isEmpty()) return result
        fun blocked(c: Candidate?) = c != null && words.any { sameWord(it, c.text) }
        with(result) {
            if (
                candidates.none(::blocked) &&
                    !blocked(autocorrect) &&
                    !blocked(phrase) &&
                    !blocked(autocorrectRunnerUp)
            ) {
                return result
            }
            return copy(
                candidates = candidates.filterNot(::blocked),
                autocorrect = autocorrect?.takeUnless(::blocked),
                phrase = phrase?.takeUnless(::blocked),
                autocorrectRunnerUp = autocorrectRunnerUp?.takeUnless(::blocked),
            )
        }
    }

    private companion object {
        const val MAX_BLOCKED = 32
    }
}
