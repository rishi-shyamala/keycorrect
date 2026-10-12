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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.keycorrect.R
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.core.settings.SkinTone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiSettingsScreen(
    contentPadding: PaddingValues,
    settings: KeyboardSettings,
    onUpdate: ((KeyboardSettings) -> KeyboardSettings) -> Unit,
) {
    SettingsPage(contentPadding) {
        SectionHeader(stringResource(R.string.emoji_section_suggestions))
        SwitchRow(
            title = stringResource(R.string.emoji_suggestions),
            supportingText = stringResource(R.string.emoji_suggestions_summary),
            checked = settings.emojiSuggestions,
            onCheckedChange = { v -> onUpdate { it.copy(emojiSuggestions = v) } },
        )

        SectionHeader(stringResource(R.string.emoji_section_skin_tone))
        Text(
            stringResource(R.string.emoji_skin_tone_summary),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        val tones = SkinTone.entries
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            tones.forEachIndexed { index, tone ->
                val description = stringResource(toneLabel(tone))
                SegmentedButton(
                    selected = settings.emojiSkinTone == tone,
                    onClick = { onUpdate { it.copy(emojiSkinTone = tone) } },
                    shape = SegmentedButtonDefaults.itemShape(index, tones.size),
                    modifier = Modifier.semantics { contentDescription = description },
                ) {
                    Text(waveFor(tone))
                }
            }
        }
    }
}

/** U+1F44B waving hand, with the Fitzpatrick modifier for [tone] (none for the plain form). */
internal fun waveFor(tone: SkinTone): String {
    val wave = "👋"
    val modifier =
        when (tone) {
            SkinTone.None -> ""
            SkinTone.Light -> "🏻"
            SkinTone.MediumLight -> "🏼"
            SkinTone.Medium -> "🏽"
            SkinTone.MediumDark -> "🏾"
            SkinTone.Dark -> "🏿"
        }
    return wave + modifier
}

private fun toneLabel(tone: SkinTone): Int =
    when (tone) {
        SkinTone.None -> R.string.emoji_tone_none
        SkinTone.Light -> R.string.emoji_tone_light
        SkinTone.MediumLight -> R.string.emoji_tone_medium_light
        SkinTone.Medium -> R.string.emoji_tone_medium
        SkinTone.MediumDark -> R.string.emoji_tone_medium_dark
        SkinTone.Dark -> R.string.emoji_tone_dark
    }
