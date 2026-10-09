package dev.keycorrect.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.keycorrect.AppContainer
import dev.keycorrect.R
import dev.keycorrect.core.data.Shortcut
import dev.keycorrect.core.data.ShortcutMode
import dev.keycorrect.core.data.Validation
import dev.keycorrect.core.data.ValidationError
import kotlinx.coroutines.launch

/** Lists, adds, edits and deletes (with undo) text-expansion shortcuts. */
@Composable
fun ShortcutsScreen(contentPadding: PaddingValues, container: AppContainer) {
    val store = container.shortcuts
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var attempt by remember { mutableIntStateOf(0) }
    // null: no dialog, "": adding, otherwise the trigger of the shortcut being edited.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }

    val state = rememberLoadState(attempt) { store.all() }
    val saveFailed = resources.getString(R.string.data_save_failed)
    fun reportFailure() {
        scope.launch { snackbar.showSnackbar(saveFailed) }
    }

    PersonalDataScaffold(
        outerPadding = contentPadding,
        addLabel = stringResource(R.string.shortcuts_add),
        onAdd = { editing = "" }.takeIf { state is LoadState.Loaded },
        snackbarHostState = snackbar,
    ) { inner ->
        when (state) {
            LoadState.Loading -> Box(Modifier.fillMaxSize().padding(inner))
            is LoadState.Failed ->
                DataErrorState(
                    error = state.cause,
                    onRetry = { attempt++ },
                    onReset = { container.resetPersonalData { attempt++ } },
                )
            is LoadState.Loaded -> {
                val shortcuts = state.value
                if (shortcuts.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.shortcuts_empty_title),
                        body = stringResource(R.string.shortcuts_empty_body),
                    )
                } else {
                    ShortcutList(
                        padding = inner,
                        shortcuts = shortcuts,
                        onEdit = { editing = it.trigger },
                        onDelete = { shortcut ->
                            container.write(::reportFailure) { store.remove(shortcut.trigger) }
                            scope.launch {
                                snackbar.currentSnackbarData?.dismiss()
                                val result =
                                    snackbar.showSnackbar(
                                        message =
                                            resources.getString(
                                                R.string.shortcuts_removed,
                                                shortcut.trigger,
                                            ),
                                        actionLabel =
                                            resources.getString(R.string.data_action_undo),
                                        duration = SnackbarDuration.Long,
                                        withDismissAction = true,
                                    )
                                if (result == SnackbarResult.ActionPerformed) {
                                    container.write(::reportFailure) {
                                        store.put(
                                            shortcut.trigger,
                                            shortcut.expansion,
                                            shortcut.mode,
                                        )
                                    }
                                }
                            }
                        },
                    )
                }

                editing?.let { key ->
                    val initial = shortcuts.firstOrNull { it.trigger == key }
                    ShortcutDialog(
                        initial = initial,
                        takenTriggers = shortcuts.mapTo(HashSet()) { it.trigger },
                        onDismiss = { editing = null },
                        onConfirm = { trigger, expansion, mode ->
                            editing = null
                            container.write(::reportFailure) {
                                store.put(trigger, expansion, mode)
                                if (initial != null && initial.trigger != trigger) {
                                    store.remove(initial.trigger)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ShortcutList(
    padding: PaddingValues,
    shortcuts: List<Shortcut>,
    onEdit: (Shortcut) -> Unit,
    onDelete: (Shortcut) -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth(),
            // Leave room for the floating action button below the last row.
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            items(shortcuts, key = { it.trigger }) { shortcut ->
                ListItem(
                    headlineContent = { Text(shortcut.trigger) },
                    supportingContent = {
                        Text(shortcut.expansion, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ModeBadge(shortcut.mode)
                            DeleteButton(
                                description =
                                    stringResource(
                                        R.string.shortcuts_delete_description,
                                        shortcut.trigger,
                                    ),
                                onClick = { onDelete(shortcut) },
                            )
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(role = Role.Button) { onEdit(shortcut) },
                )
            }
        }
    }
}

@Composable
private fun ModeBadge(mode: ShortcutMode) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            stringResource(mode.labelRes()),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private fun ShortcutMode.labelRes(): Int =
    when (this) {
        ShortcutMode.SUGGEST -> R.string.shortcuts_mode_suggest
        ShortcutMode.AUTO_EXPAND -> R.string.shortcuts_mode_auto_expand
    }

/** Add ([initial] null) or edit dialog. [takenTriggers] are normalised triggers already stored. */
@Composable
private fun ShortcutDialog(
    initial: Shortcut?,
    takenTriggers: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (trigger: String, expansion: String, mode: ShortcutMode) -> Unit,
) {
    var trigger by rememberSaveable { mutableStateOf(initial?.trigger.orEmpty()) }
    var expansion by rememberSaveable { mutableStateOf(initial?.expansion.orEmpty()) }
    var autoExpand by rememberSaveable { mutableStateOf(initial?.mode == ShortcutMode.AUTO_EXPAND) }
    var submitted by rememberSaveable { mutableStateOf(false) }

    val normalized = Validation.normalizeTrigger(trigger)
    val validation = Validation.checkShortcut(trigger, expansion)
    val duplicate =
        validation == null && normalized in takenTriggers && normalized != initial?.trigger
    val triggerError = submitted && (validation?.isTriggerError() == true || duplicate)
    val expansionError = submitted && validation?.isTriggerError() == false

    val save = {
        submitted = true
        if (validation == null && !duplicate) {
            onConfirm(
                normalized,
                expansion,
                if (autoExpand) ShortcutMode.AUTO_EXPAND else ShortcutMode.SUGGEST,
            )
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.shortcuts_add_dialog_title
                    else R.string.shortcuts_edit_dialog_title
                )
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = trigger,
                    onValueChange = { trigger = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.shortcuts_trigger_label)) },
                    isError = triggerError,
                    supportingText = {
                        if (triggerError) {
                            Text(
                                when {
                                    duplicate -> stringResource(R.string.validation_trigger_exists)
                                    else -> validationMessage(validation!!)
                                }
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = expansion,
                    onValueChange = { expansion = it },
                    minLines = 3,
                    label = { Text(stringResource(R.string.shortcuts_expansion_label)) },
                    isError = expansionError,
                    supportingText = {
                        Text(
                            if (expansionError) validationMessage(validation!!)
                            else stringResource(R.string.shortcuts_variables_help)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.shortcuts_mode_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val modes = listOf(ShortcutMode.SUGGEST, ShortcutMode.AUTO_EXPAND)
                    modes.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = (mode == ShortcutMode.AUTO_EXPAND) == autoExpand,
                            onClick = { autoExpand = mode == ShortcutMode.AUTO_EXPAND },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        ) {
                            Text(stringResource(mode.labelRes()))
                        }
                    }
                }
                Text(
                    stringResource(
                        if (autoExpand) R.string.shortcuts_mode_auto_expand_help
                        else R.string.shortcuts_mode_suggest_help
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }) { Text(stringResource(R.string.data_action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.data_action_cancel)) }
        },
    )
}

private fun ValidationError.isTriggerError(): Boolean =
    when (this) {
        ValidationError.EMPTY_TRIGGER,
        ValidationError.TRIGGER_CONTAINS_WHITESPACE,
        ValidationError.TRIGGER_TOO_LONG -> true
        else -> false
    }
