package dev.keycorrect.feature.keyboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.keycorrect.feature.keyboard.prediction.SlotKind
import dev.keycorrect.feature.keyboard.prediction.StripLabels
import dev.keycorrect.feature.keyboard.prediction.StripModel
import dev.keycorrect.feature.keyboard.prediction.StripSlot

/**
 * The prediction strip: three equal slots in the fixed-height strip area. [strip] is a lambda so
 * the state behind it is read here, in this composable only; it recomposes when the suggestions
 * change and for no other reason (typing in a field without suggestions never gets here at all).
 * Empty slots keep their space, so the layout does not move as candidates come and go.
 *
 * While the model carries an add-word prompt, a single chip takes over the whole strip.
 */
@Composable
internal fun PredictionStrip(
    strip: () -> StripModel,
    onSlot: (StripSlot) -> Unit,
    onAddWord: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = strip()
    if (model.isEmpty) return
    val word = model.addWord
    if (word != null) {
        AddWordChip(word, onAddWord, modifier)
        return
    }
    Row(modifier = modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Slot(model.left, onSlot)
        Slot(model.centre, onSlot)
        Slot(model.right, onSlot)
    }
}

@Composable
private fun RowScope.Slot(slot: StripSlot?, onSlot: (StripSlot) -> Unit) {
    val base = Modifier.weight(1f).fillMaxHeight()
    if (slot == null) {
        Box(base)
        return
    }
    Box(
        modifier =
            base
                .clip(RoundedCornerShape(SlotCorner))
                .clickable(role = Role.Button) { onSlot(slot) }
                .clearAndSetSemantics {
                    contentDescription = slot.description
                    role = Role.Button
                    onClick {
                        onSlot(slot)
                        true
                    }
                }
                .padding(horizontal = SlotPadding),
        contentAlignment = Alignment.Center,
    ) {
        val color = MaterialTheme.colorScheme.onSurface
        val weight = if (slot.bold) FontWeight.Bold else FontWeight.Normal
        if (slot.kind == SlotKind.Phrase) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    slot.text,
                    modifier = Modifier.weight(1f, fill = false),
                    color = color,
                    fontWeight = weight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(PHRASE_HINT, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
        } else {
            Text(
                slot.text,
                color = color,
                fontWeight = weight,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AddWordChip(word: String, onAddWord: () -> Unit, modifier: Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(SlotCorner))
                .clickable(role = Role.Button, onClick = onAddWord)
                .clearAndSetSemantics {
                    contentDescription = StripLabels.addWord(word)
                    role = Role.Button
                    onClick {
                        onAddWord()
                        true
                    }
                }
                .padding(horizontal = SlotPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Add “$word” to dictionary",
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val PHRASE_HINT = " →"
private val SlotCorner = 8.dp
private val SlotPadding = 6.dp
