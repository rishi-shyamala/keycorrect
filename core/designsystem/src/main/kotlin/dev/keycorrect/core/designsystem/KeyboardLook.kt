package dev.keycorrect.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.core.settings.ThemeSource

/** What to draw on each fill of the theme in effect; null for the dynamic themes. */
val LocalThemePalette = staticCompositionLocalOf<ThemePalette?> { null }

/** The color of text on the suggestion strip. */
@Composable
fun stripTextColor(): Color = LocalThemePalette.current?.stripText ?: MaterialTheme.colorScheme.onSurface

/** Text and icons drawn straight on the keyboard background (panel titles, empty-state hints). */
@Composable
fun onBackgroundColor(): Color =
    LocalThemePalette.current?.onBackground ?: MaterialTheme.colorScheme.onSurface

/** The quieter text on the keyboard background. */
@Composable
fun hintOnBackgroundColor(): Color =
    LocalThemePalette.current?.hintOnBackground ?: MaterialTheme.colorScheme.onSurfaceVariant

/** The accent as text or an icon on the keyboard background. */
@Composable
fun accentOnBackgroundColor(): Color =
    LocalThemePalette.current?.accentOnBackground ?: MaterialTheme.colorScheme.primary

/** The accent as text or an icon on a key-colored bar. */
@Composable
fun accentOnKeyColor(): Color =
    LocalThemePalette.current?.accentOnKey ?: MaterialTheme.colorScheme.primary

/**
 * The system's dynamic scheme for light or dark. It is read again when the configuration changes
 * (dark mode, wallpaper colors on a theme change) and whenever [refresh] changes (the host bumps it
 * each time the keyboard is shown).
 */
@Composable
fun rememberDynamicScheme(darkTheme: Boolean = isSystemInDarkTheme(), refresh: Int = 0): ColorScheme {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, darkTheme, configuration, refresh) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
}

/**
 * The theme chosen in settings for the system light/dark mode (the one the keyboard and the
 * settings preview use).
 */
@Composable
fun rememberKeyboardTheme(
    source: ThemeSource,
    custom: List<Int>,
    darkTheme: Boolean = isSystemInDarkTheme(),
    refresh: Int = 0,
): KeyboardTheme {
    val dynamic = rememberDynamicScheme(darkTheme, refresh)
    return remember(source, custom, darkTheme, dynamic) {
        resolveKeyboardTheme(source, darkTheme, dynamic, custom)
    }
}

/** Applies [theme] as the Material scheme (over the dynamic scheme [base]) for [content]. */
@Composable
fun KeyboardLook(theme: KeyboardTheme, base: ColorScheme, content: @Composable () -> Unit) {
    val scheme = remember(theme, base) { theme.toColorScheme(base) }
    CompositionLocalProvider(LocalThemePalette provides theme.palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/**
 * [KeyboardLook] for the theme stored in [settings], following the system dark mode. The dynamic
 * scheme is read once per composition and again when the configuration or [refresh] changes.
 */
@Composable
fun KeyboardLook(
    settings: KeyboardSettings,
    darkTheme: Boolean = isSystemInDarkTheme(),
    refresh: Int = 0,
    content: @Composable () -> Unit,
) {
    val base = rememberDynamicScheme(darkTheme, refresh)
    val custom = if (darkTheme) settings.customDarkColors else settings.customLightColors
    val theme =
        remember(settings.themeSource, custom, darkTheme, base) {
            resolveKeyboardTheme(settings.themeSource, darkTheme, base, custom)
        }
    KeyboardLook(theme, base, content)
}
