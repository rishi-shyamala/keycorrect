package dev.keycorrect.feature.keyboard

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Monochrome outline icons for the shortcut row (24 x 24, 1.8 stroke, round caps), drawn from SVG
 * path data since material-icons isn't a dependency. Tinted at the call site.
 */
internal object ShortcutIcons {
    /** A text cursor (I-beam) with arrows either side: move the cursor, select. */
    val Editing =
        outline(
            "editing",
            "M12 5v14M9.5 5h5M9.5 19h5M9 12H3M5.5 9.5L3 12l2.5 2.5M15 12h6M18.5 9.5L21 12l-2.5 2.5",
        )

    val Emoji =
        outline(
            "emoji",
            "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18M9 10h.01M15 10h.01M8.5 14.5q3.5 3.5 7 0",
        )

    val Clipboard =
        outline(
            "clipboard",
            "M9 3.5h6V7H9zM9 5H6.5A1.5 1.5 0 0 0 5 6.5v13A1.5 1.5 0 0 0 6.5 21h11a1.5 1.5 0 0 0 " +
                "1.5-1.5v-13A1.5 1.5 0 0 0 17.5 5H15M8.5 12h7M8.5 16h5",
        )

    val Settings =
        outline(
            "settings",
            "M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6M10.3 3.5h3.4l.5 2.4 1.5.9 2.3-.8 1.7 3-1.8 1.6v" +
                "1.8l1.8 1.6-1.7 3-2.3-.8-1.5.9-.5 2.4h-3.4l-.5-2.4-1.5-.9-2.3.8-1.7-3 1.8-1.6v" +
                "-1.8L4.3 9.1l1.7-3 2.3.8 1.5-.9z",
        )

    private fun outline(name: String, path: String): ImageVector = outlineIcon(name, path)
}
