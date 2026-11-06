package dev.keycorrect.settings

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.keycorrect.R
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.core.settings.SizeProfile
import dev.keycorrect.core.settings.SizeSettings
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
            title = stringResource(R.string.keyboard_symbol_hints),
            supportingText = stringResource(R.string.keyboard_symbol_hints_summary),
            checked = settings.symbolHints,
            onCheckedChange = { v -> onUpdate { it.copy(symbolHints = v) } },
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

/** The profile of the screen this device is on now. */
@Composable
private fun currentSizeProfile(): SizeProfile {
    val config = LocalConfiguration.current
    return SizeProfile.of(
        config.smallestScreenWidthDp,
        config.orientation == Configuration.ORIENTATION_LANDSCAPE,
    )
}

@StringRes
private fun SizeProfile.labelRes(): Int =
    when {
        !tablet && !landscape -> R.string.size_profile_phone_portrait
        !tablet -> R.string.size_profile_phone_landscape
        !landscape -> R.string.size_profile_tablet_portrait
        else -> R.string.size_profile_tablet_landscape
    }

/** Which profile's sizes are edited: this device's by default, any of the four on request. */
@Composable
private fun ProfilePicker(current: SizeProfile, editing: SizeProfile, onPick: (SizeProfile) -> Unit) {
    Text(
        stringResource(R.string.keyboard_sizes_editing, stringResource(editing.labelRes())),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Text(
        stringResource(R.string.keyboard_sizes_here, stringResource(current.labelRes())),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    SizeProfile.ALL.chunked(2).forEach { pair ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        ) {
            pair.forEach { profile ->
                FilterChip(
                    selected = profile == editing,
                    onClick = { onPick(profile) },
                    label = { Text(stringResource(profile.labelRes())) },
                )
            }
        }
    }
    Text(
        stringResource(R.string.keyboard_sizes_summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** The Appearance header and the size rows for the profile being edited. */
@Composable
private fun SizeRows(
    settings: KeyboardSettings,
    onUpdate: ((KeyboardSettings) -> KeyboardSettings) -> Unit,
) {
    val current = currentSizeProfile()
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val profile = SizeProfile.fromId(editingId) ?: current
    val sizes = settings.sizeFor(profile)
    val update: ((SizeSettings) -> SizeSettings) -> Unit = { t ->
        onUpdate { it.updateSizes(profile, t) }
    }
    SectionHeader(stringResource(R.string.keyboard_section_sizes))
    ProfilePicker(current, profile) { editingId = it.id }
    SectionHeader(stringResource(R.string.keyboard_section_appearance))
    IntSliderRow(
        title = stringResource(R.string.keyboard_key_height),
        value = sizes.keyHeightDp,
        range = KeyboardSettings.KEY_HEIGHT_DP_RANGE,
        valueLabel = { stringResource(R.string.value_dp, it) },
        onValueCommitted = { v -> update { it.copy(keyHeightDp = v) } },
    )
    SliderRow(
        title = stringResource(R.string.keyboard_bottom_key_width),
        supportingText = stringResource(R.string.keyboard_bottom_key_width_summary),
        value = sizes.bottomKeyWidth,
        valueRange = KeyboardSettings.BOTTOM_KEY_WIDTH_RANGE,
        valueLabel = { stringResource(R.string.value_percent, (it * PERCENT).roundToInt()) },
        onValueCommitted = { v -> update { it.copy(bottomKeyWidth = v) } },
        snap = { (it * BOTTOM_KEY_STEPS_PER_UNIT).roundToInt() / BOTTOM_KEY_STEPS_PER_UNIT },
    )
    SliderRow(
        title = stringResource(R.string.keyboard_mode_enter_key_width),
        supportingText = stringResource(R.string.keyboard_mode_enter_key_width_summary),
        value = sizes.modeEnterKeyWidth,
        valueRange = KeyboardSettings.MODE_ENTER_KEY_WIDTH_RANGE,
        valueLabel = { stringResource(R.string.value_percent, (it * PERCENT).roundToInt()) },
        onValueCommitted = { v -> update { it.copy(modeEnterKeyWidth = v) } },
        snap = { (it * BOTTOM_KEY_STEPS_PER_UNIT).roundToInt() / BOTTOM_KEY_STEPS_PER_UNIT },
    )
    IntSliderRow(
        title = stringResource(R.string.keyboard_edge_padding),
        value = sizes.edgePaddingDp,
        range = KeyboardSettings.EDGE_PADDING_DP_RANGE,
        valueLabel = { stringResource(R.string.value_dp, it) },
        onValueCommitted = { v -> update { it.copy(edgePaddingDp = v) } },
    )
    IntSliderRow(
        title = stringResource(R.string.keyboard_key_gap),
        value = sizes.keyGapDp,
        range = KeyboardSettings.KEY_GAP_DP_RANGE,
        valueLabel = { stringResource(R.string.value_dp, it) },
        onValueCommitted = { v -> update { it.copy(keyGapDp = v) } },
    )
    SwitchRow(
        title = stringResource(R.string.keyboard_number_row),
        supportingText = stringResource(R.string.keyboard_number_row_summary),
        checked = sizes.numberRow,
        onCheckedChange = { v -> update { it.copy(numberRow = v) } },
    )
}
