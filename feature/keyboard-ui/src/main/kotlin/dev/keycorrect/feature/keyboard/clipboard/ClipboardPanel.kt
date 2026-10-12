package dev.keycorrect.feature.keyboard.clipboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.keycorrect.feature.keyboard.KeyboardController
import dev.keycorrect.feature.keyboard.KeyboardPanel
import dev.keycorrect.feature.keyboard.R
import dev.keycorrect.feature.keyboard.emoji.ABC_WIDTH
import dev.keycorrect.feature.keyboard.emoji.BackspaceButton
import dev.keycorrect.feature.keyboard.emoji.BarButton

/**
 * The clipboard panel (docs/PHASE4.md, PR C), in the place of the keys and as tall as they are: a
 * grid of the history (pinned first, as the host orders it) and a bar with ABC and backspace. A tap
 * pastes ([KeyboardController.onClipPicked]); a long press or the ⋮ button swaps the card's content
 * for Pin/Delete/Clean-link actions. The title row with "Clear" is [ClipboardTitleStrip], in the
 * strip's place. Clip text is shown but never logged. [onPress] is the key feedback.
 */
@Composable
internal fun ClipboardPanel(
    controller: KeyboardController,
    barHeight: Dp,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clips = controller.clipboard.clips
    Column(modifier = modifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (clips.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.clipboard_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(CARD_MIN_WIDTH),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
                    verticalArrangement = Arrangement.spacedBy(CARD_GAP),
                ) {
                    items(clips, key = { it.id }) { clip ->
                        ClipCard(clip, controller, onPress)
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(barHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarButton(
                text = stringResource(R.string.emoji_abc),
                description = stringResource(R.string.emoji_abc_description),
                highlighted = true,
                modifier = Modifier.width(ABC_WIDTH),
            ) {
                onPress()
                controller.showPanel(KeyboardPanel.Keys)
            }
            Box(Modifier.weight(1f))
            BackspaceButton(controller, onPress, Modifier.width(ABC_WIDTH))
        }
    }
}

/**
 * The panel's title row, drawn in the suggestion strip's place: "Clipboard" and "Clear", which
 * becomes an inline "Clear all? Yes / Cancel" (no dialog: an IME has no window for one). Clear only
 * deletes unpinned clips, so it is not offered when there are none.
 */
@Composable
internal fun ClipboardTitleStrip(controller: KeyboardController, onPress: () -> Unit) {
    val state = controller.clipboardPanel
    val colors = MaterialTheme.colorScheme
    val canClear = controller.clipboard.clips.any { !it.pinned }
    Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        if (state.confirmingClear) {
            val question = stringResource(R.string.clipboard_clear_question)
            Text(
                question,
                modifier =
                    Modifier.weight(1f).padding(horizontal = 10.dp).semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = question
                    },
                color = colors.onSurface,
                maxLines = 1,
            )
            TitleButton(
                stringResource(R.string.clipboard_clear_yes),
                stringResource(R.string.clipboard_clear_yes_description),
            ) {
                onPress()
                controller.clearClips()
            }
            TitleButton(
                stringResource(R.string.clipboard_clear_cancel),
                stringResource(R.string.clipboard_clear_cancel_description),
            ) {
                onPress()
                state.cancelClear()
            }
        } else {
            Text(
                stringResource(R.string.clipboard_title),
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                color = colors.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            if (canClear) {
                TitleButton(
                    stringResource(R.string.clipboard_clear),
                    stringResource(R.string.clipboard_clear_description),
                ) {
                    onPress()
                    state.askToClear()
                }
            }
        }
    }
}

@Composable
private fun TitleButton(label: String, description: String, onClick: () -> Unit) {
    Box(
        modifier =
            Modifier.fillMaxHeight()
                .clip(RoundedCornerShape(CARD_CORNER))
                .clickable(role = Role.Button, onClick = onClick)
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Button
                    onClick {
                        onClick()
                        true
                    }
                }
                .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}

/** The words on one card's actions, resolved once for its semantics and its action buttons. */
private class ClipLabels(
    val paste: String,
    val pin: String,
    val delete: String,
    val clean: String,
    val menu: String,
)

@Composable
private fun clipLabels(clip: ClipItem): ClipLabels =
    ClipLabels(
        paste =
            when {
                clip.kind == ClipItem.Kind.Image ->
                    stringResource(R.string.clipboard_paste_image_description)
                else -> stringResource(R.string.clipboard_paste_description, clip.preview)
            },
        pin = stringResource(if (clip.pinned) R.string.clipboard_unpin else R.string.clipboard_pin),
        delete = stringResource(R.string.clipboard_delete),
        clean = stringResource(R.string.clipboard_clean),
        menu = stringResource(R.string.clipboard_menu_description),
    )

/** One history entry: its content, or (after a long press or ⋮) its actions. */
@Composable
private fun ClipCard(clip: ClipItem, controller: KeyboardController, onPress: () -> Unit) {
    val state = controller.clipboardPanel
    val colors = MaterialTheme.colorScheme
    val menuOpen = state.menuFor == clip.id
    val labels = clipLabels(clip)
    val openMenu = {
        onPress()
        state.openMenu(clip.id)
    }
    val interaction =
        if (menuOpen) {
            Modifier
        } else {
            Modifier.clipCardInteraction(clip, controller, labels, onPress, openMenu)
        }
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(
                    min =
                        if (clip.kind == ClipItem.Kind.Image) IMAGE_CARD_HEIGHT
                        else TEXT_CARD_MIN_HEIGHT
                )
                .clip(RoundedCornerShape(CARD_CORNER))
                .background(colors.surfaceContainerHighest)
                .then(interaction)
    ) {
        if (menuOpen) {
            ClipActions(clip, controller, onPress, labels)
        } else {
            ClipContent(clip, controller)
            Text(
                "\u22EE",
                modifier =
                    Modifier.align(Alignment.TopEnd)
                        .width(MENU_BUTTON)
                        .height(MENU_BUTTON)
                        .clickable(onClick = openMenu)
                        .padding(top = 3.dp),
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                fontSize = 16.sp,
            )
        }
    }
}

/**
 * Tap pastes, long press opens the card's actions. For a screen reader the card is one button with
 * Pin, Delete and Clean link as custom actions (the ⋮ button is not a node of its own).
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.clipCardInteraction(
    clip: ClipItem,
    controller: KeyboardController,
    labels: ClipLabels,
    onPress: () -> Unit,
    openMenu: () -> Unit,
): Modifier =
    combinedClickable(
            role = Role.Button,
            onLongClick = openMenu,
            onClick = {
                onPress()
                controller.onClipPicked(clip.id)
            },
        )
        .clearAndSetSemantics {
            contentDescription = labels.paste
            role = Role.Button
            onClick {
                controller.onClipPicked(clip.id)
                true
            }
            onLongClick(labels.menu) {
                openMenu()
                true
            }
            customActions = buildList {
                add(
                    CustomAccessibilityAction(labels.pin) {
                        controller.pinClip(clip.id, !clip.pinned)
                        true
                    }
                )
                add(
                    CustomAccessibilityAction(labels.delete) {
                        controller.deleteClip(clip.id)
                        true
                    }
                )
                if (clip.cleanableUrl) {
                    add(
                        CustomAccessibilityAction(labels.clean) {
                            controller.cleanClipUrl(clip.id)
                            true
                        }
                    )
                }
            }
        }

/** Text with its badge (pin), or the thumbnail of an image. */
@Composable
private fun ClipContent(clip: ClipItem, controller: KeyboardController) {
    val colors = MaterialTheme.colorScheme
    val badges = if (clip.pinned) "📌 " else ""
    if (clip.kind == ClipItem.Kind.Image) {
        val thumbnail = controller.clipboard.thumbnail(clip.id)
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(IMAGE_CARD_HEIGHT),
            )
        } else {
            // Still loading in the host: a placeholder of the same size, so the grid doesn't jump.
            Box(
                Modifier.fillMaxWidth().height(IMAGE_CARD_HEIGHT),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.clipboard_image),
                    color = colors.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
        }
        if (badges.isNotEmpty()) {
            Text(
                badges.trim(),
                fontSize = 11.sp,
                modifier =
                    Modifier.padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.surfaceContainerHighest)
                        .padding(horizontal = 3.dp),
            )
        }
    } else {
        Text(
            badges + clip.preview,
            modifier =
                Modifier.fillMaxWidth()
                    .padding(start = 10.dp, end = MENU_BUTTON, top = 8.dp, bottom = 8.dp),
            color = colors.onSurface,
            fontSize = 13.sp,
            maxLines = PREVIEW_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Pin/Unpin, Delete, Clean link (URLs only) and Close, as plain buttons in two rows. */
@Composable
private fun ClipActions(
    clip: ClipItem,
    controller: KeyboardController,
    onPress: () -> Unit,
    labels: ClipLabels,
) {
    val state = controller.clipboardPanel
    Column(Modifier.fillMaxWidth().padding(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            ActionButton(labels.pin, Modifier.weight(1f)) {
                onPress()
                controller.pinClip(clip.id, !clip.pinned)
                state.closeMenu()
            }
            ActionButton(labels.delete, Modifier.weight(1f)) {
                onPress()
                controller.deleteClip(clip.id)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            if (clip.cleanableUrl) {
                ActionButton(labels.clean, Modifier.weight(1f)) {
                    onPress()
                    controller.cleanClipUrl(clip.id)
                }
            }
            ActionButton(stringResource(R.string.clipboard_menu_close), Modifier.weight(1f)) {
                onPress()
                state.closeMenu()
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier =
            modifier
                .heightIn(min = ACTION_HEIGHT)
                .clip(RoundedCornerShape(CARD_CORNER))
                .clickable(role = Role.Button, onClick = onClick)
                .clearAndSetSemantics {
                    contentDescription = label
                    role = Role.Button
                    onClick {
                        onClick()
                        true
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val CARD_MIN_WIDTH = 160.dp
private val CARD_GAP = 4.dp
private val CARD_CORNER = 8.dp
private val TEXT_CARD_MIN_HEIGHT = 64.dp
private val IMAGE_CARD_HEIGHT = 96.dp
private val MENU_BUTTON = 28.dp
private val ACTION_HEIGHT = 32.dp
private const val PREVIEW_LINES = 3
