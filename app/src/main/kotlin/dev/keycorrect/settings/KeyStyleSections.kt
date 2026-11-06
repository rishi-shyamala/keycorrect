package dev.keycorrect.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import dev.keycorrect.R
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.core.settings.PressStyle
import kotlin.math.roundToInt

/** The corner radius, border and press highlight of the keys. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeyShapeSection(
    settings: KeyboardSettings,
    onUpdate: ((KeyboardSettings) -> KeyboardSettings) -> Unit,
) {
    SectionHeader(stringResource(R.string.theme_section_keys))
    val pillAt = (settings.keyHeightDp - settings.keyGapDp).coerceAtLeast(0) / 2f
    SliderRow(
        title = stringResource(R.string.theme_key_radius),
        supportingText = stringResource(R.string.theme_key_radius_summary),
        value = settings.keyRadiusDp.toFloat(),
        valueRange =
            KeyboardSettings.KEY_RADIUS_DP_RANGE.let { it.first.toFloat()..it.last.toFloat() },
        valueLabel = {
            if (it >= pillAt) {
                stringResource(R.string.theme_key_radius_pill)
            } else {
                stringResource(R.string.value_dp, it.roundToInt())
            }
        },
        onValueCommitted = { v -> onUpdate { it.copy(keyRadiusDp = v.roundToInt()) } },
        snap = { it.roundToInt().toFloat() },
    )
    SwitchRow(
        title = stringResource(R.string.theme_key_border),
        supportingText = stringResource(R.string.theme_key_border_summary),
        checked = settings.keyBorder,
        onCheckedChange = { v -> onUpdate { it.copy(keyBorder = v) } },
    )
    Text(
        stringResource(R.string.theme_press_style),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        PressStyle.entries.forEachIndexed { index, style ->
            SegmentedButton(
                selected = settings.pressStyle == style,
                onClick = { onUpdate { it.copy(pressStyle = style) } },
                shape = SegmentedButtonDefaults.itemShape(index, PressStyle.entries.size),
            ) {
                Text(stringResource(pressLabel(style)))
            }
        }
    }
}

/** The label size and weight, the corner hints and the key preview popup. */
@Composable
internal fun KeyLabelsSection(
    settings: KeyboardSettings,
    onUpdate: ((KeyboardSettings) -> KeyboardSettings) -> Unit,
) {
    SectionHeader(stringResource(R.string.theme_section_labels))
    SliderRow(
        title = stringResource(R.string.theme_label_size),
        value = settings.labelScale,
        valueRange = KeyboardSettings.LABEL_SCALE_RANGE,
        valueLabel = { stringResource(R.string.value_percent, (it * PERCENT).roundToInt()) },
        onValueCommitted = { v -> onUpdate { it.copy(labelScale = v) } },
        snap = { (it * LABEL_STEPS).roundToInt() / LABEL_STEPS },
    )
    SwitchRow(
        title = stringResource(R.string.theme_bold_labels),
        checked = settings.boldLabels,
        onCheckedChange = { v -> onUpdate { it.copy(boldLabels = v) } },
    )
    SwitchRow(
        title = stringResource(R.string.theme_hint_labels),
        supportingText = stringResource(R.string.theme_hint_labels_summary),
        checked = settings.symbolHints,
        onCheckedChange = { v -> onUpdate { it.copy(symbolHints = v) } },
    )
    SwitchRow(
        title = stringResource(R.string.theme_key_preview),
        supportingText = stringResource(R.string.theme_key_preview_summary),
        checked = settings.keyPreview,
        onCheckedChange = { v -> onUpdate { it.copy(keyPreview = v) } },
    )
}

@StringRes
private fun pressLabel(style: PressStyle): Int =
    when (style) {
        PressStyle.Shade -> R.string.theme_press_shade
        PressStyle.Outline -> R.string.theme_press_outline
        PressStyle.None -> R.string.theme_press_none
    }

private const val PERCENT = 100
private const val LABEL_STEPS = 20f
