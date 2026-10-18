package dev.keycorrect.feature.keyboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import dev.keycorrect.feature.keyboard.prediction.SlotKind
import dev.keycorrect.feature.keyboard.prediction.StripModel
import dev.keycorrect.feature.keyboard.prediction.StripSlot
import dev.keycorrect.feature.keyboard.prediction.labelRes

/**
 * The prediction strip: three slots in the fixed-height strip area, each as wide as its text needs
 * (see [slotWeight]). [strip] is a lambda so the state behind it is read here, in this composable
 * only; it recomposes when the suggestions change and for no other reason (typing in a field
 * without suggestions never gets here at all). Empty slots keep a minimum space.
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
    val measurer = rememberTextMeasurer(cacheSize = MEASURE_CACHE)
    Row(modifier = modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Slot(model.left, measurer, onSlot, onSlotLongPress, blockable)
        Slot(model.centre, measurer, onSlot, onSlotLongPress, blockable)
        Slot(model.right, measurer, onSlot, onSlotLongPress, blockable)
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

/** The strings a slot's accessibility semantics use, built once per slot rather than per frame. */
private class SlotLabels(val description: String, val neverAction: String)

/**
 * One slot. Its inputs are a stable [StripSlot], a flag and callbacks the caller keeps stable, so a
 * slot whose word did not change is skipped when its neighbour's did. Its labels are remembered per
 * slot, and the plain [clickable] is used where there is no long press ([blockable] false, or a
 * kind that cannot be blocked), the combined one only where the never-suggest long press exists.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.Slot(
    slot: StripSlot?,
    measurer: TextMeasurer,
    onSlot: (StripSlot) -> Unit,
    onLongPress: (StripSlot) -> Unit,
    blockable: Boolean,
) {
    val base = Modifier.weight(slotWeight(slot)).fillMaxHeight()
    if (slot == null) {
        Box(base)
        return
    }
    val canBlockSlot =
        blockable && (slot.kind == SlotKind.Suggestion || slot.kind == SlotKind.Autocorrect)
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val labels =
        remember(slot, resources, configuration) {
            SlotLabels(
                resources.getString(slot.labelRes(), slot.label),
                resources.getString(R.string.strip_never_action, slot.insert),
            )
        }
    val tap = remember(slot, onSlot) { { onSlot(slot) } }
    val longPress = remember(slot, onLongPress) { { onLongPress(slot) } }
    val press =
        if (canBlockSlot) {
            Modifier.combinedClickable(role = Role.Button, onLongClick = longPress, onClick = tap)
        } else {
            Modifier.clickable(role = Role.Button, onClick = tap)
        }
    Box(
        modifier =
            base
                .clip(RoundedCornerShape(SlotCorner))
                .then(press)
                .clearAndSetSemantics {
                    contentDescription = labels.description
                    role = Role.Button
                    onClick {
                        tap()
                        true
                    }
                    if (canBlockSlot) {
                        onLongClick(labels.neverAction) {
                            longPress()
                            true
                        }
                        customActions =
                            listOf(
                                CustomAccessibilityAction(labels.neverAction) {
                                    longPress()
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
            ShrinkingText(slot, measurer, color, weight)
        }
    }
}

/**
 * A slot's word, in full when it can be: the font shrinks to [SHRINK_FACTOR] of its size (not below
 * [MIN_SLOT_SIZE]) when the text overflows its slot, and only text that still doesn't fit is
 * ellipsized. Measured once per text and slot width, in the layout phase (see [fitText]).
 */
@Composable
private fun ShrinkingText(
    slot: StripSlot,
    measurer: TextMeasurer,
    color: Color,
    weight: FontWeight,
) {
    if (slot.kind == SlotKind.Emoji) {
        Text(
            slot.text,
            color = color,
            fontSize = EmojiSlotSize,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        return
    }
    val local = LocalTextStyle.current
    val style = remember(local, weight) { local.copy(fontWeight = weight) }
    val base = style.fontSize.takeOrElse { DEFAULT_SLOT_SIZE }
    Spacer(Modifier.fitText(slot.text, style, color, base, measurer))
}

/** [base] shrunk to [SHRINK_FACTOR] of its size, but never below [MIN_SLOT_SIZE] (or [base]). */
internal fun shrunkSize(base: TextUnit): TextUnit =
    minOf(base.value, maxOf(base.value * SHRINK_FACTOR, MIN_SLOT_SIZE.value)).sp

/**
 * The share of the strip a slot gets, proportional to its text (with a minimum, and a maximum so a
 * phrase can't squeeze the other two), so that a word shows in full where a short one beside it
 * leaves room. An empty slot keeps the minimum.
 */
internal fun slotWeight(slot: StripSlot?): Float {
    if (slot == null) return MIN_SLOT_WEIGHT
    val extra = if (slot.kind == SlotKind.Phrase) PHRASE_HINT.length else 0
    return (slot.text.length + extra).toFloat().coerceIn(MIN_SLOT_WEIGHT, MAX_SLOT_WEIGHT)
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
private const val MIN_SLOT_WEIGHT = 5f
private const val MAX_SLOT_WEIGHT = 14f
private const val SHRINK_FACTOR = 0.8f
private val DEFAULT_SLOT_SIZE = 14.sp
private val MIN_SLOT_SIZE = 12.sp
private const val MEASURE_CACHE = 24
private val SlotCorner = 8.dp
private val SlotPadding = 4.dp

/** The emoji in the strip is drawn larger than the words around it. */
private val EmojiSlotSize = 22.sp
private val PromptButtonPadding = 14.dp
