package dev.keycorrect.feature.keyboard.emoji

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Monochrome outline icons for the emoji category tabs (24 x 24, 1.8 stroke, round caps), so the
 * tab row doesn't read as more emoji. Drawn from SVG path data, since material-icons isn't a
 * dependency. [tabIconFor] maps a catalog group name; the icon is tinted at the call site.
 */
internal object EmojiTabIcons {
    val Recents = outline("recents", CIRCLE_12 + "M12 7v5l3 2")
    val Smileys = outline("smileys", CIRCLE_12 + "M9 10h.01M15 10h.01M8.5 14.5q3.5 3.5 7 0")
    val People =
        outline("people", "M12 5a3 3 0 1 0 0 6 3 3 0 0 0 0-6M5 20c0-4 3-6.5 7-6.5s7 2.5 7 6.5")
    val Animals =
        outline(
            "animals",
            // Paw: four toes over one pad.
            "M6.5 8.5a1.6 1.6 0 1 0 0 3.2 1.6 1.6 0 0 0 0-3.2M9.8 4.5a1.6 1.6 0 1 0 0 3.2 " +
                "1.6 1.6 0 0 0 0-3.2M14.2 4.5a1.6 1.6 0 1 0 0 3.2 1.6 1.6 0 0 0 0-3.2M17.5 8.5a" +
                "1.6 1.6 0 1 0 0 3.2 1.6 1.6 0 0 0 0-3.2M12 12.5c-2.6 0-4.8 2.2-4.8 4 0 1.3 1 " +
                "2.1 2.3 2.1.9 0 1.5-.4 2.5-.4s1.6.4 2.5.4c1.3 0 2.3-.8 2.3-2.1 0-1.8-2.2-4-4.8-4z",
        )
    val Food =
        outline(
            "food",
            // Mug with a handle and steam.
            "M5 9h11v6a4 4 0 0 1-4 4H9a4 4 0 0 1-4-4zM16 11h1.5a2.5 2.5 0 0 1 0 5H16M8.5 3.5v2" +
                "M12 3.5v2",
        )
    val Travel =
        outline(
            "travel",
            "M5 17H4a1 1 0 0 1-1-1v-4l2.2-5.2A1.5 1.5 0 0 1 6.6 6h10.8a1.5 1.5 0 0 1 1.4.8L21 12" +
                "v4a1 1 0 0 1-1 1h-1M3 12h18M10 17h4M7.5 15a2 2 0 1 0 0 4 2 2 0 0 0 0-4M16.5 15a" +
                "2 2 0 1 0 0 4 2 2 0 0 0 0-4",
        )
    val Activities =
        outline(
            "activities",
            CIRCLE_12 + "M12 3v18M3 12h18M5.6 5.6c3 3 3 9.8 0 12.8M18.4 5.6c-3 3-3 9.8 0 12.8",
        )
    val Objects =
        outline(
            "objects",
            "M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2.1h5c0-.9.4-1.6 1-2.1A6 6 0 0 0 12 3zM9.5 18.5" +
                "h5M10.5 21h3",
        )
    val Symbols =
        outline(
            "symbols",
            "M12 20s-8-4.9-8-10.5A4.5 4.5 0 0 1 12 7a4.5 4.5 0 0 1 8 2.5C20 15.1 12 20 12 20z",
        )
    val Flags = outline("flags", "M5 21V4M5 4h11l-2 4 2 4H5")

    private const val CIRCLE_12 = "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18"

    private fun outline(name: String, path: String): ImageVector =
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
}

/** The outline icon for a catalog group [name], or null for one we have no icon for. */
internal fun tabIconFor(name: String): ImageVector? =
    when (name) {
        EmojiPanelState.RECENTS -> EmojiTabIcons.Recents
        "Smileys & Emotion" -> EmojiTabIcons.Smileys
        "People & Body" -> EmojiTabIcons.People
        "Animals & Nature" -> EmojiTabIcons.Animals
        "Food & Drink" -> EmojiTabIcons.Food
        "Travel & Places" -> EmojiTabIcons.Travel
        "Activities" -> EmojiTabIcons.Activities
        "Objects" -> EmojiTabIcons.Objects
        "Symbols" -> EmojiTabIcons.Symbols
        "Flags" -> EmojiTabIcons.Flags
        else -> null
    }
