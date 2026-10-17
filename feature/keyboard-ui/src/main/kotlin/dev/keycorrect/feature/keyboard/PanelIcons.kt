package dev.keycorrect.feature.keyboard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** A monochrome outline icon (24 x 24, 1.8 stroke, round caps), tinted at the call site. */
internal fun outlineIcon(name: String, path: String): ImageVector =
    ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        .addPath(
            pathData = addPathNodes(path),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        .build()

/**
 * Outline icons for the editing panel, the selection strip and the panels' search button, in the
 * style of [ShortcutIcons] and the key icons ([KeyIcon]; the arrows, the magnifier and backspace
 * share their paths). No material-icons dependency.
 */
internal object PanelIcons {
    val Up = outlineIcon("up", KeyIcon.ArrowUp.path)
    val Down = outlineIcon("down", KeyIcon.ArrowDown.path)
    val Left = outlineIcon("left", KeyIcon.ArrowLeft.path)
    val Right = outlineIcon("right", KeyIcon.ArrowRight.path)
    val Backspace = outlineIcon("backspace", KeyIcon.Backspace.path)

    /** Backspace mirrored: delete forward. */
    val DeleteForward =
        outlineIcon(
            "delete_forward",
            "M15 5H4.5A1.5 1.5 0 0 0 3 6.5v11A1.5 1.5 0 0 0 4.5 19H15l6-7zM11.5 9.5l-5 5M6.5 9.5l5 5",
        )
    val Search = outlineIcon("search", KeyIcon.EnterSearch.path)

    /** Selection brackets around one short line: select the word. */
    val SelectWord = outlineIcon("select_word", "M7 5H4v14h3M17 5h3v14h-3M8 12h8")

    /** Selection brackets around several lines: select the sentence. */
    val SelectSentence =
        outlineIcon("select_sentence", "M7 5H4v14h3M17 5h3v14h-3M8 9h8M8 12h8M8 15h5")

    /** A capital and a small letter: change case. */
    val Case =
        outlineIcon(
            "case",
            "M2.5 18 7 6l4.5 12M4.2 14h5.6M17 11.5a3 3 0 1 0 0 6 3 3 0 0 0 0-6M20 11.5v6",
        )

    /** Two overlapping sheets: copy. */
    val Copy = outlineIcon("copy", "M9 9h10v10H9zM15 9V5H5v10h4")
}
