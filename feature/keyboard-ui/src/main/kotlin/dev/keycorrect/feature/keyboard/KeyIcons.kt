package dev.keycorrect.feature.keyboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyCodes
import dev.keycorrect.core.layout.ResolvedKey

/** The vector icons drawn on keys instead of a text glyph. */
internal enum class KeyIcon(val path: String, val filled: Boolean = false) {
    /** Shift off: an outline arrow. */
    ShiftOff("M12 4 4.5 12H9v7.5h6V12h4.5z"),
    /** Shift on for one letter (or automatically): the same arrow, filled. */
    ShiftOn("M12 4 4.5 12H9v7.5h6V12h4.5z", filled = true),
    /** Caps lock: a filled arrow over a bar. */
    ShiftLocked("M12 2.5 4.5 10H9v5h6v-5h4.5zM9 18h6v3H9z", filled = true),
    Backspace(
        "M9 5h10.5A1.5 1.5 0 0 1 21 6.5v11a1.5 1.5 0 0 1-1.5 1.5H9L3 12zM12.5 9.5l5 5M17.5 9.5l-5 5"
    ),
    /** Enter as a line break: a return arrow. */
    EnterReturn("M19 6v6a2 2 0 0 1-2 2H6M9.5 10.5 6 14l3.5 3.5"),
    EnterSearch("M10.5 4a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13M15.5 15.5 20 20"),
    EnterSend("M20.5 3.5 3.5 10.5l7 3 3 7zM10.5 13.5l10-10"),
    Tab("M4 12h12M12 8l4 4-4 4M20 6v12"),
    ArrowLeft("M19 12H5M11 6l-6 6 6 6"),
    ArrowRight("M5 12h14M13 6l6 6-6 6"),
    ArrowUp("M12 19V5M6 11l6-6 6 6"),
    ArrowDown("M12 5v14M6 13l6 6 6-6"),
    Emoji("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18M9 10h.01M15 10h.01M8.5 14.5q3.5 3.5 7 0"),
    /** A microphone: the hint on a key that starts voice typing, and the listening bar's icon. */
    Mic("M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3M6 11a6 6 0 0 0 12 0M12 17v4"),
}

/**
 * The icon to draw on [key] instead of its label, or null to draw the text. [enterLabel] is the
 * text for the Enter key (see [enterLabel]): a return arrow for a line break, a magnifier for
 * "Search", a paper plane for "Send"; the other editor actions keep their word.
 */
internal fun keyIconFor(key: ResolvedKey, shift: ShiftState, enterLabel: String): KeyIcon? =
    when (val action = key.action) {
        KeyAction.Shift -> shiftIcon(shift)
        KeyAction.Backspace -> KeyIcon.Backspace
        KeyAction.Enter -> enterIcon(enterLabel)
        KeyAction.Emoji -> KeyIcon.Emoji
        is KeyAction.KeyCode -> keyCodeIcon(action.code)
        else -> null
    }

private fun shiftIcon(shift: ShiftState): KeyIcon =
    when (shift) {
        ShiftState.Off -> KeyIcon.ShiftOff
        ShiftState.Auto,
        ShiftState.Once -> KeyIcon.ShiftOn
        ShiftState.Locked -> KeyIcon.ShiftLocked
    }

private fun enterIcon(enterLabel: String): KeyIcon? =
    when (enterLabel) {
        ENTER_NEW_LINE -> KeyIcon.EnterReturn
        "Search" -> KeyIcon.EnterSearch
        "Send" -> KeyIcon.EnterSend
        else -> null
    }

private fun keyCodeIcon(code: Int): KeyIcon? =
    when (code) {
        KeyCodes.TAB -> KeyIcon.Tab
        KeyCodes.DPAD_LEFT -> KeyIcon.ArrowLeft
        KeyCodes.DPAD_RIGHT -> KeyIcon.ArrowRight
        KeyCodes.DPAD_UP -> KeyIcon.ArrowUp
        KeyCodes.DPAD_DOWN -> KeyIcon.ArrowDown
        else -> null
    }

/** [enterLabel] of a plain line break. */
internal const val ENTER_NEW_LINE = "↵"

/** Icons are authored on a 24 x 24 grid with a 1.8 stroke, like [ShortcutIcons]. */
private const val ICON_GRID = 24f
private const val ICON_STROKE = 1.8f

/**
 * The icons' paths, parsed once; drawing one is a transform and a path draw, nothing allocated.
 * Draw-phase (UI thread) only.
 */
internal object KeyIconArt {
    private val paths: Array<Path> =
        Array(KeyIcon.entries.size) {
            PathParser().parsePathString(KeyIcon.entries[it].path).toPath()
        }
    private val outline =
        Stroke(width = ICON_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)

    /** Draws [icon] [color]-tinted, centred on ([cx], [cy]), [sizePx] square. */
    fun DrawScope.drawKeyIcon(icon: KeyIcon, color: Color, cx: Float, cy: Float, sizePx: Float) {
        val scale = sizePx / ICON_GRID
        val path = paths[icon.ordinal]
        withTransform({
            translate(cx - sizePx / 2, cy - sizePx / 2)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            if (icon.filled) drawPath(path, color, style = Fill)
            drawPath(path, color, style = outline)
        }
    }
}
