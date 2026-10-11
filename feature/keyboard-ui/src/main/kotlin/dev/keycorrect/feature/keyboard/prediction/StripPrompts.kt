package dev.keycorrect.feature.keyboard.prediction

import dev.keycorrect.feature.keyboard.TimeoutHandle
import dev.keycorrect.feature.keyboard.TimeoutScheduler

/**
 * The strip and the transient prompts that take it over: "add word to dictionary" after a literal
 * is kept, and "never suggest this?" after a long press. [base] is the strip the suggestions built;
 * each prompt shows on top of it ([overlaid]) until it is answered, dismissed or times out. What
 * the answers do (the dictionary, learning events, hiding the word) stays with [PredictiveInput],
 * which owns the gates. Main thread only.
 */
internal class StripPrompts(
    private val scheduler: TimeoutScheduler,
    private val onStrip: (StripModel) -> Unit,
) {
    /** The strip without the prompts. */
    var base = StripModel.Empty

    var addWord: String? = null
        private set

    private var addWordTimer: TimeoutHandle? = null

    var neverWord: String? = null
        private set

    private var neverTimer: TimeoutHandle? = null

    /** The strip with the prompts that take it over, if any. */
    fun overlaid(): StripModel = base.withAddWord(addWord).withNeverSuggest(neverWord)

    fun showAddWord(word: String) {
        addWordTimer?.cancel()
        addWord = word
        addWordTimer =
            scheduler.schedule(ADD_WORD_PROMPT_MS) {
                addWordTimer = null
                if (addWord != null) {
                    addWord = null
                    onStrip(overlaid())
                }
            }
    }

    /** Asks whether to never suggest [word]; replaces an add-word prompt. */
    fun askNever(word: String) {
        addWordTimer?.cancel()
        addWordTimer = null
        addWord = null
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

    fun cancelAddWord() {
        addWordTimer?.cancel()
        addWordTimer = null
        addWord = null
    }

    fun cancelNever() {
        neverTimer?.cancel()
        neverTimer = null
        neverWord = null
    }

    /** Any key or tap other than the prompts' own dismisses them. */
    fun dismiss() {
        if (addWord == null && neverWord == null) return
        cancelAddWord()
        cancelNever()
        onStrip(base)
    }

    private companion object {
        const val ADD_WORD_PROMPT_MS = 3_000L
        const val NEVER_PROMPT_MS = 10_000L
    }
}
