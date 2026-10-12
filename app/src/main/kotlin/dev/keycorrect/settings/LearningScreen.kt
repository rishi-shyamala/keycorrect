package dev.keycorrect.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.keycorrect.AppContainer
import dev.keycorrect.R
import dev.keycorrect.core.learning.model.ForgetResult
import dev.keycorrect.core.learning.model.ProfileTouchStatistics
import dev.keycorrect.core.learning.model.TouchStatistics
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.ime.LearningHost
import dev.keycorrect.ime.LearningOverview
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Choices for "Forget recent learning", in minutes. */
private val ForgetWindows = listOf(15, 60, 24 * 60)

/** What the screen needs from the learning system, read in one consistent step. */
private data class LearningSummary(val overview: LearningOverview, val touch: TouchStatistics)

/**
 * "Learning & privacy": the switches that control learning, forgetting recent learning, entry
 * points to what was learned, a typing-accuracy summary and a reset of everything learned.
 */
@Composable
fun LearningScreen(
    contentPadding: PaddingValues,
    container: AppContainer,
    settings: KeyboardSettings,
    onUpdate: ((KeyboardSettings) -> KeyboardSettings) -> Unit,
    onNavigate: (SettingsDestination) -> Unit,
) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val failed = resources.getString(R.string.data_save_failed)
    val say: (String) -> Unit = { message ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }

    PersonalDataScaffold(
        outerPadding = contentPadding,
        addLabel = "",
        onAdd = null,
        snackbarHostState = snackbar,
    ) { inner ->
        SettingsPage(inner) {
            val learning = settings.learningEnabled
            SwitchRow(
                title = stringResource(R.string.learning_enabled),
                supportingText = stringResource(R.string.learning_enabled_summary),
                checked = learning,
                onCheckedChange = { v -> onUpdate { it.copy(learningEnabled = v) } },
            )
            SwitchRow(
                title = stringResource(R.string.learning_incognito),
                supportingText =
                    stringResource(R.string.learning_incognito_summary) +
                        if (learning) "" else " " + stringResource(R.string.learning_requires),
                checked = settings.incognito,
                enabled = learning,
                onCheckedChange = { v -> onUpdate { it.copy(incognito = v) } },
            )
            SwitchRow(
                title = stringResource(R.string.learning_sentences),
                supportingText =
                    stringResource(R.string.learning_sentences_summary) +
                        if (learning) "" else " " + stringResource(R.string.learning_requires),
                checked = settings.sentenceBufferEnabled,
                enabled = learning,
                onCheckedChange = { v -> onUpdate { it.copy(sentenceBufferEnabled = v) } },
            )

            Text(
                stringResource(R.string.learning_stop_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            LearningDataGate(container, inline = true, onResetFailed = { say(failed) }) { host ->
                LearningBody(container, host, say, failed, onNavigate)
            }
        }
    }
}

@Composable
private fun LearningBody(
    container: AppContainer,
    host: LearningHost,
    say: (String) -> Unit,
    failed: String,
    onNavigate: (SettingsDestination) -> Unit,
) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableIntStateOf(0) }
    val state =
        rememberLoadState(attempt) {
            host.overviewFlow().map { LearningSummary(it, host.touchStatistics()) }
        }
    when (state) {
        LoadState.Loading -> return
        is LoadState.Failed -> {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.learning_load_failed))
                TextButton(onClick = { attempt++ }) {
                    Text(stringResource(R.string.data_action_retry))
                }
            }
            return
        }
        is LoadState.Loaded -> Unit
    }
    val summary = (state as LoadState.Loaded).value
    val overview = summary.overview

    // Sentence buffer count and "Clear now".
    ListItem(
        headlineContent = {
            Text(
                resources.getQuantityString(
                    R.plurals.learning_sentences_count,
                    overview.sentenceCount,
                    overview.sentenceCount,
                )
            )
        },
        trailingContent = {
            TextButton(
                enabled = overview.sentenceCount > 0,
                onClick = {
                    scope.launch {
                        val result = container.writeAndAwait { host.clearSentenceBuffer() }
                        say(
                            if (result.isSuccess)
                                resources.getString(R.string.learning_sentences_cleared)
                            else failed
                        )
                    }
                },
            ) {
                Text(stringResource(R.string.learning_sentences_clear))
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )

    ForgetSection(container, say, failed)

    SectionHeader(stringResource(R.string.learning_section_learned))
    NavRow(
        title = stringResource(R.string.learning_words_title),
        supportingText =
            countSummary(
                stringResource(R.string.learning_words_summary),
                overview.userWords.size,
            ),
        onClick = { onNavigate(SettingsDestination.LearnedWords) },
    )
    NavRow(
        title = stringResource(R.string.learning_corrections_title),
        supportingText = stringResource(R.string.learning_corrections_summary),
        onClick = { onNavigate(SettingsDestination.LearnedCorrections) },
    )
    NavRow(
        title = stringResource(R.string.learning_never_corrected_title),
        supportingText =
            countSummary(
                stringResource(R.string.learning_never_corrected_summary),
                overview.suppressedCorrections.size,
            ),
        onClick = { onNavigate(SettingsDestination.NeverCorrected) },
    )
    NavRow(
        title = stringResource(R.string.learning_never_suggested_title),
        supportingText =
            countSummary(
                stringResource(R.string.learning_never_suggested_summary),
                overview.blockedWords.size,
            ),
        onClick = { onNavigate(SettingsDestination.NeverSuggested) },
    )

    SectionHeader(stringResource(R.string.learning_accuracy_title))
    AccuracyCard(summary.touch)

    ResetSection(container, say, failed)
}

@Composable
private fun countSummary(text: String, count: Int): String =
    "$text · " +
        LocalResources.current.getQuantityString(R.plurals.learning_items_count, count, count)

// ---- Forget recent learning ---------------------------------------------------------------

@Composable
private fun ForgetSection(
    container: AppContainer,
    say: (String) -> Unit,
    failed: String,
) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val labels =
        listOf(
            stringResource(R.string.learning_forget_15m),
            stringResource(R.string.learning_forget_1h),
            stringResource(R.string.learning_forget_24h),
        )

    SectionHeader(stringResource(R.string.learning_forget_title))
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.learning_forget_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(
            modifier =
                Modifier.fillMaxWidth().semantics {
                    contentDescription = resources.getString(R.string.learning_forget_title)
                }
        ) {
            labels.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = selected == index,
                    onClick = { selected = index },
                    shape = SegmentedButtonDefaults.itemShape(index, labels.size),
                ) {
                    Text(label)
                }
            }
        }
        Button(onClick = { confirming = true }) {
            Text(stringResource(R.string.learning_forget_action))
        }
    }

    if (confirming) {
        val label = labels[selected]
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.learning_forget_confirm_title, label)) },
            text = { Text(stringResource(R.string.learning_forget_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        val minutes = ForgetWindows[selected]
                        scope.launch {
                            val result = container.writeAndAwait {
                                container.forgetLearningSince(minutes)
                            }
                            say(
                                result.fold(
                                    onSuccess = { forgetMessage(resources, it) },
                                    onFailure = { failed },
                                )
                            )
                        }
                    }
                ) {
                    Text(stringResource(R.string.learning_forget_confirm))
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

/** A one-line account of what [ForgetResult] removed. */
private fun forgetMessage(
    resources: android.content.res.Resources,
    result: ForgetResult,
): String {
    val parts = buildList {
        fun add(plural: Int, count: Int) {
            if (count > 0) add(resources.getQuantityString(plural, count, count))
        }
        add(R.plurals.learning_forgot_words, result.removedUserWords.size)
        add(R.plurals.learning_forgot_never_corrected, result.removedSuppressions.size)
        add(R.plurals.learning_forgot_never_suggested, result.removedBlocks.size)
        add(R.plurals.learning_forgot_sentences, result.removedSentences)
        add(R.plurals.learning_forgot_updates, result.subtractedUpdates)
    }
    return if (parts.isEmpty()) resources.getString(R.string.learning_forgot_nothing)
    else resources.getString(R.string.learning_forgot_prefix, parts.joinToString(", "))
}

// ---- Typing accuracy ----------------------------------------------------------------------

@Composable
private fun AccuracyCard(touch: TouchStatistics) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.learning_accuracy_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (touch.profiles.isEmpty()) {
                Text(
                    stringResource(R.string.learning_accuracy_none),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            for (profile in touch.profiles) {
                ProfileRow(profile)
            }
            Text(
                stringResource(R.string.learning_accuracy_later),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ProfileRow(profile: ProfileTouchStatistics) {
    val parts = profile.touchKey.split('/', limit = 3)
    val device =
        when (parts.getOrNull(0)) {
            "Phone" -> stringResource(R.string.learning_profile_phone)
            "Tablet" -> stringResource(R.string.learning_profile_tablet)
            "HardwareKeyboard" -> stringResource(R.string.learning_profile_hardware)
            else -> null
        }
    val orientation =
        when (parts.getOrNull(1)) {
            "land" -> stringResource(R.string.learning_profile_landscape)
            "port" -> stringResource(R.string.learning_profile_portrait)
            else -> null
        }
    val title =
        if (device != null && orientation != null)
            stringResource(R.string.learning_profile_label, device, orientation)
        else profile.touchKey
    val layout = parts.getOrNull(2)
    Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        if (layout != null) {
            Text(layout, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            stringResource(
                R.string.learning_accuracy_samples,
                pluralStringResource(
                    R.plurals.learning_accuracy_words,
                    profile.wordCount,
                    profile.wordCount,
                ),
                pluralStringResource(
                    R.plurals.learning_accuracy_touches,
                    profile.sampleCount,
                    profile.sampleCount,
                ),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.learning_accuracy_spread, profile.sigmaX, profile.sigmaY),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// ---- Reset --------------------------------------------------------------------------------

@Composable
private fun ResetSection(
    container: AppContainer,
    say: (String) -> Unit,
    failed: String,
) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var confirming by rememberSaveable { mutableStateOf(false) }

    SectionHeader(stringResource(R.string.learning_reset_title))
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.learning_reset_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { confirming = true }) {
            Text(
                stringResource(R.string.learning_reset_action),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.learning_reset_title)) },
            text = { Text(stringResource(R.string.learning_reset_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        scope.launch {
                            val result = container.writeAndAwait { container.resetAllLearning() }
                            say(
                                if (result.isSuccess)
                                    resources.getString(R.string.learning_reset_done)
                                else failed
                            )
                        }
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
