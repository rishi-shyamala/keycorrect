package dev.keycorrect.feature.keyboard.prediction

import dev.keycorrect.feature.keyboard.TimeoutHandle
import dev.keycorrect.feature.keyboard.TimeoutScheduler

/**
 * The strip and the transient prompt that takes it over: "never suggest this?" after a long press.
 * [base] is the strip the suggestions built; the prompt shows on top of it ([overlaid]) until it is
 * answered, dismissed or times out. What the answers do (learning events, hiding the word) stays
 * with [PredictiveInput], which owns the gates. Main thread only.
 */
internal class StripPrompts(
    private val scheduler: TimeoutScheduler,
    private val onStrip: (StripModel) -> Unit,
) {
    /** The strip without the prompts. */
    var base = StripModel.Empty

    var neverWord: String? = null
        private set

    private var neverTimer: TimeoutHandle? = null

    /** The strip with the prompts that take it over, if any. */
    fun overlaid(): StripModel = base.withNeverSuggest(neverWord)

    /** Asks whether to never suggest [word]. */
    fun askNever(word: String) {
        neverTimer?.cancel()
        neverWord = word
        neverTimer =
            scheduler.schedule(NEVER_PROMPT_MS) {
                neverTimer = null
                if (neverWord != null) {
                    neverWord = null
                    onStrip(overlaid())
                }
            }
        onStrip(overlaid())
    }

    /** "Cancel" on the never-suggest prompt. */
    fun declineNever() {
        if (neverWord == null) return
        cancelNever()
        onStrip(overlaid())
    }

    fun cancelNever() {
        neverTimer?.cancel()
        neverTimer = null
        neverWord = null
    }

    /** Any key or tap other than the prompts' own dismisses them. */
    fun dismiss() {
        if (neverWord == null) return
        cancelNever()
        onStrip(base)
    }

    private companion object {
        const val NEVER_PROMPT_MS = 10_000L
    }
}
