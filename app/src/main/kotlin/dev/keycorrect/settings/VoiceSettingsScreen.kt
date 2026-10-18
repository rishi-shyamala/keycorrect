package dev.keycorrect.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.keycorrect.R
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.core.settings.VoiceEngineChoice
import dev.keycorrect.feature.voicesherpa.VoicePackLocator
import dev.keycorrect.feature.voicesherpa.VoicePackProblem
import dev.keycorrect.feature.voicesherpa.VoicePackResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(
    contentPadding: PaddingValues,
    settings: KeyboardSettings,
    onUpdate: ((KeyboardSettings) -> KeyboardSettings) -> Unit,
) {
    SettingsPage(contentPadding) {
        SwitchRow(
            title = stringResource(R.string.voice_enabled),
            supportingText = stringResource(R.string.voice_enabled_summary),
            checked = settings.voiceEnabled,
            onCheckedChange = { v -> onUpdate { it.copy(voiceEnabled = v) } },
        )

        SectionHeader(stringResource(R.string.voice_section_engine))
        Text(
            stringResource(R.string.voice_engine_summary),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        val choices = VoiceEngineChoice.entries
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            choices.forEachIndexed { index, choice ->
                SegmentedButton(
                    selected = settings.voiceEngine == choice,
                    onClick = { onUpdate { it.copy(voiceEngine = choice) } },
                    shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                ) {
                    Text(
                        stringResource(
                            when (choice) {
                                VoiceEngineChoice.BuiltIn -> R.string.voice_engine_built_in
                                VoiceEngineChoice.PhoneRecognizer -> R.string.voice_engine_phone
                            }
                        )
                    )
                }
            }
        }

        if (settings.voiceEngine == VoiceEngineChoice.PhoneRecognizer) {
            Text(
                stringResource(R.string.voice_engine_phone_warning),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            PackStatus()
        }

        SwitchRow(
            title = stringResource(R.string.voice_auto_punctuation),
            supportingText = stringResource(R.string.voice_auto_punctuation_summary),
            checked = settings.voiceAutoPunctuation,
            onCheckedChange = { v -> onUpdate { it.copy(voiceAutoPunctuation = v) } },
        )

        LimitSliders(settings, onUpdate)
    }
}

/** The long-dictation limits; each slider is clamped by [KeyboardSettings] and off with voice. */
@Composable
private fun LimitSliders(
    settings: KeyboardSettings,
    onUpdate: ((KeyboardSettings) -> KeyboardSettings) -> Unit,
) {
    Column {
        SectionHeader(stringResource(R.string.voice_section_limits))
        val on = settings.voiceEnabled
        IntSliderRow(
            title = stringResource(R.string.voice_phrase_limit),
            supportingText = stringResource(R.string.voice_phrase_limit_summary),
            value = settings.voicePhraseMs,
            range = KeyboardSettings.VOICE_PHRASE_MS_RANGE,
            step = PHRASE_STEP_MS,
            valueLabel = { stringResource(R.string.voice_seconds_value, it / MS_PER_S) },
            onValueCommitted = { v -> onUpdate { it.copy(voicePhraseMs = v) } },
            enabled = on,
        )
        IntSliderRow(
            title = stringResource(R.string.voice_pause_length),
            supportingText = stringResource(R.string.voice_pause_length_summary),
            value = settings.voicePauseMs,
            range = KeyboardSettings.VOICE_PAUSE_MS_RANGE,
            step = PAUSE_STEP_MS,
            valueLabel = { stringResource(R.string.voice_seconds_value, it / MS_PER_S) },
            onValueCommitted = { v -> onUpdate { it.copy(voicePauseMs = v) } },
            enabled = on,
        )
        IntSliderRow(
            title = stringResource(R.string.voice_live_interval),
            supportingText = stringResource(R.string.voice_live_interval_summary),
            value = liveIndex(settings.voiceLiveMs),
            range = 0..LIVE_OFF_INDEX,
            valueLabel = { index ->
                when (index) {
                    0 -> stringResource(R.string.voice_live_auto)
                    LIVE_OFF_INDEX -> stringResource(R.string.voice_off)
                    else -> stringResource(R.string.voice_seconds_value, liveMs(index) / MS_PER_S)
                }
            },
            onValueCommitted = { index -> onUpdate { it.copy(voiceLiveMs = liveMs(index)) } },
            enabled = on,
        )
        AutoStopSlider(settings, onUpdate)
    }
}

@Composable
private fun AutoStopSlider(
    settings: KeyboardSettings,
    onUpdate: ((KeyboardSettings) -> KeyboardSettings) -> Unit,
) {
    IntSliderRow(
        title = stringResource(R.string.voice_auto_stop),
        supportingText = stringResource(R.string.voice_auto_stop_summary),
        value = autoStopIndex(settings.voiceAutoStopSeconds),
        range = KeyboardSettings.VOICE_AUTO_STOP_SECONDS_RANGE.first..AUTO_STOP_OFF_INDEX,
        valueLabel = { index ->
            if (index == AUTO_STOP_OFF_INDEX) stringResource(R.string.voice_off)
            else stringResource(R.string.voice_seconds_whole, index)
        },
        onValueCommitted = { index ->
            onUpdate {
                it.copy(
                    voiceAutoStopSeconds =
                        if (index == AUTO_STOP_OFF_INDEX) KeyboardSettings.VOICE_AUTO_STOP_OFF
                        else index
                )
            }
        },
        enabled = settings.voiceEnabled,
    )
}

private const val MS_PER_S = 1000f
private const val PHRASE_STEP_MS = 500
private const val PAUSE_STEP_MS = 100
private const val LIVE_STEP_MS = 100

/** Live slider positions: 0 is Auto, 1..16 are 0.5 s to 2.0 s, and the last is Off. */
private val LIVE_OFF_INDEX =
    (KeyboardSettings.VOICE_LIVE_MS_RANGE.last - KeyboardSettings.VOICE_LIVE_MS_RANGE.first) /
        LIVE_STEP_MS + 2

private fun liveMs(index: Int): Int =
    when (index) {
        0 -> KeyboardSettings.VOICE_LIVE_AUTO
        LIVE_OFF_INDEX -> KeyboardSettings.VOICE_LIVE_OFF
        else -> KeyboardSettings.VOICE_LIVE_MS_RANGE.first + (index - 1) * LIVE_STEP_MS
    }

private fun liveIndex(ms: Int): Int =
    when {
        ms < 0 -> LIVE_OFF_INDEX
        ms == KeyboardSettings.VOICE_LIVE_AUTO -> 0
        else ->
            1 + (ms - KeyboardSettings.VOICE_LIVE_MS_RANGE.first + LIVE_STEP_MS / 2) / LIVE_STEP_MS
    }

/** Auto-stop slider positions are the seconds (3..30); one past the last is Off. */
private val AUTO_STOP_OFF_INDEX = KeyboardSettings.VOICE_AUTO_STOP_SECONDS_RANGE.last + 1

private fun autoStopIndex(seconds: Int): Int =
    if (seconds <= KeyboardSettings.VOICE_AUTO_STOP_OFF) AUTO_STOP_OFF_INDEX else seconds

/** Whether the voice pack (the built-in engine) is installed and trusted. */
@Suppress("InjectDispatcher") // one-off disk and native-library probe in a settings screen
@Composable
private fun PackStatus() {
    val context = LocalContext.current
    var result by remember { mutableStateOf<VoicePackResult?>(null) }
    LaunchedEffect(Unit) {
        result = withContext(Dispatchers.Default) { VoicePackLocator(context).locate() }
    }
    val text =
        when (val found = result) {
            null -> return
            is VoicePackResult.Available -> R.string.voice_pack_installed
            is VoicePackResult.Unavailable ->
                when (found.problem) {
                    VoicePackProblem.NotInstalled -> R.string.voice_pack_not_installed
                    VoicePackProblem.SignatureMismatch -> R.string.voice_pack_signature_mismatch
                    VoicePackProblem.Broken -> R.string.voice_pack_broken
                }
        }
    Text(
        stringResource(text),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
