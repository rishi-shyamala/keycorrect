package dev.keycorrect.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.keycorrect.R
import dev.keycorrect.core.settings.KeyboardSettings

/**
 * Suggestion and correction switches. Everything except the master "Show suggestions" switch only
 * makes sense while suggestions are on, so those rows are disabled (keeping their stored value)
 * when it is off.
 */
@Composable
fun TextCorrectionScreen(
    contentPadding: PaddingValues,
    settings: KeyboardSettings,
    onUpdate: ((KeyboardSettings) -> KeyboardSettings) -> Unit,
) {
    val on = settings.suggestionsEnabled
    val dependentHint = stringResource(R.string.text_correction_requires_suggestions)
    fun summary(text: String) = if (on) text else "$text $dependentHint"

    SettingsPage(contentPadding) {
        SwitchRow(
            title = stringResource(R.string.text_correction_suggestions),
            supportingText = stringResource(R.string.text_correction_suggestions_summary),
            checked = on,
            onCheckedChange = { v -> onUpdate { it.copy(suggestionsEnabled = v) } },
        )
        SwitchRow(
            title = stringResource(R.string.text_correction_autocorrect),
            supportingText = summary(stringResource(R.string.text_correction_autocorrect_summary)),
            checked = settings.autocorrectEnabled,
            enabled = on,
            onCheckedChange = { v -> onUpdate { it.copy(autocorrectEnabled = v) } },
        )
        SwitchRow(
            title = stringResource(R.string.text_correction_enter_as_typed),
            supportingText =
                summary(stringResource(R.string.text_correction_enter_as_typed_summary)),
            checked = settings.enterKeepsTyped,
            enabled = on && settings.autocorrectEnabled,
            onCheckedChange = { v -> onUpdate { it.copy(enterKeepsTyped = v) } },
        )
        SwitchRow(
            title = stringResource(R.string.text_correction_next_word),
            supportingText = summary(stringResource(R.string.text_correction_next_word_summary)),
            checked = settings.nextWordPredictions,
            enabled = on,
            onCheckedChange = { v -> onUpdate { it.copy(nextWordPredictions = v) } },
        )
        SwitchRow(
            title = stringResource(R.string.text_correction_phrases),
            supportingText = summary(stringResource(R.string.text_correction_phrases_summary)),
            checked = settings.phraseCompletion,
            enabled = on,
            onCheckedChange = { v -> onUpdate { it.copy(phraseCompletion = v) } },
        )
        SwitchRow(
            title = stringResource(R.string.text_correction_auto_space),
            supportingText = summary(stringResource(R.string.text_correction_auto_space_summary)),
            checked = settings.autoSpaceAfterPick,
            enabled = on,
            onCheckedChange = { v -> onUpdate { it.copy(autoSpaceAfterPick = v) } },
        )

        TryItSection()
    }
}
