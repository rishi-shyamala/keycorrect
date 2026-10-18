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

import android.net.Uri
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.keycorrect.AppContainer
import dev.keycorrect.R
import dev.keycorrect.core.transfer.ImportMode
import dev.keycorrect.core.transfer.TransferBundle
import dev.keycorrect.core.transfer.TransferCodec
import dev.keycorrect.core.transfer.TransferException
import dev.keycorrect.transfer.TransferCounts
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Largest backup file read into memory; a bigger one is refused as too large. */
private const val MAX_FILE_BYTES = 64 * 1024 * 1024

private sealed interface ExportState {
    data object Idle : ExportState

    data object Working : ExportState

    data class Done(val counts: TransferCounts) : ExportState

    data class Failed(val messageRes: Int) : ExportState
}

private sealed interface ImportState {
    data object Idle : ImportState

    data object Working : ImportState

    /** An encrypted file waiting for its passphrase. */
    class NeedPassphrase(val bytes: ByteArray, val wrong: Boolean) : ImportState

    class Review(val bundle: TransferBundle, val screenDiffers: Boolean) : ImportState

    data class Done(val counts: TransferCounts, val skippedTouch: Boolean) : ImportState

    data class Failed(val messageRes: Int) : ImportState
}

/** "Backup & transfer": export to and import from a file picked with the system file picker. */
@Composable
fun BackupScreen(contentPadding: PaddingValues, container: AppContainer) {
    SettingsPage(contentPadding) {
        ExportSection(container)
        ImportSection(container)
    }
}

// ---------------------------------------------------------------------------------------- export

@Composable
private fun ExportSection(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<ExportState>(ExportState.Idle) }
    // Held only between the button and the file picker's answer, then handed to the codec (which
    // zeroes it). A text field cannot hold a char array, so the typed text is dropped right after.
    var pending by remember { mutableStateOf<CharArray?>(null) }

    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri: Uri? ->
            val key = pending
            pending = null
            if (uri == null) {
                key?.fill('\u0000')
                state = ExportState.Idle
                return@rememberLauncherForActivityResult
            }
            state = ExportState.Working
            scope.launch {
                state =
                    try {
                        val counts =
                            withContext(Dispatchers.Default) {
                                val service = container.transferService
                                val bundle = service.buildBundle()
                                val bytes = service.encode(bundle, key)
                                withContext(Dispatchers.IO) {
                                    (context.contentResolver.openOutputStream(uri, "wt")
                                            ?: throw java.io.IOException())
                                        .use {
                                            it.write(bytes)
                                        }
                                }
                                TransferCounts.of(bundle)
                            }
                        ExportState.Done(counts)
                    } catch (e: IllegalStateException) {
                        key?.fill('\u0000')
                        ExportState.Failed(R.string.backup_export_unavailable)
                    } catch (e: Exception) {
                        key?.fill('\u0000')
                        ExportState.Failed(R.string.backup_export_failed)
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
            pending = passphrase.toCharArray()
            passphrase = ""
            confirm = ""
            state = ExportState.Idle
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
private fun ImportSection(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ImportState>(ImportState.Idle) }

    /** Parses [bytes]; asks for the passphrase when the file is encrypted and none was given. */
    fun decode(bytes: ByteArray, passphrase: CharArray?) {
        state = ImportState.Working
        scope.launch {
            state =
                try {
                    if (TransferCodec.isEncrypted(bytes) && passphrase == null) {
                        ImportState.NeedPassphrase(bytes, wrong = false)
                    } else {
                        val bundle =
                            withContext(Dispatchers.Default) {
                                TransferCodec.read(bytes) { passphrase }
                            }
                        val differs = container.transferService.screenDiffers(bundle)
                        ImportState.Review(bundle, differs)
                    }
                } catch (e: TransferException.WrongPassphrase) {
                    ImportState.NeedPassphrase(bytes, wrong = true)
                } catch (e: TransferException) {
                    ImportState.Failed(errorMessage(e))
                } catch (e: Exception) {
                    ImportState.Failed(R.string.backup_error_corrupt)
                }
        }
    }

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            state = ImportState.Working
            scope.launch {
                val bytes =
                    withContext(Dispatchers.IO) {
                        try {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                readAtMost(input, MAX_FILE_BYTES + 1)
                            }
                        } catch (e: Exception) {
                            null
                        }
                    }
                when {
                    bytes == null -> state = ImportState.Failed(R.string.backup_error_read)
                    bytes.size > MAX_FILE_BYTES ->
                        state = ImportState.Failed(R.string.backup_error_too_large)
                    else -> decode(bytes, null)
                }
            }
        }

    SectionHeader(stringResource(R.string.backup_import_header))
    BodyText(stringResource(R.string.backup_import_intro))
    Button(
        enabled = state != ImportState.Working,
        onClick = {
            state = ImportState.Idle
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
                onSubmit = { decode(s.bytes, it) },
                onCancel = { state = ImportState.Idle },
            )
        is ImportState.Review ->
            ReviewImport(
                bundle = s.bundle,
                screenDiffers = s.screenDiffers,
                onApply = { mode, anyway ->
                    state = ImportState.Working
                    // The application scope: an import that has begun finishes even if the screen
                    // is left.
                    container.applicationScope.launch {
                        val next =
                            withContext(NonCancellable) {
                                try {
                                    val service = container.transferService
                                    val plan = service.plan(s.bundle, mode, anyway)
                                    val result = service.import(s.bundle, plan)
                                    ImportState.Done(result.applied, result.skippedTouch)
                                } catch (e: Exception) {
                                    ImportState.Failed(R.string.backup_import_failed)
                                }
                            }
                        state = next
                    }
                },
                onCancel = { state = ImportState.Idle },
            )
        is ImportState.Done -> {
            BodyText(stringResource(R.string.backup_import_done))
            CountLines(s.counts)
            if (s.skippedTouch) BodyText(stringResource(R.string.backup_import_done_skipped))
        }
        is ImportState.Failed -> BodyText(stringResource(s.messageRes), error = true)
    }
}

private fun errorMessage(e: TransferException): Int =
    when (e) {
        is TransferException.NotABackup -> R.string.backup_error_not_a_backup
        is TransferException.NewerFormat -> R.string.backup_error_newer
        is TransferException.TooLarge -> R.string.backup_error_too_large
        else -> R.string.backup_error_corrupt
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
    bundle: TransferBundle,
    screenDiffers: Boolean,
    onApply: (ImportMode, Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var mode by remember { mutableStateOf<ImportMode?>(null) }
    var anyway by remember { mutableStateOf(false) }
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
    CountLines(TransferCounts.of(bundle))

    SectionHeader(stringResource(R.string.backup_mode_header))
    Column(Modifier.selectableGroup()) {
        ModeRow(
            selected = mode == ImportMode.Merge,
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.fillMaxWidth()
                    .selectable(
                        selected = anyway,
                        role = Role.Checkbox,
                        onClick = { anyway = !anyway },
                    )
                    .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Checkbox(checked = anyway, onCheckedChange = null)
            Text(
                stringResource(R.string.backup_screen_anyway),
                modifier = Modifier.padding(start = 12.dp),
            )
        }
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
                else onApply(chosen, anyway && screenDiffers)
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
                        onApply(ImportMode.Replace, anyway && screenDiffers)
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
private fun ModeRow(selected: Boolean, title: String, summary: String, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier =
            Modifier.fillMaxWidth()
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
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
private fun CountLines(c: TransferCounts) {
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
            R.string.backup_count_touch to c.touchKeys,
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

/** Reads up to [limit] bytes (so an oversized file is noticed without reading all of it). */
private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_BYTES)
    while (out.size() < limit) {
        val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

private const val BUFFER_BYTES = 64 * 1024
