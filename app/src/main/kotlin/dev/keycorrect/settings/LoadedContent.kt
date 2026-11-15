package dev.keycorrect.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The body of a personal-data screen for [state]: an empty box while loading, the retry/reset error
 * state on failure, otherwise [content] with the loaded value.
 */
@Composable
internal fun <T> LoadedContent(
    state: LoadState<T>,
    padding: PaddingValues,
    onRetry: () -> Unit,
    onReset: () -> Unit,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        LoadState.Loading -> Box(Modifier.fillMaxSize().padding(padding))
        is LoadState.Failed -> DataErrorState(state.cause, onRetry, onReset)
        is LoadState.Loaded -> content(state.value)
    }
}
