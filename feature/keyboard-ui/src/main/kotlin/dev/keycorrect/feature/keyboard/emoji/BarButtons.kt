package dev.keycorrect.feature.keyboard.emoji

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun BarButton(
    text: String,
    description: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    BarBox(description, modifier, highlighted, onClick) {
        Text(
            text,
            color = if (highlighted) colors.onPrimary else colors.onSurface,
            fontSize = BAR_TEXT_SIZE.sp,
            maxLines = 1,
        )
    }
}

/** A bar button showing a vector [icon] (tinted like [BarButton]'s text) instead of text. */
@Composable
internal fun IconBarButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    BarBox(description, modifier, highlighted = false, onClick) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(BAR_ICON_SIZE),
        )
    }
}

@Composable
private fun BarBox(
    description: String,
    modifier: Modifier,
    highlighted: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .padding(2.dp)
                .clip(RoundedCornerShape(BarCorner))
                .background(if (highlighted) colors.primary else colors.surfaceContainerHighest)
                .clickable(role = Role.Button, onClick = onClick)
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Button
                    onClick {
                        onClick()
                        true
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private val BAR_ICON_SIZE = 24.dp
