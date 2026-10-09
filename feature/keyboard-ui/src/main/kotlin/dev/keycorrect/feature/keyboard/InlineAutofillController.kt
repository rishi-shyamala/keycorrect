package dev.keycorrect.feature.keyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
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

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Publishes a response's suggestions atomically: once every inflate has resolved, or
     * [PUBLISH_TIMEOUT_MS] after the first view arrives. The previous list stays on screen until
     * then (no flicker). Superseded and cleared batches are ignored.
     */
    private val coordinator =
        InflateCoordinator<InlineSuggestionItem>(
            PUBLISH_TIMEOUT_MS,
            { delayMs, action ->
                handler.postDelayed(action, delayMs)
                TimeoutHandle { handler.removeCallbacks(action) }
            },
            { _suggestions.value = it.pinnedLast() },
        )

    /** Handles a response; always returns true (we always use the suggestions we are given). */
    fun onResponse(response: InlineSuggestionsResponse): Boolean {
        val inline = response.inlineSuggestions
        val batch = coordinator.begin(inline.size)
        if (inline.isEmpty()) {
            _suggestions.value = emptyList()
            return true
        }
        // Inflation is asynchronous and may finish out of order, so each suggestion owns a slot
        // and the list is built from the slots, keeping the platform's order.
        inline.forEachIndexed { index, suggestion ->
            val pinned = suggestion.info.isPinned
            // A null view means inflation failed: the batch skips that suggestion.
            val onView =
                Consumer<InlineContentView?> { view ->
                    batch.resolve(index, view?.let { InlineSuggestionItem(it, pinned) })
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
        coordinator.cancel()
        if (_suggestions.value.isNotEmpty()) _suggestions.value = emptyList()
    }

    private companion object {
        /** How long after the first view arrives to publish without waiting for slower inflates. */
        const val PUBLISH_TIMEOUT_MS = 300L

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
