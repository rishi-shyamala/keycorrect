package dev.keycorrect.feature.keyboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp

/** The icon buttons of the shortcut row (docs/PHASE4.md, feedback round 1, #5). */
internal enum class Shortcut {
    /** Opens the editing panel, as hold-and-release on the space bar does. */
    Editing,

    /** Opens the emoji panel, as the emoji key does. Not in sensitive fields. */
    Emoji,

    /** Opens the clipboard panel. Only when [KeyboardController.clipboardAvailable]. */
    Clipboard,

    /** Opens the settings app. */
    Settings,
}

/** What fills the suggestion strip area while the keys are showing. */
internal enum class StripContent {
    /** Suggestions, the add-word / never-suggest prompt or the selection strip. */
    Predictions,

    /** The recent-clip chip. */
    ClipChip,

    /** The row of [Shortcut] icons. */
    Shortcuts,
}

/**
 * The visibility rule. Predictions own the area while there is a strip model, a word being composed
 * (typing) or a selection strip; otherwise a fresh clip ([hasClip]) outranks the shortcuts, which
 * show when there is nothing else. Autofill chips outrank all of this and are handled by the
 * caller.
 */
internal fun stripContent(
    stripEmpty: Boolean,
    composing: Boolean,
    selectionStrip: Boolean,
    hasClip: Boolean,
): StripContent =
    when {
        !stripEmpty || composing || selectionStrip -> StripContent.Predictions
        hasClip -> StripContent.ClipChip
        else -> StripContent.Shortcuts
    }

/**
 * The shortcuts shown, in order. Emoji and the clipboard are hidden in [sensitive] fields (the
 * emoji key is absent there and the clipboard is off), and the clipboard whenever it is unavailable
 * (history off).
 */
internal fun shortcutsFor(sensitive: Boolean, clipboardAvailable: Boolean): List<Shortcut> =
    buildList {
        add(Shortcut.Editing)
        if (!sensitive) add(Shortcut.Emoji)
        if (clipboardAvailable && !sensitive) add(Shortcut.Clipboard)
        add(Shortcut.Settings)
    }

/**
 * The Samsung-style shared row: evenly spaced icon buttons filling the strip area. It reads
 * [KeyboardController.shortcutRowVisible] here and nowhere above, so typing recomposes only this
 * (which returns at once) and never the rest of the strip area.
 */
@Composable
internal fun ShortcutRow(
    controller: KeyboardController,
    onPress: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!controller.shortcutRowVisible) return
    Row(modifier = modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        controller.shortcuts.forEach { shortcut ->
            ShortcutButton(
                icon = shortcut.icon(),
                description = shortcut.description(),
                modifier = Modifier.weight(1f),
            ) {
                onPress()
                controller.onShortcut(shortcut, onOpenSettings)
            }
        }
    }
}

/**
 * One icon button. Each takes an equal share of the row (at least 48 dp wide on a phone); the strip
 * is 40 dp tall, so the touch target is the strip's full height.
 */
@Composable
private fun ShortcutButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            modifier.fillMaxHeight().clickable(onClick = onClick).clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

private fun Shortcut.icon(): ImageVector =
    when (this) {
        Shortcut.Editing -> ShortcutIcons.Editing
        Shortcut.Emoji -> ShortcutIcons.Emoji
        Shortcut.Clipboard -> ShortcutIcons.Clipboard
        Shortcut.Settings -> ShortcutIcons.Settings
    }

@Composable
private fun Shortcut.description(): String =
    stringResource(
        when (this) {
            Shortcut.Editing -> R.string.shortcut_editing
            Shortcut.Emoji -> R.string.shortcut_emoji
            Shortcut.Clipboard -> R.string.shortcut_clipboard
            Shortcut.Settings -> R.string.shortcut_settings
        }
    )
