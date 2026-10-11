package dev.keycorrect.feature.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.unit.sp

/**
 * The editing panel (docs/PHASE4.md, PR A): a grid of buttons that takes the place of the keys, at
 * the same height. Opened by a hold on space; ABC returns to the keys. The select toggle is drawn
 * on or off, and the buttons that need the field's text are drawn disabled where it is not read
 * (sensitive fields). The caller sizes it ([modifier]); [onPress] is the key feedback.
 */
@Composable
internal fun EditingPanel(
    editing: EditingController,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        for (row in PanelRows) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                for (action in row) PanelButton(action, editing, onPress)
            }
        }
    }
}

@Composable
private fun RowScope.PanelButton(
    action: EditingAction,
    editing: EditingController,
    onPress: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(action.label)
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
            Modifier.weight(1f)
                .fillMaxHeight()
                .padding(KeyGap / 2)
                .clip(RoundedCornerShape(KeyCornerRadius))
                .background(panelFill(action, on, colors))
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .clickable(enabled = enabled, role = Role.Button, onClick = click)
                .clearAndSetSemantics { semantics.apply(this) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = panelContent(action, on, colors),
            fontSize = if (label.length <= GLYPH_LENGTH) GLYPH_SIZE.sp else LABEL_SIZE.sp,
            maxLines = 1,
        )
    }
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

/** Rows of the grid: movement, cursor and delete keys, selection and history, clipboard. */
private val PanelRows =
    listOf(
        listOf(
            EditingAction.Close,
            EditingAction.Home,
            EditingAction.Up,
            EditingAction.End,
            EditingAction.Backspace,
            EditingAction.Delete,
        ),
        listOf(
            EditingAction.SelectToggle,
            EditingAction.Left,
            EditingAction.Down,
            EditingAction.Right,
            EditingAction.WordLeft,
            EditingAction.WordRight,
        ),
        listOf(
            EditingAction.SelectWord,
            EditingAction.SelectSentence,
            EditingAction.SelectLine,
            EditingAction.SelectAll,
            EditingAction.Undo,
            EditingAction.Redo,
        ),
        listOf(EditingAction.Cut, EditingAction.Copy, EditingAction.Paste),
    )

private const val DISABLED_ALPHA = 0.38f
private const val GLYPH_LENGTH = 1
private const val GLYPH_SIZE = 22
private const val LABEL_SIZE = 14
