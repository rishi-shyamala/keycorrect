package dev.keycorrect.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keycorrect.AppContainer
import dev.keycorrect.R
import dev.keycorrect.core.data.KeyUnavailableException
import dev.keycorrect.core.data.Validation
import dev.keycorrect.core.data.ValidationError
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/*
 * Shared plumbing for the screens backed by the encrypted personal-data database (personal
 * dictionary, text shortcuts): load state with error handling, an error panel with recovery, list
 * scaffolding and validation messages.
 */

/** What a screen shows for a flow read from the encrypted database. */
internal sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>

    data class Loaded<T>(val value: T) : LoadState<T>

    /** Reading failed, typically [KeyUnavailableException] when the Keystore key was lost. */
    data class Failed(val cause: Throwable) : LoadState<Nothing>
}

/** Collects [source] as a [LoadState]. Changing [attempt] restarts a failed collection. */
@Composable
internal fun <T> rememberLoadState(attempt: Int, source: () -> Flow<T>): LoadState<T> {
    val flow =
        remember(attempt) {
            flow { emitAll(source()) }
                .map<T, LoadState<T>> { LoadState.Loaded(it) }
                .catch { emit(LoadState.Failed(it)) }
        }
    val state by flow.collectAsStateWithLifecycle(initialValue = LoadState.Loading)
    return state
}

/** Whether this failure (or its cause) means the Keystore key is gone and the data is lost. */
internal fun Throwable.isKeyLost(): Boolean =
    generateSequence(this) { it.cause }.any { it is KeyUnavailableException }

/**
 * Runs [block] on the application scope so a write finishes even if the screen is closed, and calls
 * [onFailure] (best effort: the screen may be gone) when it throws.
 */
internal fun AppContainer.write(onFailure: () -> Unit = {}, block: suspend () -> Unit): Job =
    applicationScope.launch {
        try {
            block()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            onFailure()
        }
    }

/**
 * Scaffold shared by the list screens: width-capped content, an add FAB and a snackbar host.
 * [content] gets the content padding for the list and the host state for undo messages.
 */
@Composable
internal fun PersonalDataScaffold(
    outerPadding: PaddingValues,
    addLabel: String,
    onAdd: (() -> Unit)?,
    snackbarHostState: SnackbarHostState,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize().padding(outerPadding),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (onAdd != null) {
                FloatingActionButton(
                    onClick = onAdd,
                    modifier = Modifier.semantics { contentDescription = addLabel },
                ) {
                    Text(
                        "+",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            }
        },
    ) { inner ->
        content(inner)
    }
}

/** Centered column limited to [MaxContentWidth] for panels and empty states. */
@Composable
internal fun CenteredPanel(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            content()
        }
    }
}

@Composable
internal fun EmptyState(title: String, body: String) {
    CenteredPanel {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Friendly error panel for a failed database read. Offers "Try again" and, because
 * [KeyUnavailableException] or a corrupt file leaves the data unreadable for good, a confirmed
 * "Reset personal data" which calls [onReset] ([dev.keycorrect.core.data.KeyCorrectDatabase]
 * .destroy()).
 */
@Composable
internal fun DataErrorState(error: Throwable, onRetry: () -> Unit, onReset: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    CenteredPanel {
        Text(
            stringResource(R.string.data_error_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            stringResource(
                if (error.isKeyLost()) R.string.data_error_key_lost else R.string.data_error_generic
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry) { Text(stringResource(R.string.data_action_retry)) }
        OutlinedButton(onClick = { confirming = true }) {
            Text(stringResource(R.string.data_reset_action))
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.data_reset_title)) },
            text = { Text(stringResource(R.string.data_reset_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onReset()
                    }
                ) {
                    Text(stringResource(R.string.data_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.data_action_cancel))
                }
            },
        )
    }
}

/** A glyph-only delete button. The glyph is hidden from accessibility; [description] is read. */
@Composable
internal fun DeleteButton(description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Text(
            "×",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/** Localized message for a [ValidationError] returned by [Validation]. */
@Composable
internal fun validationMessage(error: ValidationError): String =
    when (error) {
        ValidationError.EMPTY_WORD -> stringResource(R.string.validation_empty_word)
        ValidationError.WORD_CONTAINS_WHITESPACE ->
            stringResource(R.string.validation_word_whitespace)
        ValidationError.WORD_TOO_LONG ->
            stringResource(R.string.validation_word_too_long, Validation.MAX_WORD_LENGTH)
        ValidationError.FREQUENCY_OUT_OF_RANGE ->
            stringResource(R.string.validation_frequency_range)
        ValidationError.EMPTY_TRIGGER -> stringResource(R.string.validation_empty_trigger)
        ValidationError.TRIGGER_CONTAINS_WHITESPACE ->
            stringResource(R.string.validation_trigger_whitespace)
        ValidationError.TRIGGER_TOO_LONG ->
            stringResource(R.string.validation_trigger_too_long, Validation.MAX_TRIGGER_LENGTH)
        ValidationError.EMPTY_EXPANSION -> stringResource(R.string.validation_empty_expansion)
        ValidationError.EXPANSION_TOO_LONG ->
            stringResource(R.string.validation_expansion_too_long, Validation.MAX_EXPANSION_LENGTH)
    }

/**
 * Erases the database and reloads both stores so the keyboard's in-memory snapshots are empty too.
 * Runs on the application scope; [onDone] is called afterwards (best effort).
 */
internal fun AppContainer.resetPersonalData(onDone: () -> Unit) {
    write(onFailure = onDone) {
        database.destroy()
        userDictionary.load()
        shortcuts.load()
        onDone()
    }
}
