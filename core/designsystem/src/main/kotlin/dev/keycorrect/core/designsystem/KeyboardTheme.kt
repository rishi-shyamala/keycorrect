package dev.keycorrect.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import dev.keycorrect.core.settings.ThemeColors
import dev.keycorrect.core.settings.ThemeSource
import kotlin.math.pow

/** The parts of the keyboard that have a user-chosen color. */
enum class ThemeRole {
    Background,
    Key,
    ModifierKey,
    KeyText,
    HintText,
    Accent,
    PressedKey,
    StripText,
}

/** One color per [ThemeRole], for one of light or dark. */
data class KeyboardColors(
    val background: Color,
    val key: Color,
    val modifierKey: Color,
    val keyText: Color,
    val hintText: Color,
    val accent: Color,
    val pressedKey: Color,
    val stripText: Color,
) {
    operator fun get(role: ThemeRole): Color =
        when (role) {
            ThemeRole.Background -> background
            ThemeRole.Key -> key
            ThemeRole.ModifierKey -> modifierKey
            ThemeRole.KeyText -> keyText
            ThemeRole.HintText -> hintText
            ThemeRole.Accent -> accent
            ThemeRole.PressedKey -> pressedKey
            ThemeRole.StripText -> stripText
        }

    fun with(role: ThemeRole, color: Color): KeyboardColors =
        when (role) {
            ThemeRole.Background -> copy(background = color)
            ThemeRole.Key -> copy(key = color)
            ThemeRole.ModifierKey -> copy(modifierKey = color)
            ThemeRole.KeyText -> copy(keyText = color)
            ThemeRole.HintText -> copy(hintText = color)
            ThemeRole.Accent -> copy(accent = color)
            ThemeRole.PressedKey -> copy(pressedKey = color)
            ThemeRole.StripText -> copy(stripText = color)
        }

    /** The colors as opaque ARGB ints in [ThemeRole] order (how settings store them). */
    fun toArgb(): List<Int> = ThemeRole.entries.map { this[it].toArgb() or OPAQUE }

    companion object {
        private const val OPAQUE = 0xFF000000.toInt()
        private val BLANK =
            KeyboardColors(
                Color.Black,
                Color.Black,
                Color.Black,
                Color.Black,
                Color.Black,
                Color.Black,
                Color.Black,
                Color.Black,
            )

        /** Inverse of [toArgb]; null unless [argb] has one color per role. */
        fun fromArgb(argb: List<Int>): KeyboardColors? {
            if (argb.size != ThemeColors.COUNT) return null
            return ThemeRole.entries.fold(BLANK) { acc, role ->
                acc.with(role, Color(argb[role.ordinal] or OPAQUE))
            }
        }
    }
}

/**
 * A resolved theme: its [colors], and whether it is built on the system's dynamic scheme, in which
 * case the colors it does not set (text on the accent, latched modifier keys) come from there.
 */
data class KeyboardTheme(val colors: KeyboardColors, val dynamic: Boolean) {
    /**
     * The readable text, hint and icon colors on each fill; null for a dynamic theme, which keeps
     * the Material roles it was built from.
     */
    val palette: ThemePalette? = if (dynamic) null else colors.palette()
}

/** Black or white, whichever reads better on [background]. */
fun onColor(background: Color): Color =
    if (contrastRatio(background.toArgb(), WHITE) >= contrastRatio(background.toArgb(), BLACK)) {
        Color.White
    } else {
        Color.Black
    }

private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF000000.toInt()
private const val CHANNEL_MAX = 255.0
private const val SRGB_LINEAR_CUTOFF = 0.03928
private const val SRGB_LINEAR_DIVISOR = 12.92
private const val SRGB_OFFSET = 0.055
private const val SRGB_SCALE = 1.055
private const val SRGB_GAMMA = 2.4
private const val LUMA_R = 0.2126
private const val LUMA_G = 0.7152
private const val LUMA_B = 0.0722
private const val FLARE = 0.05
private const val BYTE = 0xFF
private const val SHIFT_R = 16
private const val SHIFT_G = 8
private const val LATCH_BLEND = 0.35f

/** Minimum contrast (WCAG AA for normal text) between key text and key background. */
const val MIN_TEXT_CONTRAST = 4.5

private fun channel(value: Int): Double {
    val c = value / CHANNEL_MAX
    return if (c <= SRGB_LINEAR_CUTOFF) c / SRGB_LINEAR_DIVISOR
    else ((c + SRGB_OFFSET) / SRGB_SCALE).pow(SRGB_GAMMA)
}

/** WCAG relative luminance of an ARGB int (alpha ignored). */
fun relativeLuminance(argb: Int): Double =
    LUMA_R * channel((argb shr SHIFT_R) and BYTE) +
        LUMA_G * channel((argb shr SHIFT_G) and BYTE) +
        LUMA_B * channel(argb and BYTE)

/** WCAG contrast ratio between two ARGB ints, 1.0 to 21.0. */
fun contrastRatio(a: Int, b: Int): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + FLARE) / (minOf(la, lb) + FLARE)
}

/** Contrast of the key text on the plain key fill. */
fun KeyboardColors.keyContrast(): Double = contrastRatio(keyText.toArgb(), key.toArgb())

/** Whether the key text is too faint on the key (below [MIN_TEXT_CONTRAST]). */
fun KeyboardColors.lowContrast(): Boolean = keyContrast() < MIN_TEXT_CONTRAST

private const val OPAQUE_LONG = 0xFF000000

private fun c(rgb: Long) = Color(rgb or OPAQUE_LONG)

private val SamsungLight =
    KeyboardColors(
        background = c(0xE9EAEE),
        key = c(0xFFFFFF),
        modifierKey = c(0xD2D4DA),
        keyText = c(0x1B1B1F),
        hintText = c(0x62656D),
        accent = c(0x1A6FE0),
        pressedKey = c(0xBFD3F7),
        stripText = c(0x1B1B1F),
    )

private val SamsungDark =
    KeyboardColors(
        background = c(0x1F2023),
        key = c(0x3A3B40),
        modifierKey = c(0x2B2C30),
        keyText = c(0xF2F2F5),
        hintText = c(0xA3A6AE),
        accent = c(0x6AA8FF),
        pressedKey = c(0x5A5D66),
        stripText = c(0xF2F2F5),
    )

/** Light: white keys on a mid-grey board (3.4:1), black text 21:1 on them. */
private val HighContrastLight =
    KeyboardColors(
        background = c(0x8A8A8A),
        key = c(0xFFFFFF),
        modifierKey = c(0x1A1A1A),
        keyText = c(0x000000),
        hintText = c(0x2E2E2E),
        accent = c(0x001F99),
        pressedKey = c(0xFFD60A),
        stripText = c(0x000000),
    )

/** Dark: light keys on black (17:1), black text on them. */
private val HighContrastDark =
    KeyboardColors(
        background = c(0x000000),
        key = c(0xF0F0F0),
        modifierKey = c(0xBDBDBD),
        keyText = c(0x000000),
        hintText = c(0x2E2E2E),
        accent = c(0xFFD60A),
        pressedKey = c(0x0A84FF),
        stripText = c(0xFFFFFF),
    )

/** Today's look: the dynamic scheme's roles exactly as the keyboard used them before themes. */
fun classicColors(s: ColorScheme) =
    KeyboardColors(
        background = s.surfaceContainer,
        key = s.surfaceContainerHighest,
        modifierKey = s.secondaryContainer,
        keyText = s.onSurface,
        hintText = s.onSurfaceVariant,
        accent = s.primary,
        pressedKey = s.primaryContainer,
        stripText = s.onSurface,
    )

/** Wallpaper colors: like classic, but with brighter keys in light mode. */
fun wallpaperColors(s: ColorScheme, dark: Boolean) =
    classicColors(s).copy(key = if (dark) s.surfaceContainerHighest else s.surfaceContainerLowest)

/**
 * The theme for [source] in light or [dark] mode. [dynamic] is the system's Material You scheme for
 * that mode; [custom] the user's colors for it (an empty or short list falls back to the wallpaper
 * colors).
 */
fun resolveKeyboardTheme(
    source: ThemeSource,
    dark: Boolean,
    dynamic: ColorScheme,
    custom: List<Int> = emptyList(),
): KeyboardTheme =
    when (source) {
        ThemeSource.Wallpaper -> KeyboardTheme(wallpaperColors(dynamic, dark), dynamic = true)
        ThemeSource.Classic -> KeyboardTheme(classicColors(dynamic), dynamic = true)
        ThemeSource.Samsung -> KeyboardTheme(if (dark) SamsungDark else SamsungLight, false)
        ThemeSource.HighContrast ->
            KeyboardTheme(if (dark) HighContrastDark else HighContrastLight, false)
        ThemeSource.Custom ->
            customColors(custom, wallpaperColors(dynamic, dark))?.let {
                KeyboardTheme(it, dynamic = false)
            } ?: KeyboardTheme(wallpaperColors(dynamic, dark), dynamic = true)
    }

/**
 * The Material scheme the keyboard's panels and renderer read: [base] (the dynamic scheme) with the
 * theme's roles laid over it. Classic comes out equal to [base]. Without a dynamic base, the text
 * roles come from the [KeyboardTheme.palette]: `onSurface` is the text on keys, `onBackground` the
 * text on the board, `onSurfaceVariant` the hint on keys.
 */
fun KeyboardTheme.toColorScheme(base: ColorScheme): ColorScheme {
    val k = colors
    val themed =
        base.copy(
            surface = k.background,
            surfaceContainerLow = k.background,
            surfaceContainer = k.background,
            surfaceContainerHighest = k.key,
            secondaryContainer = k.modifierKey,
            onSecondaryContainer = if (dynamic) base.onSecondaryContainer else k.keyText,
            onSurface = k.keyText,
            onSurfaceVariant = k.hintText,
            primary = k.accent,
            primaryContainer = k.pressedKey,
        )
    val p = palette
    if (p == null) return if (k == classicColors(base)) base else themed
    val latched = lerp(k.modifierKey, k.accent, LATCH_BLEND)
    return themed.copy(
        onSurface = p.onKey,
        onBackground = p.onBackground,
        onSurfaceVariant = p.hintOnKey,
        onSecondaryContainer = p.onModifier,
        onPrimary = p.onAccent,
        onPrimaryContainer = p.onPressed,
        tertiary = k.accent,
        onTertiary = p.onAccent,
        tertiaryContainer = latched,
        onTertiaryContainer = k.textOn(latched),
        surfaceContainerLowest = k.background,
        surfaceContainerHigh = k.key,
    )
}
