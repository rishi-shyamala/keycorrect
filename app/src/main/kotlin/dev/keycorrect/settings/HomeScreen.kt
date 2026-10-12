package dev.keycorrect.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.keycorrect.R

@Composable
@Suppress("LongMethod") // A flat list of navigation rows.
fun HomeScreen(
    contentPadding: PaddingValues,
    status: ImeStatus,
    onNavigate: (SettingsDestination) -> Unit,
) {
    SettingsPage(contentPadding) {
        if (!(status.enabled && status.selected)) {
            SetupCard(onSetup = { onNavigate(SettingsDestination.Setup) })
        }
        NavRow(
            title = stringResource(R.string.keyboard_title),
            supportingText = stringResource(R.string.keyboard_summary),
            onClick = { onNavigate(SettingsDestination.Keyboard) },
        )
        NavRow(
            title = stringResource(R.string.editing_title),
            supportingText = stringResource(R.string.editing_summary),
            onClick = { onNavigate(SettingsDestination.Editing) },
        )
        NavRow(
            title = stringResource(R.string.emoji_title),
            supportingText = stringResource(R.string.emoji_summary),
            onClick = { onNavigate(SettingsDestination.Emoji) },
        )
        NavRow(
            title = stringResource(R.string.clipboard_title),
            supportingText = stringResource(R.string.clipboard_summary),
            onClick = { onNavigate(SettingsDestination.Clipboard) },
        )
        NavRow(
            title = stringResource(R.string.text_correction_title),
            supportingText = stringResource(R.string.text_correction_summary),
            onClick = { onNavigate(SettingsDestination.TextCorrection) },
        )
        NavRow(
            title = stringResource(R.string.dictionary_title),
            supportingText = stringResource(R.string.dictionary_summary),
            onClick = { onNavigate(SettingsDestination.PersonalDictionary) },
        )
        NavRow(
            title = stringResource(R.string.shortcuts_title),
            supportingText = stringResource(R.string.shortcuts_summary),
            onClick = { onNavigate(SettingsDestination.Shortcuts) },
        )
        NavRow(
            title = stringResource(R.string.learning_title),
            supportingText = stringResource(R.string.learning_summary),
            onClick = { onNavigate(SettingsDestination.Learning) },
        )
        NavRow(
            title = stringResource(R.string.feedback_title),
            supportingText = stringResource(R.string.feedback_summary),
            onClick = { onNavigate(SettingsDestination.Feedback) },
        )
        NavRow(
            title = stringResource(R.string.advanced_title),
            supportingText = stringResource(R.string.advanced_summary),
            onClick = { onNavigate(SettingsDestination.Advanced) },
        )
        NavRow(
            title = stringResource(R.string.about_title),
            supportingText = stringResource(R.string.about_summary),
            onClick = { onNavigate(SettingsDestination.About) },
        )
    }
}

@Composable
private fun SetupCard(onSetup: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.home_setup_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                stringResource(R.string.home_setup_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onSetup) { Text(stringResource(R.string.home_setup_action)) }
        }
    }
}
