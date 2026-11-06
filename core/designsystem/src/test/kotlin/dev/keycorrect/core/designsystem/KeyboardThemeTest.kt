package dev.keycorrect.core.designsystem

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.settings.ThemeSource
import org.junit.Test

class KeyboardThemeTest {
    private val light = lightColorScheme(primary = Color(0xFF6750A4))
    private val dark = darkColorScheme(primary = Color(0xFFD0BCFF))

    @Test
    fun classicIsExactlyTheOldRoleMapping() {
        for ((scheme, isDark) in listOf(light to false, dark to true)) {
            val k = resolveKeyboardTheme(ThemeSource.Classic, isDark, scheme).colors
            assertThat(k.background).isEqualTo(scheme.surfaceContainer)
            assertThat(k.key).isEqualTo(scheme.surfaceContainerHighest)
            assertThat(k.modifierKey).isEqualTo(scheme.secondaryContainer)
            assertThat(k.keyText).isEqualTo(scheme.onSurface)
            assertThat(k.hintText).isEqualTo(scheme.onSurfaceVariant)
            assertThat(k.accent).isEqualTo(scheme.primary)
            assertThat(k.pressedKey).isEqualTo(scheme.primaryContainer)
            assertThat(k.stripText).isEqualTo(scheme.onSurface)
        }
    }

    @Test
    fun classicSchemeIsTheBaseSchemeUnchanged() {
        val theme = resolveKeyboardTheme(ThemeSource.Classic, false, light)
        assertThat(theme.toColorScheme(light)).isEqualTo(light)
    }

    @Test
    fun wallpaperFollowsTheDynamicSchemeAndKeepsItsLatchColors() {
        val theme = resolveKeyboardTheme(ThemeSource.Wallpaper, false, light)
        assertThat(theme.dynamic).isTrue()
        assertThat(theme.colors.accent).isEqualTo(light.primary)
        assertThat(theme.colors.key).isEqualTo(light.surfaceContainerLowest)
        val scheme = theme.toColorScheme(light)
        assertThat(scheme.tertiary).isEqualTo(light.tertiary)
        assertThat(scheme.surfaceContainer).isEqualTo(theme.colors.background)
        val darkTheme = resolveKeyboardTheme(ThemeSource.Wallpaper, true, dark)
        assertThat(darkTheme.colors.key).isEqualTo(dark.surfaceContainerHighest)
    }

    @Test
    fun presetsDifferByModeAndIgnoreTheWallpaper() {
        for (source in listOf(ThemeSource.Samsung, ThemeSource.HighContrast)) {
            val l = resolveKeyboardTheme(source, false, light).colors
            val d = resolveKeyboardTheme(source, true, dark).colors
            assertThat(l).isNotEqualTo(d)
            assertThat(l).isEqualTo(resolveKeyboardTheme(source, false, dark).colors)
            assertThat(l.lowContrast()).isFalse()
            assertThat(d.lowContrast()).isFalse()
        }
        assertThat(resolveKeyboardTheme(ThemeSource.HighContrast, false, light).colors.keyContrast())
            .isAtLeast(7.0)
    }

    @Test
    fun customUsesItsColorsAndFallsBackToWallpaperWhenEmpty() {
        val mine = KeyboardColors.fromArgb(List(8) { 0xFF000000.toInt() or (it * 0x111111) })!!
        val theme = resolveKeyboardTheme(ThemeSource.Custom, false, light, mine.toArgb())
        assertThat(theme.colors).isEqualTo(mine)
        assertThat(theme.dynamic).isFalse()
        assertThat(theme.toColorScheme(light).primary).isEqualTo(mine.accent)
        assertThat(resolveKeyboardTheme(ThemeSource.Custom, false, light))
            .isEqualTo(resolveKeyboardTheme(ThemeSource.Wallpaper, false, light))
    }

    @Test
    fun colorsRoundTripThroughArgbInRoleOrder() {
        val k = resolveKeyboardTheme(ThemeSource.Samsung, false, light).colors
        assertThat(KeyboardColors.fromArgb(k.toArgb())).isEqualTo(k)
        assertThat(k.toArgb()[ThemeRole.Accent.ordinal]).isEqualTo(0xFF1A6FE0.toInt())
        assertThat(KeyboardColors.fromArgb(listOf(1, 2))).isNull()
        assertThat(k.with(ThemeRole.Key, Color.Red)[ThemeRole.Key]).isEqualTo(Color.Red)
    }

    @Test
    fun contrastMatchesWcagReferenceValues() {
        assertThat(contrastRatio(0xFFFFFFFF.toInt(), 0xFF000000.toInt())).isWithin(0.01).of(21.0)
        assertThat(contrastRatio(0xFF777777.toInt(), 0xFFFFFFFF.toInt())).isWithin(0.05).of(4.48)
        assertThat(contrastRatio(0xFF123456.toInt(), 0xFF123456.toInt())).isWithin(1e-9).of(1.0)
        assertThat(contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()))
            .isEqualTo(contrastRatio(0xFFFFFFFF.toInt(), 0xFF000000.toInt()))
    }

    @Test
    fun lowContrastWarnsBelowFourPointFive() {
        val ok = KeyboardColors.fromArgb(List(8) { 0xFF000000.toInt() })!!
        val keyWhite = ok.copy(key = Color.White, keyText = Color(0xFF777777))
        assertThat(keyWhite.lowContrast()).isTrue()
        assertThat(keyWhite.copy(keyText = Color(0xFF595959)).lowContrast()).isFalse()
    }

    @Test
    fun onColorPicksTheReadableOne() {
        assertThat(onColor(Color.White)).isEqualTo(Color.Black)
        assertThat(onColor(Color.Black)).isEqualTo(Color.White)
        assertThat(onColor(Color(0xFF1A6FE0))).isEqualTo(Color.White)
    }
}
