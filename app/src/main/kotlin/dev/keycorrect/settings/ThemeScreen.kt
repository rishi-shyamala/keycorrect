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
import dev.keycorrect.core.designsystem.keyContrast
import dev.keycorrect.core.designsystem.lowContrast
import dev.keycorrect.core.designsystem.resolveKeyboardTheme
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.core.settings.ThemeSource
import java.util.Locale

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
        ThemePreview(colorsFor(editDark))

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
                            // Start the custom colors from what is showing now.
                            s.copy(
                                themeSource = option,
                                customLightColors =
                                    s.customLightColors.ifEmpty { colorsFor(false).toArgb() },
                                customDarkColors =
                                    s.customDarkColors.ifEmpty { colorsFor(true).toArgb() },
                            )
                        }
                    }
                },
            )
        }

        if (source == ThemeSource.Custom) {
            CustomEditor(colorsFor(editDark), editDark, role, { role = it }, onUpdate)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomEditor(
    colors: KeyboardColors,
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
    if (colors.lowContrast()) {
        Text(
            stringResource(
                R.string.theme_contrast_warning,
                String.format(Locale.US, "%.1f", colors.keyContrast()),
            ),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    ColorPicker(
        current = colors[role],
        key = "$editDark/$role",
        onPick = { picked ->
            val updated = colors.with(role, picked).toArgb()
            onUpdate { s ->
                if (editDark) s.copy(customDarkColors = updated)
                else s.copy(customLightColors = updated)
            }
        },
    )
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
