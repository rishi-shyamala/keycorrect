package dev.keycorrect.feature.keyboard.prediction

import dev.keycorrect.core.engineapi.PredictResult
import java.util.Locale

/** How a swiped word is cased: the shift state when the finger lifts (docs/PHASE5.md). */
internal enum class SwipeCase {
    /** The decoder's casing. */
    None,

    /** Shift once, or auto-capitalisation at a sentence start: Capitalised. */
    First,

    /** Caps lock: ALL CAPS. */
    All,
}

internal fun String.withSwipeCase(case: SwipeCase): String =
    when (case) {
        SwipeCase.None -> this
        SwipeCase.First -> replaceFirstChar { it.titlecase(Locale.ROOT) }
        SwipeCase.All -> uppercase(Locale.ROOT)
    }

/** The words of [result] a swipe offers, best first: cased, without blanks or duplicates. */
internal fun swipeWords(result: PredictResult, case: SwipeCase): List<String> =
    result.candidates
        .mapNotNullTo(ArrayList(result.candidates.size)) {
            it.text.takeIf { text -> text.isNotEmpty() }?.withSwipeCase(case)
        }
        .distinct()

/**
 * The swiped word standing at the cursor (P5-1, P5-2): [words] are the alternatives, best first,
 * [current] the one in the text. [addedSpace] is whether the swipe put a space in front of it.
 * Lives until the next action of any kind; see `PredictiveInput.swipe`.
 */
internal class SwipedWord(val words: List<String>, var current: Int, val addedSpace: Boolean) {
    val text: String
        get() = words[current]
}
