package dev.keycorrect.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.keycorrect.R
import dev.keycorrect.core.settings.KeyboardSettings
import kotlin.math.roundToInt

private const val PERCENT = 100
private const val BOTTOM_KEY_STEPS_PER_UNIT = 20f // 5 % steps

@Composable
fun KeyboardSettingsScreen(
    contentPadding: PaddingValues,
    settings: KeyboardSettings,
    onUpdate: ((KeyboardSettings) -> KeyboardSettings) -> Unit,
) {
    SettingsPage(contentPadding) {
        SizeRows(settings, onUpdate)
        SwitchRow(
            title = stringResource(R.string.keyboard_number_row),
            supportingText = stringResource(R.string.keyboard_number_row_summary),
            checked = settings.numberRow,
            onCheckedChange = { v -> onUpdate { it.copy(numberRow = v) } },
        )
        SwitchRow(
            title = stringResource(R.string.keyboard_key_preview),
            supportingText = stringResource(R.string.keyboard_key_preview_summary),
            checked = settings.keyPreview,
            onCheckedChange = { v -> onUpdate { it.copy(keyPreview = v) } },
        )

        SectionHeader(stringResource(R.string.keyboard_section_behaviour))
        IntSliderRow(
            title = stringResource(R.string.keyboard_long_press_delay),
            value = settings.longPressDelayMs,
            range = KeyboardSettings.LONG_PRESS_DELAY_MS_RANGE,
            step = 10,
            valueLabel = { stringResource(R.string.value_ms, it) },
            onValueCommitted = { v -> onUpdate { it.copy(longPressDelayMs = v) } },
        )
        SwitchRow(
            title = stringResource(R.string.keyboard_auto_capitalize),
            supportingText = stringResource(R.string.keyboard_auto_capitalize_summary),
            checked = settings.autoCapitalize,
            onCheckedChange = { v -> onUpdate { it.copy(autoCapitalize = v) } },
        )
        SwitchRow(
            title = stringResource(R.string.keyboard_inline_autofill),
            supportingText = stringResource(R.string.keyboard_inline_autofill_summary),
            checked = settings.inlineAutofill,
            onCheckedChange = { v -> onUpdate { it.copy(inlineAutofill = v) } },
        )

        TryItSection()
    }
}

/** The Appearance header and the sliders for key height, bottom-key width, padding and gap. */
@Composable
private fun SizeRows(
    settings: KeyboardSettings,
    onUpdate: ((KeyboardSettings) -> KeyboardSettings) -> Unit,
) {
    SectionHeader(stringResource(R.string.keyboard_section_appearance))
    IntSliderRow(
        title = stringResource(R.string.keyboard_key_height),
        value = settings.keyHeightDp,
        range = KeyboardSettings.KEY_HEIGHT_DP_RANGE,
        valueLabel = { stringResource(R.string.value_dp, it) },
        onValueCommitted = { v -> onUpdate { it.copy(keyHeightDp = v) } },
    )
    SliderRow(
        title = stringResource(R.string.keyboard_bottom_key_width),
        supportingText = stringResource(R.string.keyboard_bottom_key_width_summary),
        value = settings.bottomKeyWidth,
        valueRange = KeyboardSettings.BOTTOM_KEY_WIDTH_RANGE,
        valueLabel = { stringResource(R.string.value_percent, (it * PERCENT).roundToInt()) },
        onValueCommitted = { v -> onUpdate { it.copy(bottomKeyWidth = v) } },
        snap = { (it * BOTTOM_KEY_STEPS_PER_UNIT).roundToInt() / BOTTOM_KEY_STEPS_PER_UNIT },
    )
    IntSliderRow(
        title = stringResource(R.string.keyboard_edge_padding),
        value = settings.edgePaddingDp,
        range = KeyboardSettings.EDGE_PADDING_DP_RANGE,
        valueLabel = { stringResource(R.string.value_dp, it) },
        onValueCommitted = { v -> onUpdate { it.copy(edgePaddingDp = v) } },
    )
    IntSliderRow(
        title = stringResource(R.string.keyboard_key_gap),
        value = settings.keyGapDp,
        range = KeyboardSettings.KEY_GAP_DP_RANGE,
        valueLabel = { stringResource(R.string.value_dp, it) },
        onValueCommitted = { v -> onUpdate { it.copy(keyGapDp = v) } },
    )
}
