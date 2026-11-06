package dev.keycorrect.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.core.settings.ThemeSource

/** The roles of the theme in effect, for the few places a Material role does not say enough. */
val LocalKeyboardColors = staticCompositionLocalOf<KeyboardColors?> { null }

/** The color of text on the suggestion strip. */
@Composable
fun stripTextColor(): Color =
    LocalKeyboardColors.current?.stripText ?: MaterialTheme.colorScheme.onSurface

/**
 * The theme chosen in settings for the system light/dark mode (the one the keyboard and the
 * settings preview use).
 */
@Composable
fun rememberKeyboardTheme(
    source: ThemeSource,
    custom: List<Int>,
    darkTheme: Boolean = isSystemInDarkTheme(),
): KeyboardTheme {
    val context = LocalContext.current
    val dynamic =
        remember(context, darkTheme) {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
    return remember(source, custom, darkTheme, dynamic) {
        resolveKeyboardTheme(source, darkTheme, dynamic, custom)
    }
}

/** Applies [theme] as the Material scheme (over the system's dynamic one) for [content]. */
@Composable
fun KeyboardLook(
    theme: KeyboardTheme,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme =
        remember(theme, context, darkTheme) {
            val base =
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            theme.toColorScheme(base)
        }
    CompositionLocalProvider(LocalKeyboardColors provides theme.colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** [KeyboardLook] for the theme stored in [settings], following the system dark mode. */
@Composable
fun KeyboardLook(
    settings: KeyboardSettings,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val custom = if (darkTheme) settings.customDarkColors else settings.customLightColors
    KeyboardLook(rememberKeyboardTheme(settings.themeSource, custom, darkTheme), darkTheme, content)
}
