package dev.keycorrect.feature.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp

/**
 * The strip shown in place of the predictions while a range is selected (docs/PHASE4.md, feedback
 * round 4): `[start back, forward] Word Sentence Line [end back, forward]` and the case change. A
 * unit button expands the selection to whole units and makes that unit current (highlighted, Word
 * at first; forgotten when the strip leaves with the selection). The left pair moves the
 * selection's start a current unit back or forward, the right pair its end. All icons, so nothing
 * is cut off on a narrow phone. The actions are [EditingController]'s.
 */
@Composable
internal fun SelectionStrip(editing: EditingController, modifier: Modifier = Modifier) {
    var unit by remember { mutableStateOf(SelectionUnit.Word) }
    val move = { edge: SelectionEdge, forward: Boolean ->
        editing.moveSelectionEdge(unit, edge, forward)
        Unit
    }
    Row(modifier = modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        StripAction(
            PanelIcons.Left,
            stringResource(R.string.selection_start_back_description),
        ) {
            move(SelectionEdge.Start, false)
        }
        StripAction(
            PanelIcons.Right,
            stringResource(R.string.selection_start_forward_description),
        ) {
            move(SelectionEdge.Start, true)
        }
        for (candidate in SelectionUnit.entries) {
            StripAction(
                candidate.icon(),
                stringResource(candidate.description()),
                weight = UNIT_WEIGHT,
                selected = candidate == unit,
            ) {
                unit = candidate
                editing.expandSelection(candidate)
            }
        }
        StripAction(
            PanelIcons.Left,
            stringResource(R.string.selection_end_back_description),
        ) {
            move(SelectionEdge.End, false)
        }
        StripAction(
            PanelIcons.Right,
            stringResource(R.string.selection_end_forward_description),
        ) {
            move(SelectionEdge.End, true)
        }
        StripAction(
            PanelIcons.Case,
            stringResource(R.string.selection_case_description),
        ) {
            editing.cycleCase()
        }
    }
}

private fun SelectionUnit.icon(): ImageVector =
    when (this) {
        SelectionUnit.Word -> PanelIcons.SelectWord
        SelectionUnit.Sentence -> PanelIcons.SelectSentence
        SelectionUnit.Line -> PanelIcons.SelectLine
    }

private fun SelectionUnit.description(): Int =
    when (this) {
        SelectionUnit.Word -> R.string.selection_word_description
        SelectionUnit.Sentence -> R.string.selection_sentence_description
        SelectionUnit.Line -> R.string.selection_line_description
    }

/** One strip button; [selected] (the current unit) is drawn on a tinted background. */
@Composable
private fun RowScope.StripAction(
    icon: ImageVector,
    description: String,
    weight: Float = 1f,
    selected: Boolean? = null,
    action: () -> Unit,
) {
    Box(
        modifier =
            Modifier.weight(weight)
                .fillMaxHeight()
                .widthIn(min = StripActionMinWidth)
                .clip(RoundedCornerShape(StripActionCorner))
                .background(
                    if (selected == true) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    else Color.Transparent
                )
                .clickable(role = Role.Button, onClick = action)
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Button
                    if (selected != null) this.selected = selected
                    onClick {
                        action()
                        true
                    }
                }
                .padding(horizontal = StripActionPadding),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(StripActionIcon),
        )
    }
}

private val StripActionCorner = 8.dp
private val StripActionPadding = 4.dp
private val StripActionIcon = 24.dp

/** Every button is at least this wide: 9 of them fit 360 dp. */
private val StripActionMinWidth = 36.dp

/** The unit buttons are a little wider than the arrows. */
private const val UNIT_WEIGHT = 1.2f
