package dev.keycorrect.settings

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
    }
}

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
