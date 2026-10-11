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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The strip shown in place of the predictions while a range is selected (docs/PHASE4.md, PR A):
 * expand to the word or the sentence, cycle the case, copy. The actions are [EditingController]'s.
 */
@Composable
internal fun SelectionStrip(editing: EditingController, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        StripAction(
            stringResource(R.string.selection_word),
            stringResource(R.string.selection_word_description),
        ) {
            editing.expandSelectionToWord()
        }
        StripAction(
            stringResource(R.string.selection_sentence),
            stringResource(R.string.selection_sentence_description),
        ) {
            editing.expandSelectionToSentence()
        }
        StripAction(
            stringResource(R.string.selection_case),
            stringResource(R.string.selection_case_description),
        ) {
            editing.cycleCase()
        }
        StripAction(
            stringResource(R.string.selection_copy),
            stringResource(R.string.selection_copy_description),
        ) {
            editing.copySelection()
        }
    }
}

@Composable
private fun RowScope.StripAction(label: String, description: String, action: () -> Unit) {
    Box(
        modifier =
            Modifier.weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(StripActionCorner))
                .clickable(role = Role.Button, onClick = action)
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Button
                    onClick {
                        action()
                        true
                    }
                }
                .padding(horizontal = StripActionPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val StripActionCorner = 8.dp
private val StripActionPadding = 6.dp
