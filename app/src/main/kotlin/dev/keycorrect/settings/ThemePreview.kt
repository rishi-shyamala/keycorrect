package dev.keycorrect.settings

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.keycorrect.R
import dev.keycorrect.core.designsystem.KeyboardColors
import dev.keycorrect.core.designsystem.onColor

/** A faithful miniature of the keyboard: the strip, three rows and the bottom row. */
@Composable
internal fun ThemePreview(colors: KeyboardColors) {
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
        PreviewRow("qwertyuiop".map { it.toString() }, colors, pressedIndex = 4)
        PreviewRow("asdfghjkl".map { it.toString() }, colors, hint = "1")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val mod = colors.modifierKey
            PreviewKey("⇧", mod, colors.keyText, Modifier.weight(WIDE_WEIGHT))
            for (c in "zxcvbnm") {
                PreviewKey(c.toString(), colors.key, colors.keyText, Modifier.weight(1f))
            }
            PreviewKey("⌫", mod, colors.keyText, Modifier.weight(WIDE_WEIGHT))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val mod = colors.modifierKey
            PreviewKey("?123", mod, colors.keyText, Modifier.weight(WIDE_WEIGHT))
            PreviewKey("", colors.key, colors.keyText, Modifier.weight(SPACE_WEIGHT))
            PreviewKey("⏎", colors.accent, onColor(colors.accent), Modifier.weight(ENTER_WEIGHT))
        }
    }
}

@Composable
private fun PreviewRow(
    letters: List<String>,
    colors: KeyboardColors,
    pressedIndex: Int = -1,
    hint: String? = null,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        letters.forEachIndexed { index, letter ->
            PreviewKey(
                letter,
                if (index == pressedIndex) colors.pressedKey else colors.key,
                colors.keyText,
                Modifier.weight(1f),
                hint = if (hint != null && index == 0) PreviewHint(hint, colors.hintText) else null,
            )
        }
    }
}

private data class PreviewHint(val text: String, val color: Color)

private const val WIDE_WEIGHT = 1.4f
private const val SPACE_WEIGHT = 5f
private const val ENTER_WEIGHT = 1.6f

@Composable
private fun PreviewKey(
    label: String,
    fill: Color,
    text: Color,
    modifier: Modifier,
    hint: PreviewHint? = null,
) {
    Box(
        modifier.height(30.dp).background(fill, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = text, fontSize = 14.sp)
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
