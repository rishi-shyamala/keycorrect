package dev.keycorrect.feature.keyboard.emoji

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.keycorrect.feature.keyboard.KeyboardController
import dev.keycorrect.feature.keyboard.R

/**
 * The emoji search, in the strip's place above the letter keys: a close button, the query, and the
 * matching emoji (tap to insert; the search stays open). The letter keys edit the query, not the
 * app's text; Enter or the close button return to the emoji grid.
 */
@Composable
internal fun EmojiSearchRow(controller: KeyboardController, onPress: () -> Unit) {
    val search = controller.emojiSearch
    val catalog = controller.emoji.catalog
    val query = search.query
    val results = remember(query, catalog) { search.results(catalog) }
    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_SPACING),
    ) {
        CloseButton {
            onPress()
            controller.closeEmojiSearch()
        }
        QueryBox(query)
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            if (results.isEmpty() && query.isNotBlank()) {
                Text(
                    stringResource(R.string.emoji_search_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            } else {
                LazyRow(verticalAlignment = Alignment.CenterVertically) {
                    items(results, key = { it.value }) { emoji ->
                        ResultCell(emoji.name, controller.emojiValue(emoji)) {
                            onPress()
                            controller.onEmojiTap(emoji)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CloseButton(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val description = stringResource(R.string.emoji_search_close_description)
    Box(
        modifier =
            Modifier.fillMaxHeight()
                .width(CLOSE_WIDTH)
                .clip(RoundedCornerShape(CORNER))
                .background(colors.surfaceContainerHighest)
                .clickable(role = Role.Button, onClick = onClick)
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Button
                },
        contentAlignment = Alignment.Center,
    ) {
        Text("\u2715", color = colors.onSurface, fontSize = CLOSE_SIZE.sp)
    }
}

@Composable
private fun QueryBox(query: String) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier =
            Modifier.fillMaxHeight()
                .width(QUERY_WIDTH)
                .clip(RoundedCornerShape(CORNER))
                .background(colors.surfaceContainerLowest)
                .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = query.ifEmpty { stringResource(R.string.emoji_search_hint) },
            color = if (query.isEmpty()) colors.onSurfaceVariant else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ResultCell(name: String, value: String, onClick: () -> Unit) {
    Box(
        modifier =
            Modifier.width(RESULT_WIDTH)
                .fillMaxHeight()
                .clickable(role = Role.Button, onClick = onClick)
                .clearAndSetSemantics {
                    contentDescription = name
                    role = Role.Button
                },
        contentAlignment = Alignment.Center,
    ) {
        Text(value, fontSize = RESULT_SIZE.sp, maxLines = 1)
    }
}

private val ROW_SPACING = 4.dp
private val CLOSE_WIDTH = 40.dp
private val QUERY_WIDTH = 120.dp
private val RESULT_WIDTH = 44.dp
private val CORNER = 8.dp
private const val CLOSE_SIZE = 16
private const val RESULT_SIZE = 26
