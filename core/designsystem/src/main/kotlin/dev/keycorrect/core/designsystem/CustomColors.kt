package dev.keycorrect.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.keycorrect.core.settings.ThemeColors

private val BASE_ROLES =
    setOf(ThemeRole.Background, ThemeRole.Key, ThemeRole.KeyText, ThemeRole.Accent)

/** The colors of a new custom theme: only the main four set, the others left to be derived. */
fun KeyboardColors.toSeedArgb(): List<Int> =
    ThemeRole.entries.map {
        if (it in BASE_ROLES) this[it].toArgb() or OPAQUE_INT else ThemeColors.AUTO
    }

private const val OPAQUE_INT = 0xFF000000.toInt()

/**
 * The colors for [stored] custom entries (one per [ThemeRole]); an [ThemeColors.AUTO] one is worked
 * out from the others: modifier keys and the pressed key are tonal shifts of the keys, hints are
 * the key text faded (3:1 or more), suggestion text reads on the background. Null unless [stored]
 * has an entry for every role. The roles missing from the main four come from [fallback].
 */
fun customColors(stored: List<Int>, fallback: KeyboardColors): KeyboardColors? {
    if (stored.size != ThemeColors.COUNT) return null
    fun raw(role: ThemeRole): Color? =
        stored[role.ordinal].takeIf { it != ThemeColors.AUTO }?.let { Color(it or OPAQUE_INT) }
    val background = raw(ThemeRole.Background) ?: fallback.background
    val key = raw(ThemeRole.Key) ?: fallback.key
    val accent = raw(ThemeRole.Accent) ?: fallback.accent
    val keyText = raw(ThemeRole.KeyText) ?: onColor(key)
    val base = fallback.copy(background = background, key = key, keyText = keyText, accent = accent)
    return base.copy(
        modifierKey = raw(ThemeRole.ModifierKey) ?: awayFromText(key, keyText),
        pressedKey = raw(ThemeRole.PressedKey) ?: awayFromText(key, keyText, PRESS_SHIFT),
        hintText = raw(ThemeRole.HintText) ?: hintOn(base.textOn(key), key),
        stripText = raw(ThemeRole.StripText) ?: base.textOn(background),
    )
}

/**
 * A shade of [fill] moved away from [text] (so the text keeps reading on it): darker under light
 * text, lighter under dark text, and the other way where the fill is already black or white.
 */
private fun awayFromText(fill: Color, text: Color, amount: Float = TONE_SHIFT): Color {
    val lighter = relativeLuminance(text.toArgb()) < relativeLuminance(fill.toArgb())
    val target = if (lighter) Color.White else Color.Black
    val shifted = androidx.compose.ui.graphics.lerp(fill, target, amount)
    return if (shifted == fill)
        androidx.compose.ui.graphics.lerp(fill, if (lighter) Color.Black else Color.White, amount)
    else shifted
}
