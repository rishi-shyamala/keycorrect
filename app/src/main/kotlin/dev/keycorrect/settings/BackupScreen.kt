@file:Suppress(
    "TooGenericExceptionCaught", // Any failure is reported as a short message; details can hold
    // text.
    "SwallowedException",
    "TooManyFunctions", // Small private composables for one screen.
    "InjectDispatcher", // One-off file work in a settings screen.
    "LongMethod", // Flat Compose layouts.
    "CyclomaticComplexMethod",
)

package dev.keycorrect.settings

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.keycorrect.AppContainer
import dev.keycorrect.R
import dev.keycorrect.core.transfer.ImportMode
import dev.keycorrect.transfer.BackupController
import dev.keycorrect.transfer.ExportState
import dev.keycorrect.transfer.ImportState
import dev.keycorrect.transfer.TransferCounts
import java.text.DateFormat
import java.util.Date

/** "Backup & transfer": export to and import from a file picked with the system file picker. */
@Composable
fun BackupScreen(contentPadding: PaddingValues, container: AppContainer) {
    val model: BackupViewModel =
        viewModel(
            factory =
                viewModelFactory {
                    initializer { BackupViewModel(createSavedStateHandle(), container) }
                }
        )
    SettingsPage(contentPadding) {
        ExportSection(model.controller)
        ImportSection(model.controller)
    }
}

// ---------------------------------------------------------------------------------------- export

@Composable
private fun ExportSection(controller: BackupController) {
    val resolver = LocalContext.current.applicationContext.contentResolver
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val state by controller.export.collectAsStateWithLifecycle()

    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri: Uri? ->
            if (uri == null) {
                controller.cancelExport()
            } else {
                controller.finishExport { bytes ->
                    (resolver.openOutputStream(uri, "wt") ?: throw java.io.IOException()).use {
                        it.write(bytes)
                    }
                }
            }
        }

    SectionHeader(stringResource(R.string.backup_export_header))
    BodyText(stringResource(R.string.backup_export_intro))
    PassphraseField(passphrase, R.string.backup_passphrase) { passphrase = it }
    PassphraseField(confirm, R.string.backup_passphrase_confirm) { confirm = it }
    val mismatch = passphrase != confirm
    BodyText(
        when {
            mismatch -> stringResource(R.string.backup_passphrase_mismatch)
            passphrase.isEmpty() -> stringResource(R.string.backup_passphrase_plain_note)
            else -> stringResource(R.string.backup_passphrase_encrypted_note)
        },
        error = mismatch,
    )
    Button(
        enabled = !mismatch && state != ExportState.Working,
        onClick = {
            // The typed text is dropped right away; the controller holds the passphrase until the
            // picker answers (a text field cannot hold a char array).
            controller.beginExport(passphrase.toCharArray())
            passphrase = ""
            confirm = ""
            launcher.launch(exportFileName())
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(stringResource(R.string.backup_export_action))
    }
    when (val s = state) {
        ExportState.Idle -> Unit
        ExportState.Working -> BodyText(stringResource(R.string.backup_working))
        is ExportState.Done -> {
            BodyText(stringResource(R.string.backup_export_done))
            CountLines(s.counts)
        }
        is ExportState.Failed -> BodyText(stringResource(s.messageRes), error = true)
    }
}

private fun exportFileName(): String {
    val day = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
    return "keycorrect-$day.kcbackup"
}

// ---------------------------------------------------------------------------------------- import

@Composable
private fun ImportSection(controller: BackupController) {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val state by controller.import.collectAsStateWithLifecycle()

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                controller.pickFile(fileSize(resolver, uri)) { resolver.openInputStream(uri) }
            }
        }

    SectionHeader(stringResource(R.string.backup_import_header))
    BodyText(stringResource(R.string.backup_import_intro))
    Button(
        enabled = state != ImportState.Working,
        onClick = {
            controller.cancelImport()
            picker.launch(arrayOf("*/*"))
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(stringResource(R.string.backup_import_action))
    }

    when (val s = state) {
        ImportState.Idle -> Unit
        ImportState.Working -> BodyText(stringResource(R.string.backup_working))
        is ImportState.NeedPassphrase ->
            PassphraseDialog(
                wrong = s.wrong,
                onSubmit = { controller.submitPassphrase(s.bytes, it) },
                onCancel = controller::cancelImport,
            )
        is ImportState.Review ->
            ReviewImport(
                review = s,
                onApply = { mode, anyway, mergeAnyway ->
                    controller.apply(s, mode, anyway, mergeAnyway)
                },
                onCancel = controller::cancelImport,
            )
        is ImportState.Done -> {
            BodyText(stringResource(R.string.backup_import_done))
            CountLines(s.counts, touchSkipped = s.skippedTouch)
            if (s.skippedTouch) BodyText(stringResource(R.string.backup_import_done_skipped))
            if (s.settingsFailed) {
                BodyText(stringResource(R.string.backup_import_done_settings_failed), error = true)
            }
            if (s.reloadFailed) {
                BodyText(stringResource(R.string.backup_import_done_restart), error = true)
            }
        }
        is ImportState.Failed -> BodyText(stringResource(s.messageRes), error = true)
    }
}

/** The size the file system reports for [uri], or null when it does not say. */
private fun fileSize(resolver: ContentResolver, uri: Uri): Long? =
    try {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }
    } catch (e: Exception) {
        null
    }

@Composable
private fun PassphraseDialog(
    wrong: Boolean,
    onSubmit: (CharArray) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.backup_passphrase_prompt_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_passphrase_prompt_body))
                if (wrong) {
                    Text(
                        stringResource(R.string.backup_passphrase_wrong),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                PassphraseField(text, R.string.backup_passphrase, horizontalPadding = 0) {
                    text = it
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotEmpty(),
                onClick = {
                    val chars = text.toCharArray()
                    text = ""
                    onSubmit(chars)
                },
            ) {
                Text(stringResource(R.string.backup_unlock))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.backup_cancel)) }
        },
    )
}

@Composable
private fun ReviewImport(
    review: ImportState.Review,
    onApply: (mode: ImportMode, importTouchAnyway: Boolean, mergeAnyway: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val bundle = review.bundle
    val screenDiffers = review.screenDiffers
    val already = review.alreadyImportedAt
    var mode by remember { mutableStateOf<ImportMode?>(null) }
    var anyway by remember { mutableStateOf(false) }
    var mergeAnyway by remember { mutableStateOf(false) }
    val mergeBlocked = already != null && !mergeAnyway
    var confirmReplace by remember { mutableStateOf(false) }
    val device = bundle.manifest.device
    val date =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(bundle.manifest.createdAtMillis))

    SectionHeader(stringResource(R.string.backup_review_header))
    BodyText(
        stringResource(
            R.string.backup_review_device,
            device.model,
            device.screenWidthPx,
            device.screenHeightPx,
        )
    )
    BodyText(stringResource(R.string.backup_review_date, date, bundle.manifest.appVersion))
    val touchSkipped = screenDiffers && !anyway
    CountLines(TransferCounts.of(bundle, includeTouch = !touchSkipped), touchSkipped)

    SectionHeader(stringResource(R.string.backup_mode_header))
    if (already != null) {
        BodyText(
            stringResource(
                R.string.backup_already_merged,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(already)),
            ),
            error = true,
        )
        CheckRow(mergeAnyway, R.string.backup_merge_anyway) {
            mergeAnyway = it
            if (!it && mode == ImportMode.Merge) mode = null
        }
    }
    Column(Modifier.selectableGroup()) {
        ModeRow(
            selected = mode == ImportMode.Merge,
            enabled = !mergeBlocked,
            title = stringResource(R.string.backup_mode_merge),
            summary = stringResource(R.string.backup_mode_merge_summary),
            onSelect = { mode = ImportMode.Merge },
        )
        ModeRow(
            selected = mode == ImportMode.Replace,
            title = stringResource(R.string.backup_mode_replace),
            summary = stringResource(R.string.backup_mode_replace_summary),
            onSelect = { mode = ImportMode.Replace },
        )
    }

    if (screenDiffers) {
        BodyText(
            stringResource(
                R.string.backup_screen_warning,
                device.screenWidthPx,
                device.screenHeightPx,
                device.densityDpi,
            ),
            error = true,
        )
        CheckRow(anyway, R.string.backup_screen_anyway) { anyway = it }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Button(
            enabled = mode != null,
            onClick = {
                val chosen = mode ?: return@Button
                if (chosen == ImportMode.Replace) confirmReplace = true
                else onApply(chosen, anyway && screenDiffers, mergeAnyway)
            },
        ) {
            Text(stringResource(R.string.backup_import_apply))
        }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.backup_cancel)) }
    }

    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text(stringResource(R.string.backup_replace_confirm_title)) },
            text = { Text(stringResource(R.string.backup_replace_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReplace = false
                        onApply(ImportMode.Replace, anyway && screenDiffers, mergeAnyway)
                    }
                ) {
                    Text(stringResource(R.string.backup_replace_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReplace = false }) {
                    Text(stringResource(R.string.backup_cancel))
                }
            },
        )
    }
}

@Composable
private fun CheckRow(checked: Boolean, labelRes: Int, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .selectable(
                    selected = checked,
                    role = Role.Checkbox,
                    onClick = { onChange(!checked) },
                )
                .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(stringResource(labelRes), modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun ModeRow(
    selected: Boolean,
    title: String,
    summary: String,
    onSelect: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier =
            Modifier.fillMaxWidth()
                .selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = onSelect,
                )
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------- shared

@Composable
private fun PassphraseField(
    value: String,
    labelRes: Int,
    horizontalPadding: Int = 16,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier =
            Modifier.fillMaxWidth().padding(horizontal = horizontalPadding.dp, vertical = 4.dp),
    )
}

@Composable
private fun BodyText(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color =
            if (error) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun CountLines(c: TransferCounts, touchSkipped: Boolean = false) {
    val lines =
        listOf(
            R.string.backup_count_settings to c.settings,
            R.string.backup_count_words to c.words,
            R.string.backup_count_learned to c.learnedWords,
            R.string.backup_count_blocked to c.blockedWords,
            R.string.backup_count_shortcuts to c.shortcuts,
            R.string.backup_count_ngrams to c.ngrams,
            R.string.backup_count_corrections to c.corrections,
            R.string.backup_count_sentences to c.sentences,
            R.string.backup_count_emoji to c.emoji,
            (if (touchSkipped) R.string.backup_count_touch_skipped
            else R.string.backup_count_touch) to c.touchKeys,
        )
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).semantics(mergeDescendants = true) {}
    ) {
        for ((res, n) in lines) Text(
            stringResource(res, n),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private const val DISABLED_ALPHA = 0.38f
