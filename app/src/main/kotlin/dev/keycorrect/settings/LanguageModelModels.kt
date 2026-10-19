@file:Suppress("InjectDispatcher") // file work (import, list, delete) off the main thread

package dev.keycorrect.settings

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.keycorrect.AppContainer
import dev.keycorrect.R
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.neural.ImportResult
import dev.keycorrect.neural.ImportedModel
import dev.keycorrect.neural.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Choose the model (the pack's or an imported one), import a GGUF file, delete an import. */
@Composable
internal fun ModelsSection(
    container: AppContainer,
    settings: KeyboardSettings,
    onUpdate: ((KeyboardSettings) -> KeyboardSettings) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val store = container.modelStore
    var models by remember { mutableStateOf(emptyList<ImportedModel>()) }
    var refresh by remember { mutableIntStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ImportResult?>(null) }
    var deleting by remember { mutableStateOf<ImportedModel?>(null) }
    androidx.compose.runtime.LaunchedEffect(refresh) {
        models = withContext(Dispatchers.IO) { store.list() }
    }

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                importing = true
                result = null
                // The application scope, so a long copy finishes even if the screen is left.
                container.applicationScope.launch {
                    result =
                        withContext(Dispatchers.IO) { store.import(context.contentResolver, uri) }
                    importing = false
                    refresh++
                }
            }
        }

    SectionHeader(stringResource(R.string.lm_section_models))
    val selectedImport = models.firstOrNull { it.id == settings.neuralModel }
    ChoiceRow(
        selected = selectedImport == null,
        title = stringResource(R.string.lm_model_builtin),
        summary = stringResource(R.string.lm_model_builtin_summary),
        onSelect = { onUpdate { it.copy(neuralModel = "") } },
    )
    for (model in models) {
        ModelRow(
            model = model,
            selected = model.id == selectedImport?.id,
            onSelect = { onUpdate { it.copy(neuralModel = model.id) } },
            onDelete = { deleting = model },
        )
    }
    ImportControls(enabled = !importing && models.size < ModelStore.MAX_MODELS, importing) {
        picker.launch(arrayOf("*/*"))
    }
    ImportFeedback(result)

    deleting?.let { model ->
        DeleteModelDialog(
            model = model,
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                container.applicationScope.launch {
                    deleteModel(store, model.id, settings, onUpdate)
                    refresh++
                }
            },
        )
    }
}

private suspend fun deleteModel(
    store: ModelStore,
    id: String,
    settings: KeyboardSettings,
    onUpdate: ((KeyboardSettings) -> KeyboardSettings) -> Unit,
) {
    withContext(Dispatchers.IO) { store.delete(id) }
    if (settings.neuralModel == id) onUpdate { it.copy(neuralModel = "") }
}

@Composable
private fun ImportFeedback(result: ImportResult?) {
    result?.let { found ->
        Text(
            importMessage(found),
            style = MaterialTheme.typography.bodyMedium,
            modifier =
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp).semantics {
                    liveRegion = LiveRegionMode.Polite
                },
        )
    }
    Text(
        stringResource(R.string.lm_model_import_hint, ModelStore.MAX_MODELS),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun ModelRow(
    model: ImportedModel,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    ChoiceRow(
        selected = selected,
        title = model.displayName,
        summary = Formatter.formatFileSize(LocalContext.current, model.sizeBytes),
        onSelect = onSelect,
        trailing = {
            TextButton(onClick = onDelete) {
                Text(
                    stringResource(R.string.lm_model_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

@Composable
private fun ImportControls(enabled: Boolean, importing: Boolean, onImport: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Button(enabled = enabled, onClick = onImport) {
            Text(stringResource(R.string.lm_model_import))
        }
        if (importing) {
            CircularProgressIndicator(modifier = Modifier.padding(start = 16.dp))
            Text(
                stringResource(R.string.lm_model_importing),
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun DeleteModelDialog(model: ImportedModel, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lm_model_delete_title, model.displayName)) },
        text = { Text(stringResource(R.string.lm_model_delete_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.lm_model_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.data_action_cancel)) }
        },
    )
}

/** One plain sentence per import outcome. */
@Composable
private fun importMessage(result: ImportResult): String {
    val context = LocalContext.current
    return when (result) {
        is ImportResult.Imported ->
            if (result.alreadyPresent) {
                stringResource(R.string.lm_import_already, result.model.displayName)
            } else {
                stringResource(R.string.lm_import_done, result.model.displayName)
            }
        ImportResult.NotGguf -> stringResource(R.string.lm_import_not_gguf)
        is ImportResult.UnsupportedVersion ->
            stringResource(R.string.lm_import_version, result.version)
        ImportResult.TooLarge ->
            stringResource(
                R.string.lm_import_too_large,
                Formatter.formatFileSize(context, ModelStore.MAX_MODEL_BYTES),
            )
        is ImportResult.NoSpace ->
            stringResource(
                R.string.lm_import_no_space,
                Formatter.formatFileSize(context, result.neededBytes),
            )
        ImportResult.TooManyModels ->
            stringResource(R.string.lm_import_too_many, ModelStore.MAX_MODELS)
        // The reason is an exception class name: no paths or user data.
        is ImportResult.Failed -> stringResource(R.string.lm_import_failed, result.reason)
    }
}
