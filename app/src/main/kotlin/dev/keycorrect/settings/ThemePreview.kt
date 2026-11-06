package dev.keycorrect.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.keycorrect.R
import dev.keycorrect.core.designsystem.KeyboardColors
import dev.keycorrect.core.designsystem.onColor
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.core.settings.PressStyle

/** A faithful miniature of the keyboard: the strip, three rows and the bottom row. */
@Composable
internal fun ThemePreview(colors: KeyboardColors, settings: KeyboardSettings) {
    val style = PreviewStyle(settings)
    val description = stringResource(R.string.theme_preview_description)
    Column(
        modifier =
            Modifier.padding(horizontal = 16.dp)
                .fillMaxWidth()
                .background(colors.background, RoundedCornerShape(12.dp))
                .padding(6.dp)
                .clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(28.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (word in listOf("hello", "help", "held")) {
                Text(word, color = colors.stripText, fontSize = 14.sp)
            }
        }
        PreviewRow("qwertyuiop".map { it.toString() }, colors, style, pressedIndex = 4)
        PreviewRow("asdfghjkl".map { it.toString() }, colors, style, hint = "1")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val mod = colors.modifierKey
            PreviewKey("⇧", mod, colors.keyText, style, Modifier.weight(WIDE_WEIGHT))
            for (c in "zxcvbnm") {
                PreviewKey(c.toString(), colors.key, colors.keyText, style, Modifier.weight(1f))
            }
            PreviewKey("⌫", mod, colors.keyText, style, Modifier.weight(WIDE_WEIGHT))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val mod = colors.modifierKey
            PreviewKey("?123", mod, colors.keyText, style, Modifier.weight(WIDE_WEIGHT))
            PreviewKey("", colors.key, colors.keyText, style, Modifier.weight(SPACE_WEIGHT))
            PreviewKey(
                "⏎",
                colors.accent,
                onColor(colors.accent),
                style,
                Modifier.weight(ENTER_WEIGHT),
            )
        }
    }
}

@Composable
private fun PreviewRow(
    letters: List<String>,
    colors: KeyboardColors,
    style: PreviewStyle,
    pressedIndex: Int = -1,
    hint: String? = null,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        letters.forEachIndexed { index, letter ->
            val pressed = index == pressedIndex
            PreviewKey(
                letter,
                if (pressed && style.press == PressStyle.Shade) colors.pressedKey else colors.key,
                colors.keyText,
                style,
                Modifier.weight(1f),
                hint =
                    if (hint != null && index == 0 && style.hints) {
                        PreviewHint(hint, colors.hintText)
                    } else {
                        null
                    },
                outline = if (pressed && style.press == PressStyle.Outline) colors.accent else null,
            )
        }
    }
}

/** The key style settings the preview shows. */
internal class PreviewStyle(settings: KeyboardSettings) {
    val radius = settings.keyRadius
    val border = settings.keyBorder
    val labelScale = settings.labelScale
    val bold = settings.boldLabels
    val hints = settings.symbolHints
    val press = settings.pressStyle
}

private const val PREVIEW_KEY_DP = 30

private data class PreviewHint(val text: String, val color: Color)

private const val WIDE_WEIGHT = 1.4f
private const val SPACE_WEIGHT = 5f
private const val ENTER_WEIGHT = 1.6f

@Suppress("LongParameterList")
@Composable
private fun PreviewKey(
    label: String,
    fill: Color,
    text: Color,
    style: PreviewStyle,
    modifier: Modifier,
    hint: PreviewHint? = null,
    outline: Color? = null,
) {
    val shape = RoundedCornerShape((style.radius * PREVIEW_KEY_DP).dp)
    var box = modifier.height(PREVIEW_KEY_DP.dp).background(fill, shape)
    if (style.border) box = box.border(1.dp, text.copy(alpha = 0.45f), shape)
    if (outline != null) box = box.border(2.5.dp, outline, shape)
    Box(box, contentAlignment = Alignment.Center) {
        Text(
            label,
            color = text,
            fontSize = (14 * style.labelScale).sp,
            fontWeight = if (style.bold) FontWeight.Bold else null,
        )
        if (hint != null) {
            Text(
                hint.text,
                color = hint.color,
                fontSize = 8.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 2.dp),
            )
        }
    }
}
