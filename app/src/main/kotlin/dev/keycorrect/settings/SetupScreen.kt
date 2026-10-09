package dev.keycorrect.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.keycorrect.R

/** Setup wizard: enable the keyboard, select it, then try it. */
@Composable
fun SetupScreen(
    contentPadding: PaddingValues,
    status: ImeStatus,
    onOpenKeyboardSettings: () -> Unit,
    onChooseKeyboard: () -> Unit,
) {
    SettingsPage(contentPadding) {
        Column(
            modifier = Modifier.padding(top = 8.dp).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SetupStep(
                title = stringResource(R.string.setup_enable_title),
                body = stringResource(R.string.setup_enable_body),
                action = stringResource(R.string.setup_enable_action),
                done = status.enabled,
                onAction = onOpenKeyboardSettings,
            )
            SetupStep(
                title = stringResource(R.string.setup_select_title),
                body = stringResource(R.string.setup_select_body),
                action = stringResource(R.string.setup_select_action),
                done = status.selected,
                enabled = status.enabled,
                onAction = onChooseKeyboard,
            )
        }
        Text(
            stringResource(R.string.setup_try_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        )
        TryItField()
    }
}

@Composable
private fun SetupStep(
    title: String,
    body: String,
    action: String,
    done: Boolean,
    onAction: () -> Unit,
    enabled: Boolean = true,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (done) {
                Text(
                    "✓ " + stringResource(R.string.setup_done),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            } else {
                Button(onClick = onAction, enabled = enabled) { Text(action) }
            }
        }
    }
}
