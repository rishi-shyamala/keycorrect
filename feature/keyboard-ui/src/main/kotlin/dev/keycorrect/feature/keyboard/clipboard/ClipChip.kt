package dev.keycorrect.feature.keyboard.clipboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.keycorrect.feature.keyboard.KeyboardController
import dev.keycorrect.feature.keyboard.R
import dev.keycorrect.feature.keyboard.ShortcutIcons

/**
 * The "copied just now" chip, drawn in the suggestion strip when it has nothing else to show
 * ([KeyboardController.stripClip]): "clipboard icon + preview", tap to paste, ✕ to dismiss it for
 * that clip. It is shown in any field, password fields included. Reads
 * [KeyboardController.stripClip] here, so a keystroke recomposes this and nothing above it.
 */
@Composable
internal fun ClipChip(controller: KeyboardController, onPress: () -> Unit) {
    // Derived, so a keystroke that leaves the chip as it was (usually: absent) recomposes nothing.
    val clipState = remember(controller) { derivedStateOf { controller.stripClip } }
    val clip = clipState.value ?: return
    val text = clip.preview.takeIf { clip.kind == ClipItem.Kind.Text }
    val label =
        stringResource(R.string.clipboard_chip, text ?: stringResource(R.string.clipboard_image))
    val description =
        if (text == null) stringResource(R.string.clipboard_chip_image_description)
        else stringResource(R.string.clipboard_chip_description, text)
    val dismiss = stringResource(R.string.clipboard_chip_dismiss_description)
    Row(
        modifier = Modifier.fillMaxSize().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChipButton(
            label = label,
            description = description,
            modifier = Modifier.weight(1f, fill = false),
            primary = true,
            icon = ShortcutIcons.Clipboard,
        ) {
            onPress()
            controller.onClipPicked(clip.id)
        }
        ChipButton(
            label = stringResource(R.string.clipboard_chip_dismiss),
            description = dismiss,
            modifier = Modifier.widthIn(min = 40.dp),
            primary = false,
        ) {
            onPress()
            controller.dismissClipChip(clip.id)
        }
    }
}

@Composable
@Suppress("LongParameterList") // one small private button; every part is drawn
private fun ChipButton(
    label: String,
    description: String,
    modifier: Modifier,
    primary: Boolean,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .clip(RoundedCornerShape(CHIP_CORNER))
                .background(if (primary) colors.secondaryContainer else Color.Transparent)
                .clickable(role = Role.Button, onClick = onClick)
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Button
                    onClick {
                        onClick()
                        true
                    }
                }
                .padding(horizontal = if (primary) 12.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (primary) colors.onSecondaryContainer else colors.onSurfaceVariant
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val CHIP_CORNER = 16.dp
