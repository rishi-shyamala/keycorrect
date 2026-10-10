package dev.keycorrect.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.keycorrect.R
import dev.keycorrect.core.settings.KeyboardSettings

@Composable
fun AdvancedScreen(
    contentPadding: PaddingValues,
    settings: KeyboardSettings,
    onUpdate: ((KeyboardSettings) -> KeyboardSettings) -> Unit,
) {
    val range = KeyboardSettings.REVERTS_RANGE
    val addAfter = settings.implicitAddReverts.coerceIn(range)
    val stopAfter = settings.suppressCorrectionReverts.coerceIn(range).coerceAtLeast(addAfter)
    // When "add" is at the top of the range "stop" has nowhere to move, so lock it.
    val stopRange = addAfter..range.last
    val stopLocked = stopRange.first >= stopRange.last

    SettingsPage(contentPadding) {
        SectionHeader(stringResource(R.string.advanced_section_undo))
        IntSliderRow(
            title = pluralStringResource(R.plurals.advanced_add_after_undos, addAfter, addAfter),
            value = addAfter,
            range = range,
            valueLabel = { pluralStringResource(R.plurals.advanced_undo_count, it, it) },
            onValueCommitted = { n ->
                // Keep stop >= add; sanitized() also enforces this at persistence time.
                onUpdate {
                    it.copy(
                        implicitAddReverts = n,
                        suppressCorrectionReverts = it.suppressCorrectionReverts.coerceAtLeast(n),
                    )
                }
            },
        )
        IntSliderRow(
            title = pluralStringResource(R.plurals.advanced_stop_after_undos, stopAfter, stopAfter),
            value = stopAfter,
            range = if (stopLocked) range else stopRange,
            enabled = !stopLocked,
            supportingText =
                if (addAfter > range.first) {
                    stringResource(R.string.advanced_stop_at_least_note)
                } else {
                    null
                },
            valueLabel = { pluralStringResource(R.plurals.advanced_undo_count, it, it) },
            onValueCommitted = { m ->
                onUpdate {
                    it.copy(suppressCorrectionReverts = m.coerceAtLeast(it.implicitAddReverts))
                }
            },
        )
        Text(
            stringResource(R.string.advanced_undo_explanation),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        SectionHeader(stringResource(R.string.advanced_section_autocorrect))
        SwitchRow(
            title = stringResource(R.string.advanced_tie_rule),
            supportingText = stringResource(R.string.advanced_tie_rule_summary),
            checked = settings.nonWordTieRule,
            onCheckedChange = { v -> onUpdate { it.copy(nonWordTieRule = v) } },
        )
    }
}
