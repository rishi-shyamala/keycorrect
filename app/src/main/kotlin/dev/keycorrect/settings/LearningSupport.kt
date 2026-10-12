package dev.keycorrect.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keycorrect.AppContainer
import dev.keycorrect.R
import dev.keycorrect.core.learning.LearningUpdate.AddUserWord
import dev.keycorrect.ime.LearningHost
import dev.keycorrect.ime.LearningHostState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async

/*
 * Shared plumbing for the "Learning & privacy" screens: gating on the learning system's load state,
 * writes that outlive the screen but report back to it, and small formatting helpers.
 */

/**
 * Runs [block] on the application scope (so it finishes even if the screen is closed) and returns
 * its outcome to the caller, which can then show a message if it is still around. Cancelling the
 * caller does not cancel [block].
 */
internal suspend fun <T> AppContainer.writeAndAwait(block: suspend () -> T): Result<T> =
    applicationScope
        .async {
            try {
                Result.success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        .await()

/**
 * Shows [content] once the learning system has loaded its saved state; a spinner while it is
 * [LearningHostState.Restoring] and an explanation with a confirmed reset while it is
 * [LearningHostState.Unavailable]. [inline] renders the status in the flow of a scrolling page
 * instead of filling the screen.
 */
@Composable
internal fun LearningDataGate(
    container: AppContainer,
    inline: Boolean,
    onResetFailed: () -> Unit,
    content: @Composable (LearningHost) -> Unit,
) {
    val host = container.learningHost
    val state by host.state.collectAsStateWithLifecycle()
    when (state) {
        LearningHostState.Ready -> content(host)
        LearningHostState.Restoring ->
            StatusPanel(inline) {
                CircularProgressIndicator()
                Text(
                    stringResource(R.string.learning_restoring),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        LearningHostState.Unavailable ->
            StatusPanel(inline) {
                UnavailableContent(
                    onReset = {
                        container.write(onFailure = onResetFailed) { container.resetAllLearning() }
                    }
                )
            }
    }
}

@Composable
private fun StatusPanel(inline: Boolean, content: @Composable () -> Unit) {
    if (inline) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            content()
        }
    } else {
        CenteredPanel(content)
    }
}

@Composable
private fun UnavailableContent(onReset: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Text(
        stringResource(R.string.learning_unavailable_title),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    Text(
        stringResource(R.string.learning_unavailable_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    OutlinedButton(onClick = { confirming = true }) {
        Text(stringResource(R.string.learning_unavailable_reset))
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.learning_unavailable_reset_title)) },
            text = { Text(stringResource(R.string.learning_reset_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onReset()
                    }
                ) {
                    Text(
                        stringResource(R.string.learning_reset_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
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

/** How a word got onto the learned list, in words the user would use. */
@Composable
internal fun reasonLabel(reason: AddUserWord.Reason): String =
    stringResource(
        when (reason) {
            AddUserWord.Reason.Explicit -> R.string.learning_reason_explicit
            AddUserWord.Reason.RevertedAutocorrect -> R.string.learning_reason_reverted
            AddUserWord.Reason.Promoted -> R.string.learning_reason_promoted
        }
    )
