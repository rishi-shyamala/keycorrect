package dev.keycorrect.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.keycorrect.AppContainer
import dev.keycorrect.R
import dev.keycorrect.core.settings.ClipRetention
import dev.keycorrect.core.settings.KeyboardSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardSettingsScreen(
    contentPadding: PaddingValues,
    container: AppContainer,
    settings: KeyboardSettings,
    onUpdate: ((KeyboardSettings) -> KeyboardSettings) -> Unit,
) {
    var confirmingClear by rememberSaveable { mutableStateOf(false) }
    SettingsPage(contentPadding) {
        SwitchRow(
            title = stringResource(R.string.clipboard_history),
            supportingText = stringResource(R.string.clipboard_history_summary),
            checked = settings.clipboardHistory,
            onCheckedChange = { v -> onUpdate { it.copy(clipboardHistory = v) } },
        )

        SectionHeader(stringResource(R.string.clipboard_section_limits))
        IntSliderRow(
            title = stringResource(R.string.clipboard_max_items_title),
            value = settings.clipboardMaxItems,
            range = KeyboardSettings.CLIPBOARD_MAX_ITEMS_RANGE,
            valueLabel = { pluralStringResource(R.plurals.clipboard_max_items, it, it) },
            onValueCommitted = { n -> onUpdate { it.copy(clipboardMaxItems = n) } },
            supportingText = stringResource(R.string.clipboard_max_items_summary),
            enabled = settings.clipboardHistory,
        )
        RetentionRow(settings, onUpdate)
        SwitchRow(
            title = stringResource(R.string.clipboard_images),
            supportingText = stringResource(R.string.clipboard_images_summary),
            checked = settings.clipboardImages,
            onCheckedChange = { v -> onUpdate { it.copy(clipboardImages = v) } },
            enabled = settings.clipboardHistory,
        )

        SectionHeader(stringResource(R.string.clipboard_section_clear))
        Text(
            stringResource(R.string.clipboard_clear_summary),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        OutlinedButton(
            onClick = { confirmingClear = true },
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Text(stringResource(R.string.clipboard_clear))
        }
    }
    if (confirmingClear) {
        ClearConfirmDialog(
            onDismiss = { confirmingClear = false },
            onConfirm = {
                confirmingClear = false
                container.clipboardEnvironment.clearAll()
            },
        )
    }
}

@Composable
private fun ClearConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clipboard_clear_confirm_title)) },
        text = { Text(stringResource(R.string.clipboard_clear_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.clipboard_clear_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.data_action_cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RetentionRow(
    settings: KeyboardSettings,
    onUpdate: ((KeyboardSettings) -> KeyboardSettings) -> Unit,
) {
    Text(
        stringResource(R.string.clipboard_retention),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
    )
    val retentions = ClipRetention.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        retentions.forEachIndexed { index, retention ->
            SegmentedButton(
                selected = settings.clipboardRetention == retention,
                onClick = { onUpdate { it.copy(clipboardRetention = retention) } },
                enabled = settings.clipboardHistory,
                shape = SegmentedButtonDefaults.itemShape(index, retentions.size),
            ) {
                Text(stringResource(retentionLabel(retention)))
            }
        }
    }
}

private fun retentionLabel(retention: ClipRetention): Int =
    when (retention) {
        ClipRetention.Hour -> R.string.clipboard_retention_hour
        ClipRetention.Day -> R.string.clipboard_retention_day
        ClipRetention.Week -> R.string.clipboard_retention_week
        ClipRetention.Month -> R.string.clipboard_retention_month
    }
