package dev.keycorrect.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * App-wide Material 3 theme. Uses wallpaper-based dynamic color (minSdk 31 guarantees it is
 * available) and follows the system dark-mode setting. Works from both Activity and
 * InputMethodService contexts.
 */
@Composable
fun KeyCorrectTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme =
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colorScheme, content = content)
}
