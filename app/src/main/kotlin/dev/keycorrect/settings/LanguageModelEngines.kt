@file:Suppress("InjectDispatcher") // package-manager lookups off the main thread

package dev.keycorrect.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.keycorrect.R
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.neural.AndroidPluginSource
import dev.keycorrect.neural.EngineApprovals
import dev.keycorrect.neural.EnginePlugin
import dev.keycorrect.neural.EnginePlugins
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Choose the built-in engine or an installed plugin; a plugin must be approved first. */
@Composable
internal fun EnginesSection(
    settings: KeyboardSettings,
    onUpdate: ((KeyboardSettings) -> KeyboardSettings) -> Unit,
) {
    val context = LocalContext.current
    val plugins by
        produceState(emptyList<EnginePlugin>(), settings.approvedEngines) {
            value =
                withContext(Dispatchers.IO) {
                    EnginePlugins(AndroidPluginSource(context)).list(settings.approvedEngines)
                }
        }
    var approving by remember { mutableStateOf<EnginePlugin?>(null) }

    SectionHeader(stringResource(R.string.lm_section_engines))
    val active = plugins.firstOrNull { it.component == settings.neuralEngine && it.approved }
    ChoiceRow(
        selected = active == null,
        title = stringResource(R.string.lm_engine_builtin),
        summary = stringResource(R.string.lm_engine_builtin_summary),
        onSelect = { onUpdate { it.copy(neuralEngine = KeyboardSettings.ENGINE_BUILTIN) } },
    )
    for (plugin in plugins) {
        val changed =
            !plugin.approved &&
                settings.approvedEngines.any { it.startsWith("${plugin.component}|") }
        ChoiceRow(
            selected = plugin.component == active?.component,
            title = plugin.label,
            summary = pluginSummary(plugin, changed),
            onSelect = {
                if (plugin.approved) onUpdate { it.copy(neuralEngine = plugin.component) }
                else approving = plugin
            },
            trailing =
                if (plugin.approved) {
                    {
                        TextButton(
                            onClick = {
                                onUpdate {
                                    it.copy(
                                        approvedEngines =
                                            EngineApprovals.revoke(
                                                it.approvedEngines,
                                                plugin.component,
                                            ),
                                        neuralEngine =
                                            if (it.neuralEngine == plugin.component) {
                                                KeyboardSettings.ENGINE_BUILTIN
                                            } else {
                                                it.neuralEngine
                                            },
                                    )
                                }
                            }
                        ) {
                            Text(stringResource(R.string.lm_engine_remove))
                        }
                    }
                } else null,
        )
    }
    if (plugins.isEmpty()) {
        Text(
            stringResource(R.string.lm_engine_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }

    approving?.let { plugin ->
        ApprovalDialog(
            plugin = plugin,
            onDismiss = { approving = null },
            onApprove = { cert ->
                approving = null
                onUpdate {
                    it.copy(
                        approvedEngines =
                            EngineApprovals.approve(it.approvedEngines, plugin.component, cert),
                        neuralEngine = plugin.component,
                    )
                }
            },
        )
    }
}

@Composable
private fun pluginSummary(plugin: EnginePlugin, certificateChanged: Boolean): String {
    val state =
        when {
            plugin.approved -> stringResource(R.string.lm_engine_approved)
            certificateChanged -> stringResource(R.string.lm_engine_cert_changed)
            else -> stringResource(R.string.lm_engine_not_approved)
        }
    return "${plugin.packageName}\n$state"
}

/**
 * The approval dialog: who the plugin is, its certificate, and, prominently, whether it can use
 * the internet. Approving pins the package name and certificate.
 */
@Composable
private fun ApprovalDialog(
    plugin: EnginePlugin,
    onDismiss: () -> Unit,
    onApprove: (cert: String) -> Unit,
) {
    val cert = plugin.cert
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lm_approve_title, plugin.label)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(stringResource(R.string.lm_approve_package, plugin.packageName))
                if (cert != null) {
                    Text(
                        stringResource(R.string.lm_approve_cert, EngineApprovals.shortCert(cert)),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    Text(
                        stringResource(R.string.lm_approve_no_cert),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (plugin.requestsInternet) {
                    Text(
                        stringResource(R.string.lm_approve_internet),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                Text(
                    stringResource(R.string.lm_approve_body),
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            if (cert != null) {
                TextButton(onClick = { onApprove(cert) }) {
                    Text(stringResource(R.string.lm_approve_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.data_action_cancel)) }
        },
    )
}
