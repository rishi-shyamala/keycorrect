package dev.keycorrect.feature.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
 * The editing panel (docs/PHASE4.md): a joystick block on the left (Home / up / End, left / Select
 * / right, word-left / down / word-right, then ABC, backspace and delete) and, after a divider, the
 * selection and clipboard actions on the right. It takes the place of the keys, at the same height.
 * ABC (bottom left, like the other panels) returns to the keys. The select toggle is drawn on or
 * off, and the buttons that need the field's text are drawn disabled where it is not read
 * (sensitive fields). The caller sizes it ([modifier]); [onPress] is the key feedback.
 */
@Composable
internal fun EditingPanel(
    editing: EditingController,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        Column(modifier = Modifier.weight(PAD_WEIGHT).fillMaxHeight()) {
            for ((index, row) in PadRows.withIndex()) {
                Row(modifier = Modifier.weight(RowWeights[index]).fillMaxWidth()) {
                    for ((col, action) in row.withIndex()) {
                        PanelButton(action, editing, onPress, PadColumnWeights[col])
                    }
                }
            }
        }
        Box(
            Modifier.fillMaxHeight()
                .padding(vertical = DIVIDER_INSET)
                .width(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
        Column(modifier = Modifier.weight(ACTIONS_WEIGHT).fillMaxHeight()) {
            for ((index, row) in ActionRows.withIndex()) {
                Row(modifier = Modifier.weight(RowWeights[index]).fillMaxWidth()) {
                    for (action in row) PanelButton(action, editing, onPress, 1f)
                }
            }
            Spacer(Modifier.weight(RowWeights.last()))
        }
    }
}

@Composable
private fun RowScope.PanelButton(
    action: EditingAction,
    editing: EditingController,
    onPress: () -> Unit,
    weight: Float,
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
            Modifier.weight(weight)
                .fillMaxHeight()
                .padding(KeyGap / 2)
                .clip(RoundedCornerShape(KeyCornerRadius))
                .background(panelFill(action, on, colors))
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .clickable(enabled = enabled, role = Role.Button, onClick = click)
                .clearAndSetSemantics { semantics.apply(this) },
        contentAlignment = Alignment.Center,
    ) {
        PanelContent(action, stringResource(action.label), panelContent(action, on, colors))
    }
}

/** An icon for the pad's arrows and delete keys, text for the rest; Word keys have both. */
@Composable
private fun PanelContent(action: EditingAction, label: String, color: Color) {
    val icon = action.icon()
    when {
        action == EditingAction.WordLeft || action == EditingAction.WordRight -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (action == EditingAction.WordLeft) PanelIcon(PanelIcons.Left, color, SMALL_ICON)
                Text(label, color = color, fontSize = LABEL_SIZE.sp, maxLines = 1)
                if (action == EditingAction.WordRight) {
                    PanelIcon(PanelIcons.Right, color, SMALL_ICON)
                }
            }
        }
        icon != null -> PanelIcon(icon, color, if (action.isArrow()) ARROW_ICON else ICON)
        else ->
            Text(
                label,
                color = color,
                fontSize = if (action == EditingAction.Close) ABC_SIZE.sp else LABEL_SIZE.sp,
                maxLines = 1,
            )
    }
}

@Composable
private fun PanelIcon(icon: ImageVector, color: Color, size: Int) {
    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(size.dp))
}

private fun EditingAction.isArrow() =
    this == EditingAction.Up ||
        this == EditingAction.Down ||
        this == EditingAction.Left ||
        this == EditingAction.Right

private fun EditingAction.icon(): ImageVector? =
    when (this) {
        EditingAction.Up -> PanelIcons.Up
        EditingAction.Down -> PanelIcons.Down
        EditingAction.Left -> PanelIcons.Left
        EditingAction.Right -> PanelIcons.Right
        EditingAction.Backspace -> PanelIcons.Backspace
        EditingAction.Delete -> PanelIcons.DeleteForward
        else -> null
    }

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

/** The joystick block: moves around the Select toggle, then ABC, backspace and delete. */
private val PadRows =
    listOf(
        listOf(EditingAction.Home, EditingAction.Up, EditingAction.End),
        listOf(EditingAction.Left, EditingAction.SelectToggle, EditingAction.Right),
        listOf(EditingAction.WordLeft, EditingAction.Down, EditingAction.WordRight),
        listOf(EditingAction.Close, EditingAction.Backspace, EditingAction.Delete),
    )

/** The right block: select by unit, clipboard, history and select all; its fourth row is empty. */
private val ActionRows =
    listOf(
        listOf(EditingAction.SelectWord, EditingAction.SelectSentence, EditingAction.SelectLine),
        listOf(EditingAction.Cut, EditingAction.Copy, EditingAction.Paste),
        listOf(EditingAction.Undo, EditingAction.Redo, EditingAction.SelectAll),
    )

/** The three D-pad rows are a little taller than the bottom one; the middle column is wider. */
private val RowWeights = listOf(1.1f, 1.1f, 1.1f, 0.85f)
private val PadColumnWeights = listOf(1f, 1.2f, 1f)
private const val PAD_WEIGHT = 3.2f
private const val ACTIONS_WEIGHT = 3f
private val DIVIDER_INSET = 8.dp

private const val DISABLED_ALPHA = 0.38f
private const val LABEL_SIZE = 14
private const val ABC_SIZE = 18
private const val ARROW_ICON = 34
private const val ICON = 26
private const val SMALL_ICON = 16
