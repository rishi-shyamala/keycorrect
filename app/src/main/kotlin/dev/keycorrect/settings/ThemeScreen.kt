package dev.keycorrect.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.keycorrect.R
import dev.keycorrect.core.designsystem.KeyboardColors
import dev.keycorrect.core.designsystem.ThemeRole
import dev.keycorrect.core.designsystem.ContrastPair
import dev.keycorrect.core.designsystem.contrastProblems
import dev.keycorrect.core.designsystem.toSeedArgb
import dev.keycorrect.core.designsystem.resolveKeyboardTheme
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.core.settings.PressStyle
import dev.keycorrect.core.settings.ThemeColors
import dev.keycorrect.core.settings.ThemeSource
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ThemeScreen(
    contentPadding: PaddingValues,
    settings: KeyboardSettings,
    onUpdate: ((KeyboardSettings) -> KeyboardSettings) -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    var editDark by rememberSaveable { mutableStateOf(systemDark) }
    var role by rememberSaveable { mutableStateOf(ThemeRole.Key) }
    val lightBase = remember(context) { dynamicLightColorScheme(context) }
    val darkBase = remember(context) { dynamicDarkColorScheme(context) }
    val source = settings.themeSource

    fun colorsFor(dark: Boolean): KeyboardColors =
        resolveKeyboardTheme(
                source,
                dark,
                if (dark) darkBase else lightBase,
                if (dark) settings.customDarkColors else settings.customLightColors,
            )
            .colors

    SettingsPage(contentPadding) {
        SectionHeader(stringResource(R.string.theme_section_preview))
        ThemeModeToggle(editDark) { editDark = it }
        ThemePreview(colorsFor(editDark), settings)

        SectionHeader(stringResource(R.string.theme_section_source))
        for (option in ThemeSource.entries) {
            SourceRow(
                label = stringResource(sourceLabel(option)),
                summary = stringResource(sourceSummary(option)),
                selected = source == option,
                onSelect = {
                    onUpdate { s ->
                        if (option != ThemeSource.Custom) {
                            s.copy(themeSource = option)
                        } else {
                            // Start the custom colors from what is showing now: the main
                            // four set, the others derived from them until changed.
                            s.copy(
                                themeSource = option,
                                customLightColors =
                                    s.customLightColors.ifEmpty { colorsFor(false).toSeedArgb() },
                                customDarkColors =
                                    s.customDarkColors.ifEmpty { colorsFor(true).toSeedArgb() },
                            )
                        }
                    }
                },
            )
        }

        if (source == ThemeSource.Custom) {
            val stored = if (editDark) settings.customDarkColors else settings.customLightColors
            CustomEditor(colorsFor(editDark), stored, editDark, role, { role = it }, onUpdate)
        }

        KeyShapeSection(settings, onUpdate)
        KeyLabelsSection(settings, onUpdate)
    }
}

/** The corner radius, border and press highlight of the keys. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyShapeSection(
    settings: KeyboardSettings,
    onUpdate: ((KeyboardSettings) -> KeyboardSettings) -> Unit,
) {
    SectionHeader(stringResource(R.string.theme_section_keys))
    val pillAt = (settings.keyHeightDp - settings.keyGapDp).coerceAtLeast(0) / 2f
    SliderRow(
        title = stringResource(R.string.theme_key_radius),
        supportingText = stringResource(R.string.theme_key_radius_summary),
        value = settings.keyRadiusDp.toFloat(),
        valueRange = KeyboardSettings.KEY_RADIUS_DP_RANGE.let { it.first.toFloat()..it.last.toFloat() },
        valueLabel = {
            if (it >= pillAt) {
                stringResource(R.string.theme_key_radius_pill)
            } else {
                stringResource(R.string.value_dp, it.roundToInt())
            }
        },
        onValueCommitted = { v -> onUpdate { it.copy(keyRadiusDp = v.roundToInt()) } },
        snap = { it.roundToInt().toFloat() },
    )
    SwitchRow(
        title = stringResource(R.string.theme_key_border),
        supportingText = stringResource(R.string.theme_key_border_summary),
        checked = settings.keyBorder,
        onCheckedChange = { v -> onUpdate { it.copy(keyBorder = v) } },
    )
    Text(
        stringResource(R.string.theme_press_style),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        PressStyle.entries.forEachIndexed { index, style ->
            SegmentedButton(
                selected = settings.pressStyle == style,
                onClick = { onUpdate { it.copy(pressStyle = style) } },
                shape = SegmentedButtonDefaults.itemShape(index, PressStyle.entries.size),
            ) {
                Text(stringResource(pressLabel(style)))
            }
        }
    }
}

/** The label size and weight, the corner hints and the key preview popup. */
@Composable
private fun KeyLabelsSection(
    settings: KeyboardSettings,
    onUpdate: ((KeyboardSettings) -> KeyboardSettings) -> Unit,
) {
    SectionHeader(stringResource(R.string.theme_section_labels))
    SliderRow(
        title = stringResource(R.string.theme_label_size),
        value = settings.labelScale,
        valueRange = KeyboardSettings.LABEL_SCALE_RANGE,
        valueLabel = { stringResource(R.string.value_percent, (it * PERCENT).roundToInt()) },
        onValueCommitted = { v -> onUpdate { it.copy(labelScale = v) } },
        snap = { (it * LABEL_STEPS).roundToInt() / LABEL_STEPS },
    )
    SwitchRow(
        title = stringResource(R.string.theme_bold_labels),
        checked = settings.boldLabels,
        onCheckedChange = { v -> onUpdate { it.copy(boldLabels = v) } },
    )
    SwitchRow(
        title = stringResource(R.string.theme_hint_labels),
        supportingText = stringResource(R.string.theme_hint_labels_summary),
        checked = settings.symbolHints,
        onCheckedChange = { v -> onUpdate { it.copy(symbolHints = v) } },
    )
    SwitchRow(
        title = stringResource(R.string.theme_key_preview),
        supportingText = stringResource(R.string.theme_key_preview_summary),
        checked = settings.keyPreview,
        onCheckedChange = { v -> onUpdate { it.copy(keyPreview = v) } },
    )
}

@StringRes
private fun pressLabel(style: PressStyle): Int =
    when (style) {
        PressStyle.Shade -> R.string.theme_press_shade
        PressStyle.Outline -> R.string.theme_press_outline
        PressStyle.None -> R.string.theme_press_none
    }

private const val PERCENT = 100
private const val LABEL_STEPS = 20f

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomEditor(
    colors: KeyboardColors,
    stored: List<Int>,
    editDark: Boolean,
    role: ThemeRole,
    onRole: (ThemeRole) -> Unit,
    onUpdate: ((KeyboardSettings) -> KeyboardSettings) -> Unit,
) {
    SectionHeader(stringResource(R.string.theme_section_custom))
    FlowRow(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (r in ThemeRole.entries) {
            FilterChip(
                selected = role == r,
                onClick = { onRole(r) },
                label = { Text(stringResource(roleLabel(r))) },
            )
        }
    }
    colors.contrastProblems().firstOrNull()?.let { problem ->
        Text(
            stringResource(
                R.string.theme_contrast_warning,
                stringResource(pairLabel(problem.pair)),
                String.format(Locale.US, "%.1f", problem.ratio),
            ),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    fun store(list: List<Int>) =
        onUpdate { s ->
            if (editDark) s.copy(customDarkColors = list) else s.copy(customLightColors = list)
        }
    ColorPicker(
        current = colors[role],
        key = "$editDark/$role",
        onPick = { picked ->
            store(
                colors.toStoredWith(stored, role, picked.toArgb())
            )
        },
    )
    if (role !in MAIN_ROLES && stored.getOrNull(role.ordinal) != ThemeColors.AUTO) {
        TextButton(
            onClick = { store(stored.toMutableList().also { it[role.ordinal] = ThemeColors.AUTO }) },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(stringResource(R.string.theme_role_automatic))
        }
    }
}

/** The roles every custom theme sets itself; the others follow from them until changed. */
private val MAIN_ROLES =
    setOf(ThemeRole.Background, ThemeRole.Key, ThemeRole.KeyText, ThemeRole.Accent)

/**
 * The stored list with [role] set to [argb]. Roles still automatic stay so; the rest keep what was
 * stored (the shown colors are only the derived values of the automatic ones).
 */
private fun KeyboardColors.toStoredWith(stored: List<Int>, role: ThemeRole, argb: Int): List<Int> {
    val base = if (stored.size == ThemeColors.COUNT) stored else toSeedArgb()
    return base.toMutableList().also { it[role.ordinal] = argb or OPAQUE }
}

private const val OPAQUE = 0xFF000000.toInt()

@StringRes
private fun pairLabel(pair: ContrastPair): Int =
    when (pair) {
        ContrastPair.KeyText -> R.string.theme_pair_key_text
        ContrastPair.ModifierText -> R.string.theme_pair_modifier_text
        ContrastPair.Hint -> R.string.theme_pair_hint
        ContrastPair.StripText -> R.string.theme_pair_strip_text
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeModeToggle(dark: Boolean, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        SegmentedButton(
            selected = !dark,
            onClick = { onChange(false) },
            shape = SegmentedButtonDefaults.itemShape(0, 2),
        ) {
            Text(stringResource(R.string.theme_mode_light))
        }
        SegmentedButton(
            selected = dark,
            onClick = { onChange(true) },
            shape = SegmentedButtonDefaults.itemShape(1, 2),
        ) {
            Text(stringResource(R.string.theme_mode_dark))
        }
    }
}

@Composable
private fun SourceRow(label: String, summary: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(role = Role.RadioButton, onClick = onSelect)
                .semantics(mergeDescendants = true) { this.selected = selected }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Box(Modifier.size(16.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@StringRes
private fun sourceLabel(source: ThemeSource): Int =
    when (source) {
        ThemeSource.Wallpaper -> R.string.theme_source_wallpaper
        ThemeSource.Classic -> R.string.theme_source_classic
        ThemeSource.Samsung -> R.string.theme_source_samsung
        ThemeSource.HighContrast -> R.string.theme_source_high_contrast
        ThemeSource.Custom -> R.string.theme_source_custom
    }

@StringRes
private fun sourceSummary(source: ThemeSource): Int =
    when (source) {
        ThemeSource.Wallpaper -> R.string.theme_source_wallpaper_summary
        ThemeSource.Classic -> R.string.theme_source_classic_summary
        ThemeSource.Samsung -> R.string.theme_source_samsung_summary
        ThemeSource.HighContrast -> R.string.theme_source_high_contrast_summary
        ThemeSource.Custom -> R.string.theme_source_custom_summary
    }

@StringRes
private fun roleLabel(role: ThemeRole): Int =
    when (role) {
        ThemeRole.Background -> R.string.theme_role_background
        ThemeRole.Key -> R.string.theme_role_key
        ThemeRole.ModifierKey -> R.string.theme_role_modifier_key
        ThemeRole.KeyText -> R.string.theme_role_key_text
        ThemeRole.HintText -> R.string.theme_role_hint_text
        ThemeRole.Accent -> R.string.theme_role_accent
        ThemeRole.PressedKey -> R.string.theme_role_pressed_key
        ThemeRole.StripText -> R.string.theme_role_strip_text
    }
