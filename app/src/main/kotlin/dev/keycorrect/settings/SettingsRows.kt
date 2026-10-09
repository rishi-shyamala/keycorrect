package dev.keycorrect.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.keycorrect.R
import kotlin.math.roundToInt

/*
 * Reusable building blocks for settings screens. Phase 8 adds dozens of settings; each should be
 * one call to a row below inside a SettingsPage.
 */

/** Maximum content width on large screens (tablets, unfolded devices). */
internal val MaxContentWidth = 640.dp

/**
 * Scrollable page whose content is capped at [MaxContentWidth] and centred on wide screens.
 * [contentPadding] should be the Scaffold's inner padding so edge-to-edge insets are respected.
 */
@Composable
fun SettingsPage(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier =
                Modifier.widthIn(max = MaxContentWidth).fillMaxWidth().padding(bottom = 24.dp)
        ) {
            content()
        }
    }
}

/** Small primary-coloured heading that groups related rows. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
                .semantics { heading() },
    )
}

/** A row that toggles a boolean. The whole row is the touch target and is exposed as a switch. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supportingText?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = rowColors(enabled),
        modifier =
            modifier.toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
    )
}

/** A row that opens another screen. */
@Composable
fun NavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supportingText?.let { { Text(it) } },
        trailingContent = {
            Text(
                "›",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics {},
            )
        },
        colors = rowColors(true),
        modifier = modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

/**
 * A labelled slider with a live value label.
 *
 * While dragging only local state changes; [onValueCommitted] fires once when the gesture ends so
 * callers can persist without a write per frame. [value] is the persisted value; local state resets
 * to it whenever it changes. [snap] rounds raw slider positions (for discrete values).
 */
@Composable
fun SliderRow(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: @Composable (Float) -> String,
    onValueCommitted: (Float) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
    snap: (Float) -> Float = { it },
) {
    var live by remember(value) { mutableFloatStateOf(value) }
    val label = valueLabel(live)
    val titleColor = rowTextColor(enabled, MaterialTheme.colorScheme.onSurface)
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor,
                modifier = Modifier.weight(1f),
            )
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = rowTextColor(enabled, MaterialTheme.colorScheme.primary),
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        if (supportingText != null) {
            Text(
                supportingText,
                style = MaterialTheme.typography.bodyMedium,
                color = rowTextColor(enabled, MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        Slider(
            value = live,
            onValueChange = { live = snap(it).coerceIn(valueRange) },
            valueRange = valueRange,
            enabled = enabled,
            onValueChangeFinished = { if (live != value) onValueCommitted(live) },
            modifier =
                Modifier.fillMaxWidth().semantics {
                    contentDescription = title
                    stateDescription = label
                },
        )
    }
}

/** [SliderRow] over integers in [range], moving in increments of [step]. */
@Composable
fun IntSliderRow(
    title: String,
    value: Int,
    range: IntRange,
    valueLabel: @Composable (Int) -> String,
    onValueCommitted: (Int) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
    step: Int = 1,
) {
    SliderRow(
        title = title,
        value = value.toFloat(),
        valueRange = range.first.toFloat()..range.last.toFloat(),
        valueLabel = { valueLabel(it.roundToInt()) },
        onValueCommitted = { onValueCommitted(it.roundToInt()) },
        modifier = modifier,
        supportingText = supportingText,
        enabled = enabled,
        snap = { range.first + ((it - range.first) / step).roundToInt() * step.toFloat() },
    )
}

/** Section header plus a [TryItField], for testing the keyboard with the current settings. */
@Composable
fun TryItSection(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.try_it_title))
        TryItField()
    }
}

/** Free-text field for trying the keyboard. */
@Composable
fun TryItField(modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        placeholder = { Text(stringResource(R.string.setup_try_hint)) },
        minLines = 3,
    )
}

@Composable
private fun rowColors(enabled: Boolean) =
    ListItemDefaults.colors(
        containerColor = Color.Transparent,
        headlineColor = rowTextColor(enabled, MaterialTheme.colorScheme.onSurface),
        supportingColor = rowTextColor(enabled, MaterialTheme.colorScheme.onSurfaceVariant),
    )

private fun rowTextColor(enabled: Boolean, base: Color): Color =
    if (enabled) base else base.copy(alpha = 0.38f)
