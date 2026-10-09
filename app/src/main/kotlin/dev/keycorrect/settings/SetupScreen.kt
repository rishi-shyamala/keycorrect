package dev.keycorrect.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.keycorrect.R

@Composable
fun SetupScreen(
    status: ImeStatus,
    onOpenKeyboardSettings: () -> Unit,
    onChooseKeyboard: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier =
                Modifier.safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.setup_title),
                style = MaterialTheme.typography.headlineMedium,
            )
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
            Text(
                stringResource(R.string.setup_try_title),
                style = MaterialTheme.typography.titleMedium,
            )
            var text by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.setup_try_hint)) },
                minLines = 3,
            )
        }
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
