@file:Suppress("InjectDispatcher") // one-off probes (package manager, engine) in a settings screen

package dev.keycorrect.settings

import android.content.ComponentName
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.keycorrect.AppContainer
import dev.keycorrect.R
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.neural.AndroidPluginSource
import dev.keycorrect.neural.EngineClient
import dev.keycorrect.neural.EngineFailure
import dev.keycorrect.neural.EnginePlugins
import dev.keycorrect.neural.EngineTarget
import dev.keycorrect.neural.LmStatus
import dev.keycorrect.neural.awaitStatus
import dev.keycorrect.neural.chooseEngine
import dev.keycorrect.neural.neuralPackInstalled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Settings → Language model: status, reranking switches, the model and the engine to use. */
@Composable
fun LanguageModelScreen(
    contentPadding: PaddingValues,
    container: AppContainer,
    settings: KeyboardSettings,
    onUpdate: ((KeyboardSettings) -> KeyboardSettings) -> Unit,
) {
    SettingsPage(contentPadding) {
        Text(
            stringResource(R.string.lm_intro),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        StatusLine(container, settings)

        SwitchRow(
            title = stringResource(R.string.lm_rerank),
            supportingText = stringResource(R.string.lm_rerank_summary),
            checked = settings.neuralRerank,
            onCheckedChange = { v -> onUpdate { it.copy(neuralRerank = v) } },
        )
        SwitchRow(
            title = stringResource(R.string.lm_phrases),
            supportingText = stringResource(R.string.lm_phrases_summary),
            checked = settings.neuralPhrases,
            onCheckedChange = { v -> onUpdate { it.copy(neuralPhrases = v) } },
        )
        SliderRow(
            title = stringResource(R.string.lm_strength),
            supportingText = stringResource(R.string.lm_strength_summary),
            value = settings.lmStrength,
            valueRange = 0f..1f,
            valueLabel = { strengthLabel(it) },
            onValueCommitted = { v -> onUpdate { it.copy(lmStrength = v) } },
            enabled = settings.neuralRerank,
            snap = { snapStrength(it) },
        )

        ModelsSection(container, settings, onUpdate)
        EnginesSection(settings, onUpdate)
    }
}

/** The slider positions are multiples of 0.05. */
internal fun snapStrength(raw: Float): Float =
    (Math.round(raw / STRENGTH_STEP) * STRENGTH_STEP).coerceIn(0f, 1f)

private const val STRENGTH_STEP = 0.05f
private const val PERCENT = 100

@Composable
private fun strengthLabel(value: Float): String =
    if (value <= 0f) stringResource(R.string.lm_strength_off)
    else stringResource(R.string.lm_strength_percent, Math.round(value * PERCENT))

/** What the engine that would run says about itself, queried off the main thread. */
@Composable
private fun StatusLine(container: AppContainer, settings: KeyboardSettings) {
    val context = LocalContext.current
    val status by
        produceState<LmStatus?>(
            initialValue = null,
            settings.neuralEngine,
            settings.neuralModel,
            settings.approvedEngines,
        ) {
            value = null
            value =
                withContext(Dispatchers.IO) {
                    probeStatus(context, container, settings.copy(neuralRerank = true))
                }
        }
    Text(
        statusText(status),
        style = MaterialTheme.typography.bodyLarge,
        modifier =
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics {
                liveRegion = LiveRegionMode.Polite
            },
    )
}

/**
 * Binds the engine the keyboard would use for [settings] briefly and reads its status. Nothing that
 * was typed is involved.
 */
private suspend fun probeStatus(
    context: android.content.Context,
    container: AppContainer,
    settings: KeyboardSettings,
): LmStatus {
    val plugins = EnginePlugins(AndroidPluginSource(context))
    val target =
        chooseEngine(
            neuralRerank = true,
            lmStrength = 1f,
            neuralEngine = settings.neuralEngine,
            builtinAvailable = {
                settings.neuralModel.isNotEmpty() || neuralPackInstalled(context.packageManager)
            },
            pluginApproved = { plugins.isApproved(it, settings.approvedEngines) },
        )
    val client =
        when (target) {
            EngineTarget.None -> null
            EngineTarget.Builtin ->
                EngineClient(context, container.modelStore, modelId = { settings.neuralModel })
            is EngineTarget.Plugin ->
                ComponentName.unflattenFromString(target.component)?.let {
                    EngineClient(
                        context,
                        container.modelStore,
                        modelId = { settings.neuralModel },
                        component = it,
                        configures = false,
                    )
                }
        } ?: return LmStatus.NoModel
    return try {
        awaitStatus(info = { client.info() }, pause = { delay(STATUS_POLL_MS) })
    } finally {
        client.close()
    }
}

private const val STATUS_POLL_MS = 300L

@Composable
private fun statusText(status: LmStatus?): String =
    when (status) {
        null -> stringResource(R.string.lm_status_checking)
        is LmStatus.Ready ->
            status.model?.let { stringResource(R.string.lm_status_ready_model, it) }
                ?: stringResource(R.string.lm_status_ready)
        LmStatus.Loading -> stringResource(R.string.lm_status_loading)
        LmStatus.NoModel -> stringResource(R.string.lm_status_no_model)
        LmStatus.NoAnswer -> stringResource(R.string.lm_status_no_answer)
        is LmStatus.Error -> stringResource(errorMessage(status.reason))
    }

/** The engine's failure codes in plain words; anything else gets the general message. */
internal fun errorMessage(reason: String): Int =
    when (reason) {
        "PathRejected",
        "FileMissing" -> R.string.lm_status_error_file
        "LoadFailed" -> R.string.lm_status_error_load
        "OutOfMemory" -> R.string.lm_status_error_memory
        EngineFailure.UNSUPPORTED_CPU -> R.string.lm_status_error_cpu
        EngineFailure.CRASHED_ON_LOAD -> R.string.lm_status_error_crashed
        else -> R.string.lm_status_error_other
    }

/** A radio row: the left part selects, [trailing] (a button, say) acts on its own. */
@Composable
internal fun ChoiceRow(
    selected: Boolean,
    title: String,
    summary: String?,
    onSelect: () -> Unit,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier =
                Modifier.weight(1f)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                    .padding(vertical = 8.dp),
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (summary != null) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        trailing?.invoke(this)
    }
}
