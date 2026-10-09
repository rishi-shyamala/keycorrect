package dev.keycorrect.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.keycorrect.R
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.feature.keyboard.FeedbackKind
import dev.keycorrect.feature.keyboard.KeyFeedback
import kotlin.math.roundToInt

@Composable
fun FeedbackSettingsScreen(
    contentPadding: PaddingValues,
    settings: KeyboardSettings,
    onUpdate: ((KeyboardSettings) -> KeyboardSettings) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val feedback = remember(context) { KeyFeedback(context) }
    val hasAmplitudeControl = remember(feedback) { feedback.hasAmplitudeControl }

    val strengthEnabled =
        settings.hapticsEnabled && settings.hapticDurationMs > 0 && hasAmplitudeControl
    val strengthNote =
        when {
            !hasAmplitudeControl -> stringResource(R.string.feedback_strength_unsupported)
            settings.hapticDurationMs == 0 ->
                stringResource(R.string.feedback_strength_needs_duration)
            else -> null
        }

    SettingsPage(contentPadding) {
        SectionHeader(stringResource(R.string.feedback_section_vibration))
        SwitchRow(
            title = stringResource(R.string.feedback_vibration),
            checked = settings.hapticsEnabled,
            onCheckedChange = { v -> onUpdate { it.copy(hapticsEnabled = v) } },
        )
        IntSliderRow(
            title = stringResource(R.string.feedback_vibration_duration),
            value = settings.hapticDurationMs,
            range = KeyboardSettings.HAPTIC_DURATION_MS_RANGE,
            enabled = settings.hapticsEnabled,
            valueLabel = {
                if (it == 0) stringResource(R.string.value_system_default)
                else stringResource(R.string.value_ms, it)
            },
            onValueCommitted = { v -> onUpdate { it.copy(hapticDurationMs = v) } },
        )
        IntSliderRow(
            title = stringResource(R.string.feedback_vibration_strength),
            value = settings.hapticStrength,
            range = KeyboardSettings.HAPTIC_STRENGTH_RANGE,
            enabled = strengthEnabled,
            supportingText = strengthNote,
            valueLabel = {
                if (it == 0) stringResource(R.string.value_device_default) else it.toString()
            },
            onValueCommitted = { v -> onUpdate { it.copy(hapticStrength = v) } },
        )
        OutlinedButton(
            onClick = { feedback.haptic(view, settings) },
            enabled = settings.hapticsEnabled,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Text(stringResource(R.string.feedback_test_vibration))
        }

        SectionHeader(stringResource(R.string.feedback_section_sound))
        SwitchRow(
            title = stringResource(R.string.feedback_sound),
            supportingText = stringResource(R.string.feedback_sound_summary),
            checked = settings.soundEnabled,
            onCheckedChange = { v -> onUpdate { it.copy(soundEnabled = v) } },
        )
        SliderRow(
            title = stringResource(R.string.feedback_sound_volume),
            value = settings.soundVolume,
            valueRange = KeyboardSettings.SOUND_VOLUME_RANGE,
            enabled = settings.soundEnabled,
            snap = { (it * 100).roundToInt() / 100f },
            valueLabel = { stringResource(R.string.value_percent, (it * 100).roundToInt()) },
            onValueCommitted = { v -> onUpdate { it.copy(soundVolume = v) } },
        )
        OutlinedButton(
            onClick = { feedback.sound(FeedbackKind.Standard, settings) },
            enabled = settings.soundEnabled,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Text(stringResource(R.string.feedback_test_sound))
        }

        TryItSection()
    }
}
