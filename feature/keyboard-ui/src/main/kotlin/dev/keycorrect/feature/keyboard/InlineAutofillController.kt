package dev.keycorrect.feature.keyboard

import android.content.Context
import android.util.Size
import android.view.ViewGroup
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.inline.InlineContentView
import java.util.concurrent.Executor
import java.util.function.Consumer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One inflated autofill suggestion; [pinned] ones (e.g. the password-manager icon) go last. */
data class InlineSuggestionItem(val view: InlineContentView, val pinned: Boolean)

/**
 * Turns the autofill service's [InlineSuggestionsResponse] into inflated views for the suggestion
 * strip. The IME service feeds it; [KeyboardScreen] renders [suggestions].
 *
 * Lives in `:feature:keyboard-ui` (rather than the app module) because the strip consumes it and it
 * only needs platform classes. Building the `InlineSuggestionsRequest` (theme colours, app
 * drawable) stays in the app module.
 *
 * Main-thread only: the platform delivers responses on the main thread and [executor] must be the
 * main executor.
 */
class InlineAutofillController(private val context: Context, private val executor: Executor) {
    private val _suggestions = MutableStateFlow<List<InlineSuggestionItem>>(emptyList())

    /** Views to show, in display order (pinned last). Never contains a view twice. */
    val suggestions: StateFlow<List<InlineSuggestionItem>> = _suggestions.asStateFlow()

    /** Incremented per response and per [clear] so late inflate callbacks are dropped. */
    private var generation = 0

    /** Handles a response; always returns true (we always use the suggestions we are given). */
    fun onResponse(response: InlineSuggestionsResponse): Boolean {
        val current = ++generation
        val inline = response.inlineSuggestions
        if (inline.isEmpty()) {
            _suggestions.value = emptyList()
            return true
        }
        // Inflation is asynchronous and may finish out of order, so each suggestion owns a slot
        // and the published list is rebuilt from the slots, keeping the platform's order. The
        // previous list stays on screen until the first new view arrives (no flicker).
        val slots = arrayOfNulls<InlineSuggestionItem>(inline.size)
        inline.forEachIndexed { index, suggestion ->
            val pinned = suggestion.info.isPinned
            val onView =
                Consumer<InlineContentView?> { view ->
                    if (current == generation) {
                        // A null view means inflation failed: skip this suggestion.
                        slots[index] = view?.let { InlineSuggestionItem(it, pinned) }
                        _suggestions.value = slots.filterNotNull().pinnedLast()
                    }
                }
            // WRAP_CONTENT lets each chip size itself between the spec's min and max. If the
            // platform rejects it, fall back to the spec's maximum size.
            try {
                suggestion.inflate(context, WRAP_CONTENT_SIZE, executor, onView)
            } catch (_: IllegalArgumentException) {
                suggestion.inflate(
                    context,
                    suggestion.info.inlinePresentationSpec.maxSize,
                    executor,
                    onView,
                )
            }
        }
        return true
    }

    /** Drops all suggestions (new field, keyboard hidden, feature turned off). */
    fun clear() {
        generation++
        if (_suggestions.value.isNotEmpty()) _suggestions.value = emptyList()
    }

    private companion object {
        val WRAP_CONTENT_SIZE =
            Size(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

/** Stable partition: unpinned items first, pinned items last, original order kept in each. */
internal fun List<InlineSuggestionItem>.pinnedLast(): List<InlineSuggestionItem> =
    orderPinnedLast(this) { it.pinned }

/** Pure ordering helper behind [pinnedLast]; generic so it is testable without platform views. */
internal fun <T> orderPinnedLast(items: List<T>, isPinned: (T) -> Boolean): List<T> {
    val (pinned, regular) = items.partition(isPinned)
    return regular + pinned
}
