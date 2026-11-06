package dev.keycorrect.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.keycorrect.R
import dev.keycorrect.core.designsystem.KeyboardColors
import dev.keycorrect.core.designsystem.ThemeRole
import dev.keycorrect.core.designsystem.onColor
import dev.keycorrect.core.designsystem.lowContrast
import dev.keycorrect.core.designsystem.keyContrast
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
            val colors = colorsFor(editDark)
            SectionHeader(stringResource(R.string.theme_section_custom))
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (r in ThemeRole.entries) {
                    FilterChip(
                        selected = role == r,
                        onClick = { role = r },
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
    }
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

/** A faithful miniature of the keyboard: the strip, three rows and the bottom row. */
@Composable
private fun ThemePreview(colors: KeyboardColors) {
    val description = stringResource(R.string.theme_preview_description)
    Column(
        modifier =
            Modifier.padding(horizontal = 16.dp)
                .fillMaxWidth()
                .background(colors.background, RoundedCornerShape(12.dp))
                .padding(6.dp)
                .clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(28.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (word in listOf("hello", "help", "held")) {
                Text(word, color = colors.stripText, fontSize = 14.sp)
            }
        }
        PreviewRow("qwertyuiop".map { it.toString() }, colors, pressedIndex = 4)
        PreviewRow("asdfghjkl".map { it.toString() }, colors, hint = "1")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PreviewKey("⇧", colors.modifierKey, colors.keyText, Modifier.weight(1.4f), colors)
            for (c in "zxcvbnm") {
                PreviewKey(c.toString(), colors.key, colors.keyText, Modifier.weight(1f), colors)
            }
            PreviewKey("⌫", colors.modifierKey, colors.keyText, Modifier.weight(1.4f), colors)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PreviewKey("?123", colors.modifierKey, colors.keyText, Modifier.weight(1.4f), colors)
            PreviewKey("", colors.key, colors.keyText, Modifier.weight(5f), colors)
            PreviewKey(
                "⏎",
                colors.accent,
                onColor(colors.accent),
                Modifier.weight(1.6f),
                colors,
            )
        }
    }
}

@Composable
private fun PreviewRow(
    letters: List<String>,
    colors: KeyboardColors,
    pressedIndex: Int = -1,
    hint: String? = null,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        letters.forEachIndexed { index, letter ->
            PreviewKey(
                letter,
                if (index == pressedIndex) colors.pressedKey else colors.key,
                colors.keyText,
                Modifier.weight(1f),
                colors,
                hint = if (hint != null && index == 0) hint else null,
            )
        }
    }
}

@Composable
private fun PreviewKey(
    label: String,
    fill: Color,
    text: Color,
    modifier: Modifier,
    colors: KeyboardColors,
    hint: String? = null,
) {
    Box(
        modifier.height(30.dp).background(fill, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = text, fontSize = 14.sp)
        if (hint != null) {
            Text(
                hint,
                color = colors.hintText,
                fontSize = 8.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 2.dp),
            )
        }
    }
}

private val SWATCHES: List<Int> =
    listOf(
        0xFFFFFFFF, 0xFFF2F2F5, 0xFFD2D4DA, 0xFF9AA0A6, 0xFF5F6368, 0xFF3A3B40,
        0xFF1F2023, 0xFF000000, 0xFFD93025, 0xFFE8710A, 0xFFF9AB00, 0xFFFFD60A,
        0xFF188038, 0xFF0B8043, 0xFF12B5CB, 0xFF1A73E8, 0xFF1A6FE0, 0xFF6AA8FF,
        0xFF673AB7, 0xFFA142F4, 0xFFE52592, 0xFFFFB6C1, 0xFFBFD3F7, 0xFFC8E6C9,
    )
        .map { it.toInt() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPicker(current: Color, key: String, onPick: (Color) -> Unit) {
    val currentArgb = current.toArgb()
    FlowRow(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (argb in SWATCHES) {
            val hex = hexOf(argb)
            val description = stringResource(R.string.theme_color_description, hex)
            val selected = argb == currentArgb
            Box(
                Modifier.size(44.dp)
                    .background(Color(argb), RoundedCornerShape(8.dp))
                    .border(
                        BorderStroke(
                            if (selected) 3.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                        ),
                        RoundedCornerShape(8.dp),
                    )
                    .clickable(role = Role.RadioButton) { onPick(Color(argb)) }
                    .semantics {
                        contentDescription = description
                        this.selected = selected
                    }
            )
        }
    }
    val hsv = remember(key, currentArgb) { hsvOf(currentArgb) }
    var hue by remember(key, currentArgb) { mutableFloatStateOf(hsv[0]) }
    var value by remember(key, currentArgb) { mutableFloatStateOf(hsv[2]) }
    val saturation = if (hsv[1] < MIN_SATURATION) DEFAULT_SATURATION else hsv[1]
    val hueLabel = stringResource(R.string.theme_hue)
    val brightnessLabel = stringResource(R.string.theme_brightness)
    fun picked() = Color.hsv(hue, saturation, value)
    Text(hueLabel, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
    Slider(
        value = hue,
        onValueChange = { hue = it },
        onValueChangeFinished = { onPick(picked()) },
        valueRange = 0f..HUE_MAX,
        modifier =
            Modifier.padding(horizontal = 16.dp).semantics {
                contentDescription = hueLabel
                stateDescription = hue.toInt().toString()
            },
    )
    Text(brightnessLabel, modifier = Modifier.padding(start = 16.dp))
    Slider(
        value = value,
        onValueChange = { value = it },
        onValueChangeFinished = { onPick(picked()) },
        valueRange = 0f..1f,
        modifier =
            Modifier.padding(horizontal = 16.dp).semantics {
                contentDescription = brightnessLabel
                stateDescription = "${(value * PERCENT).toInt()}%"
            },
    )
}

private const val HUE_MAX = 360f
private const val PERCENT = 100
private const val MIN_SATURATION = 0.05f
private const val DEFAULT_SATURATION = 0.8f

private fun hsvOf(argb: Int): FloatArray {
    val out = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, out)
    return out
}

private fun hexOf(argb: Int): String = String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

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
