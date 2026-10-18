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

    /** One word in a box with an underline: select the word. */
    val SelectWord =
        outlineIcon(
            "select_word",
            "M5 7h14a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1zM7 18h10",
        )

    /** One line of text between two end marks: select the line. */
    val SelectLine = outlineIcon("select_line", "M4 12h16M4 8v8M20 8v8")

    /** Lines of text ending in a full stop: select the sentence. */
    val SelectSentence =
        outlineIcon("select_sentence", "M4 6h16M4 10h16M4 14h9M16.5 14.5h.01M4 18h6")

    /** A capital and a small letter: change case. */
    val Case =
        outlineIcon(
            "case",
            "M2.5 18 7 6l4.5 12M4.2 14h5.6M17 11.5a3 3 0 1 0 0 6 3 3 0 0 0 0-6M20 11.5v6",
        )

    /** Two overlapping sheets: copy. */
    val Copy = outlineIcon("copy", "M9 9h10v10H9zM15 9V5H5v10h4")

    /** Two chevrons pointing left: move a word left. */
    val WordLeft = outlineIcon("word_left", "M12 7l-5 5 5 5M18 7l-5 5 5 5")

    /** Two chevrons pointing right: move a word right. */
    val WordRight = outlineIcon("word_right", "M6 7l5 5-5 5M12 7l5 5-5 5")

    /** An arrow into a bar on the left: the start of the line. */
    val Home = outlineIcon("home", "M5 5v14M19 12H9M14 7l-5 5 5 5")

    /** An arrow into a bar on the right: the end of the line. */
    val End = outlineIcon("end", "M19 5v14M5 12h10M10 7l5 5-5 5")

    /** A dashed frame around a letter: select all. */
    val SelectAll =
        outlineIcon(
            "select_all",
            "M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3" +
                "M8 20H5a1 1 0 0 1-1-1v-3M9 16l3-8 3 8M10 14h4",
        )

    /** Scissors: cut. */
    val Cut =
        outlineIcon(
            "cut",
            "M6 3a3 3 0 1 0 0 6 3 3 0 0 0 0-6M6 15a3 3 0 1 0 0 6 3 3 0 0 0 0-6M8.1 8.1 20 20M8.1 15.9 20 4",
        )

    /** A clipboard: paste. */
    val Paste =
        outlineIcon(
            "paste",
            "M9 3h6v4H9zM15 5h2a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1h2",
        )

    /** A curved arrow back: undo. */
    val Undo = outlineIcon("undo", "M9 14 4 9l5-5M4 9h10a6 6 0 0 1 0 12h-3")

    /** A curved arrow forward: redo. */
    val Redo = outlineIcon("redo", "M15 14l5-5-5-5M20 9H10a6 6 0 0 0 0 12h3")
}
