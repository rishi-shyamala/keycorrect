package dev.keycorrect.feature.keyboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.keycorrect.feature.keyboard.prediction.SlotKind
import dev.keycorrect.feature.keyboard.prediction.StripModel
import dev.keycorrect.feature.keyboard.prediction.StripSlot
import dev.keycorrect.feature.keyboard.prediction.labelRes

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
    onSlotLongPress: (StripSlot) -> Unit = {},
    onNeverYes: () -> Unit = {},
    onNeverCancel: () -> Unit = {},
    canBlock: () -> Boolean = { false },
) {
    val model = strip()
    if (model.isEmpty) return
    val never = model.neverSuggest
    if (never != null) {
        NeverSuggestPrompt(never, onNeverYes, onNeverCancel, modifier)
        return
    }
    val word = model.addWord
    if (word != null) {
        AddWordChip(word, onAddWord, modifier)
        return
    }
    val blockable = canBlock()
    Row(modifier = modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Slot(model.left, onSlot, onSlotLongPress, blockable)
        Slot(model.centre, onSlot, onSlotLongPress, blockable)
        Slot(model.right, onSlot, onSlotLongPress, blockable)
    }
}

/**
 * The inline "Never suggest “x”? Yes / Cancel" confirmation that takes over the strip after a long
 * press. No dialog or window: it is announced as a polite live region and both answers are plain
 * buttons, so TalkBack reads the question and offers the two answers.
 */
@Composable
private fun NeverSuggestPrompt(
    word: String,
    onYes: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
) {
    val description = stringResource(R.string.strip_never_description, word)
    Row(
        modifier =
            modifier.fillMaxSize().semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = description
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.strip_never_question, word),
            modifier = Modifier.weight(1f).padding(horizontal = SlotPadding),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        PromptButton(
            stringResource(R.string.strip_never_yes),
            stringResource(R.string.strip_never_yes_description, word),
            onYes,
        )
        PromptButton(
            stringResource(R.string.strip_never_cancel),
            stringResource(R.string.strip_never_cancel_description),
            onCancel,
        )
    }
}

@Composable
private fun PromptButton(label: String, description: String, onClick: () -> Unit) {
    Box(
        modifier =
            Modifier.fillMaxHeight()
                .clip(RoundedCornerShape(SlotCorner))
                .clickable(role = Role.Button, onClick = onClick)
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Button
                    onClick {
                        onClick()
                        true
                    }
                }
                .padding(horizontal = PromptButtonPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.Slot(
    slot: StripSlot?,
    onSlot: (StripSlot) -> Unit,
    onLongPress: (StripSlot) -> Unit,
    blockable: Boolean,
) {
    val base = Modifier.weight(1f).fillMaxHeight()
    if (slot == null) {
        Box(base)
        return
    }
    val canBlockSlot =
        blockable && (slot.kind == SlotKind.Suggestion || slot.kind == SlotKind.Autocorrect)
    val description = stringResource(slot.labelRes(), slot.label)
    val neverLabel = stringResource(R.string.strip_never_action, slot.insert)
    Box(
        modifier =
            base
                .clip(RoundedCornerShape(SlotCorner))
                .combinedClickable(
                    role = Role.Button,
                    onLongClick = if (canBlockSlot) ({ onLongPress(slot) }) else null,
                    onClick = { onSlot(slot) },
                )
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Button
                    onClick {
                        onSlot(slot)
                        true
                    }
                    if (canBlockSlot) {
                        onLongClick(neverLabel) {
                            onLongPress(slot)
                            true
                        }
                        customActions =
                            listOf(
                                CustomAccessibilityAction(neverLabel) {
                                    onLongPress(slot)
                                    true
                                }
                            )
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
                fontSize = if (slot.kind == SlotKind.Emoji) EmojiSlotSize else TextUnit.Unspecified,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AddWordChip(word: String, onAddWord: () -> Unit, modifier: Modifier) {
    val description = stringResource(R.string.strip_add_word_description, word)
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(SlotCorner))
                .clickable(role = Role.Button, onClick = onAddWord)
                .clearAndSetSemantics {
                    contentDescription = description
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
            stringResource(R.string.strip_add_word, word),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val PHRASE_HINT = " →"
private val SlotCorner = 8.dp
private val SlotPadding = 6.dp

/** The emoji in the strip is drawn larger than the words around it. */
private val EmojiSlotSize = 22.sp
private val PromptButtonPadding = 14.dp
