package dev.keycorrect.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb

/**
 * What to draw on each fill of a theme so that it can always be read: the user's key text where it
 * has 4.5:1 on the fill, else black or white; hints at 3:1 or more.
 */
data class ThemePalette(
    val onBackground: Color,
    val hintOnBackground: Color,
    val accentOnBackground: Color,
    val onKey: Color,
    val hintOnKey: Color,
    val accentOnKey: Color,
    val onModifier: Color,
    val onPressed: Color,
    val onAccent: Color,
    val stripText: Color,
)

/** Minimum contrast for hint text and large or graphic elements (WCAG AA). */
const val MIN_HINT_CONTRAST = 3.0
private const val HINT_ALPHA = 0.7f
private const val HINT_ALPHA_STEP = 0.1f
internal const val TONE_SHIFT = 0.12f
internal const val PRESS_SHIFT = 0.25f
private const val LIGHT_FILL = 0.4

/** The user's key text where it reads on [fill] (4.5:1), else black or white. */
fun KeyboardColors.textOn(fill: Color): Color =
    if (contrastRatio(keyText.toArgb(), fill.toArgb()) >= MIN_TEXT_CONTRAST) {
        keyText
    } else {
        onColor(fill)
    }

/** [text] faded towards [fill] (70 %), but never below 3:1 against it. */
fun hintOn(text: Color, fill: Color): Color {
    var alpha = HINT_ALPHA
    var mixed = lerp(fill, text, alpha)
    while (contrastRatio(mixed.toArgb(), fill.toArgb()) < MIN_HINT_CONTRAST && alpha < 1f) {
        alpha = minOf(1f, alpha + HINT_ALPHA_STEP)
        mixed = lerp(fill, text, alpha)
    }
    return mixed
}

private fun Color.orIfFaint(fill: Color, minimum: Double, fallback: Color): Color =
    if (contrastRatio(toArgb(), fill.toArgb()) >= minimum) this else fallback

/** A shade of [fill] a step lighter (on a dark one) or darker (on a light one). */
fun tonalShift(fill: Color, amount: Float = TONE_SHIFT): Color =
    lerp(
        fill,
        if (relativeLuminance(fill.toArgb()) > LIGHT_FILL) Color.Black else Color.White,
        amount,
    )

/** The colors to draw on every fill of these theme colors. */
fun KeyboardColors.palette(): ThemePalette {
    val onBackground = textOn(background)
    val onKey = textOn(key)
    return ThemePalette(
        onBackground = onBackground,
        hintOnBackground = hintOn(onBackground, background),
        accentOnBackground = accent.orIfFaint(background, MIN_HINT_CONTRAST, onBackground),
        onKey = onKey,
        hintOnKey = hintText.orIfFaint(key, MIN_HINT_CONTRAST, hintOn(onKey, key)),
        accentOnKey = accent.orIfFaint(key, MIN_HINT_CONTRAST, onKey),
        onModifier = textOn(modifierKey),
        onPressed = textOn(pressedKey),
        onAccent = textOn(accent),
        stripText = stripText.orIfFaint(background, MIN_TEXT_CONTRAST, onBackground),
    )
}

/** The pairs of colors the custom editor checks. */
enum class ContrastPair {
    KeyText,
    ModifierText,
    Hint,
    StripText,
}

/** A [pair] of the theme colors that is hard to read, at contrast [ratio] to 1. */
data class ContrastProblem(val pair: ContrastPair, val ratio: Double)

/**
 * The pairs of the user's colors that are too faint: key text on keys and on modifier keys and
 * suggestion text on the background (below 4.5:1), hints on keys (below 3:1). The keyboard shows
 * black or white instead where one of them fails, but it is not what was asked for.
 */
fun KeyboardColors.contrastProblems(): List<ContrastProblem> = buildList {
    fun check(pair: ContrastPair, a: Color, b: Color, minimum: Double) {
        val ratio = contrastRatio(a.toArgb(), b.toArgb())
        if (ratio < minimum) add(ContrastProblem(pair, ratio))
    }
    check(ContrastPair.KeyText, keyText, key, MIN_TEXT_CONTRAST)
    check(ContrastPair.ModifierText, keyText, modifierKey, MIN_TEXT_CONTRAST)
    check(ContrastPair.Hint, hintText, key, MIN_HINT_CONTRAST)
    check(ContrastPair.StripText, stripText, background, MIN_TEXT_CONTRAST)
}
