package dev.keycorrect.feature.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The editing panel (docs/PHASE4.md), laid out like Gboard's: a big 3 x 3 pad in the middle
 * (word-left / up / word-right, left / Select / right, Home / down / End), a helper column on the
 * left (select all, copy, cut, ABC) and one on the right (paste, undo, redo, backspace). Selecting
 * a word, sentence or line is in the selection strip. It takes the place of the keys, at the same
 * height; ABC (bottom left, like the other panels) returns to them. The select toggle is drawn on
 * or off, and the buttons that need the field's text are drawn disabled where it is not read
 * (sensitive fields). The caller sizes it ([modifier]); [onPress] is the key feedback.
 */
@Composable
internal fun EditingPanel(
    editing: EditingController,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        HelperColumn(LeftColumn, editing, onPress, Modifier.weight(SIDE_WEIGHT))
        Column(modifier = Modifier.weight(PAD_WEIGHT).fillMaxHeight()) {
            for (row in PadRows) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    for (action in row) {
                        PanelButton(action, editing, onPress, Modifier.weight(1f), PAD_ICON)
                    }
                }
            }
        }
        HelperColumn(RightColumn, editing, onPress, Modifier.weight(SIDE_WEIGHT))
    }
}

@Composable
private fun HelperColumn(
    actions: List<EditingAction>,
    editing: EditingController,
    onPress: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier.fillMaxHeight()) {
        for (action in actions) {
            PanelButton(action, editing, onPress, Modifier.weight(1f).fillMaxWidth(), HELPER_ICON)
        }
    }
}

@Composable
private fun PanelButton(
    action: EditingAction,
    editing: EditingController,
    onPress: () -> Unit,
    modifier: Modifier,
    iconSize: Int,
) {
    val colors = MaterialTheme.colorScheme
    val toggle = action == EditingAction.SelectToggle
    val on = toggle && editing.selectMode
    val enabled = editing.isAvailable(action)
    val click = {
        onPress()
        editing.perform(action)
    }
    val semantics =
        PanelSemantics(
            description = stringResource(action.description),
            toggle = toggle,
            on = on,
            state = stringResource(if (on) R.string.edit_select_on else R.string.edit_select_off),
            enabled = enabled,
            click = click,
        )
    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .padding(KeyGap / 2)
                .clip(RoundedCornerShape(KeyCornerRadius))
                .background(panelFill(action, on, colors))
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .clickable(enabled = enabled, role = Role.Button, onClick = click)
                .clearAndSetSemantics { semantics.apply(this) },
        contentAlignment = Alignment.Center,
    ) {
        PanelContent(
            action,
            stringResource(action.label),
            panelContent(action, on, colors),
            iconSize,
        )
    }
}

/**
 * Text for Select and ABC, an icon for the pad and backspace, and an icon over a small label for
 * the helper columns (whose labels are the short action names).
 */
@Composable
private fun PanelContent(action: EditingAction, label: String, color: Color, iconSize: Int) {
    val icon = action.icon()
    when {
        icon == null ->
            Text(
                label,
                color = color,
                fontSize = if (action == EditingAction.Close) ABC_SIZE.sp else LABEL_SIZE.sp,
                maxLines = 1,
            )
        action in LabelledActions ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PanelIcon(icon, color, iconSize)
                Text(label, color = color, fontSize = SMALL_LABEL_SIZE.sp, maxLines = 1)
            }
        else -> PanelIcon(icon, color, iconSize)
    }
}

@Composable
private fun PanelIcon(icon: ImageVector, color: Color, size: Int) {
    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(size.dp))
}

private fun EditingAction.icon(): ImageVector? = ActionIcons[this]

private val ActionIcons =
    mapOf(
        EditingAction.Up to PanelIcons.Up,
        EditingAction.Down to PanelIcons.Down,
        EditingAction.Left to PanelIcons.Left,
        EditingAction.Right to PanelIcons.Right,
        EditingAction.WordLeft to PanelIcons.WordLeft,
        EditingAction.WordRight to PanelIcons.WordRight,
        EditingAction.Home to PanelIcons.Home,
        EditingAction.End to PanelIcons.End,
        EditingAction.SelectAll to PanelIcons.SelectAll,
        EditingAction.Copy to PanelIcons.Copy,
        EditingAction.Cut to PanelIcons.Cut,
        EditingAction.Paste to PanelIcons.Paste,
        EditingAction.Undo to PanelIcons.Undo,
        EditingAction.Redo to PanelIcons.Redo,
        EditingAction.Backspace to PanelIcons.Backspace,
        EditingAction.Delete to PanelIcons.DeleteForward,
    )

/** What a panel button says to accessibility services; [toggle] ones also say their [state]. */
private class PanelSemantics(
    val description: String,
    val toggle: Boolean,
    val on: Boolean,
    val state: String,
    val enabled: Boolean,
    val click: () -> Unit,
) {
    fun apply(scope: SemanticsPropertyReceiver) {
        scope.contentDescription = description
        scope.role = if (toggle) Role.Switch else Role.Button
        if (toggle) {
            scope.stateDescription = state
            scope.selected = on
        }
        if (!enabled) scope.disabled()
        scope.onClick {
            click()
            true
        }
    }
}

private fun panelFill(action: EditingAction, on: Boolean, colors: ColorScheme): Color =
    when {
        on -> colors.tertiaryContainer
        action == EditingAction.Close -> colors.primary
        else -> colors.surfaceContainerHighest
    }

private fun panelContent(action: EditingAction, on: Boolean, colors: ColorScheme): Color =
    when {
        on -> colors.onTertiaryContainer
        action == EditingAction.Close -> colors.onPrimary
        else -> colors.onSurface
    }

/** The 3 x 3 pad: word and line moves on the corners, arrows on the cross, Select in the centre. */
internal val PadRows =
    listOf(
        listOf(EditingAction.WordLeft, EditingAction.Up, EditingAction.WordRight),
        listOf(EditingAction.Left, EditingAction.SelectToggle, EditingAction.Right),
        listOf(EditingAction.Home, EditingAction.Down, EditingAction.End),
    )

/** The left helper column, top to bottom; ABC is at the bottom like on the other panels. */
internal val LeftColumn =
    listOf(
        EditingAction.SelectAll,
        EditingAction.Copy,
        EditingAction.Cut,
        EditingAction.Close,
    )

/** The right helper column; forward delete does not fit, backspace does. */
internal val RightColumn =
    listOf(
        EditingAction.Paste,
        EditingAction.Undo,
        EditingAction.Redo,
        EditingAction.Backspace,
    )

/** The helper buttons that carry a small label under their icon. */
private val LabelledActions =
    setOf(
        EditingAction.SelectAll,
        EditingAction.Copy,
        EditingAction.Cut,
        EditingAction.Paste,
        EditingAction.Undo,
        EditingAction.Redo,
    )

private const val PAD_WEIGHT = 3.4f
private const val SIDE_WEIGHT = 1.3f

private const val DISABLED_ALPHA = 0.38f
private const val LABEL_SIZE = 15
private const val SMALL_LABEL_SIZE = 11
private const val ABC_SIZE = 18
private const val PAD_ICON = 32
private const val HELPER_ICON = 22
