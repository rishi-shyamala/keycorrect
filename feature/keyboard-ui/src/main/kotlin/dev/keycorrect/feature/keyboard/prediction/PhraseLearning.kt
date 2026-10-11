package dev.keycorrect.feature.keyboard.prediction

import dev.keycorrect.core.learning.PhraseOutcome

/**
 * Judges phrase chips (docs/PLAN.md §6.3 rows 18-20): whether the user followed an offered phrase
 * word by word, or kept the one they tapped. Holds the one [PhraseWatch] in flight and reports its
 * outcome through [learn]; what it needs to know about the text comes from [cache]. The editing
 * side of a tap ([PredictiveInput]) stays with the owner. Main thread only.
 */
internal class PhraseLearning(
    private val learn: LearningRecorder,
    private val cache: TextContextCache,
) {
    private var watch: PhraseWatch? = null

    /** Drops the watch without judging it. */
    fun clear() {
        watch = null
    }

    /** A phrase chip was tapped: the whole of [words] went in; it is judged at the next word. */
    fun take(words: List<String>, context: List<String>) {
        watch = PhraseWatch(words, context, taken = true, index = words.size)
    }

    /**
     * How many words of the taken phrase are still in the text (all of them when the text can't
     * tell, [known] false), or -1 if no taken phrase is pending.
     */
    fun keptNow(known: Boolean): Int {
        val w = watch
        return when {
            w == null || !w.taken -> -1
            known -> keptWords(w.words)
            else -> w.words.size
        }
    }

    /**
     * [x], the next word after a phrase chip was offered or tapped, is compared with the phrase. A
     * phrase that ran to its end, or stopped matching, is reported; one still matching goes on.
     * [phraseKept], [offered] and [context] are what was true when [x] was finished (see
     * [PredictiveInput]).
     */
    fun advance(
        x: String,
        phraseKept: Int,
        offered: List<String>,
        context: List<String>,
        startWatch: Boolean,
    ) {
        val w = watch
        if (w != null) {
            watch = null
            val matched = if (w.taken) phraseKept else w.index
            if (matched < 0 || matched >= w.words.size) {
                emit(w, w.words.size, null)
            } else if (sameWord(x, w.words[matched])) {
                if (matched + 1 >= w.words.size) {
                    emit(w, w.words.size, null)
                } else {
                    watch = PhraseWatch(w.words, w.context, false, matched + 1)
                }
                return
            } else {
                emit(w, matched, x)
            }
        }
        if (startWatch && offered.size >= 2 && sameWord(x, offered[0])) {
            watch = PhraseWatch(offered, context, false, 1)
        }
    }

    /**
     * A tapped phrase and the field is left, or the cursor moved, before another word: judge it
     * now.
     */
    fun judgeTaken() {
        val w = watch ?: return
        watch = null
        if (!w.taken || !learn.open) return
        val kept = if (cache.beforeReadable) keptWords(w.words) else w.words.size
        emit(w, kept, null)
    }

    private fun emit(w: PhraseWatch, accepted: Int, divergedWith: String?) {
        learn.emit(
            PhraseOutcome(
                learn.stamp(),
                learn.profile,
                w.context,
                w.words,
                accepted,
                divergedWith,
            )
        )
    }

    /** How many leading words of [words] the text before the cursor still ends with. */
    private fun keptWords(words: List<String>): Int {
        val tail = alnum(cache.before.takeLast(words.sumOf { it.length + 1 } + 16))
        for (k in words.size downTo 1) {
            if (tail.endsWith(alnum(words.take(k).joinToString("")))) return k
        }
        return 0
    }

    private fun alnum(s: CharSequence): String =
        buildString(s.length) { s.forEach { if (it.isLetterOrDigit()) append(it.lowercaseChar()) } }
}
