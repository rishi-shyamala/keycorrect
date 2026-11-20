package dev.keycorrect.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.keycorrect.R
import dev.keycorrect.core.settings.CursorSpeed
import dev.keycorrect.core.settings.KeyboardSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditingSettingsScreen(
    contentPadding: PaddingValues,
    settings: KeyboardSettings,
    onUpdate: ((KeyboardSettings) -> KeyboardSettings) -> Unit,
) {
    SettingsPage(contentPadding) {
        SectionHeader(stringResource(R.string.editing_section_space))
        SwitchRow(
            title = stringResource(R.string.editing_spacebar_cursor),
            supportingText = stringResource(R.string.editing_spacebar_cursor_summary),
            checked = settings.spacebarCursor,
            onCheckedChange = { v -> onUpdate { it.copy(spacebarCursor = v) } },
        )
        Text(
            stringResource(R.string.editing_cursor_speed),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        )
        val speeds = CursorSpeed.entries
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            speeds.forEachIndexed { index, speed ->
                SegmentedButton(
                    selected = settings.cursorSpeed == speed,
                    onClick = { onUpdate { it.copy(cursorSpeed = speed) } },
                    enabled = settings.spacebarCursor,
                    shape = SegmentedButtonDefaults.itemShape(index, speeds.size),
                ) {
                    Text(stringResource(speedLabel(speed)))
                }
            }
        }

        SectionHeader(stringResource(R.string.editing_section_backspace))
        SwitchRow(
            title = stringResource(R.string.editing_backspace_swipe),
            supportingText = stringResource(R.string.editing_backspace_swipe_summary),
            checked = settings.backspaceSwipe,
            onCheckedChange = { v -> onUpdate { it.copy(backspaceSwipe = v) } },
        )

        SectionHeader(stringResource(R.string.editing_section_gesture))
        SwitchRow(
            title = stringResource(R.string.editing_gesture_typing),
            supportingText = stringResource(R.string.editing_gesture_typing_summary),
            checked = settings.gestureTyping,
            onCheckedChange = { v -> onUpdate { it.copy(gestureTyping = v) } },
        )
        SwitchRow(
            title = stringResource(R.string.editing_gesture_trail),
            checked = settings.gestureTrail,
            onCheckedChange = { v -> onUpdate { it.copy(gestureTrail = v) } },
            enabled = settings.gestureTyping,
        )
        NeuralSwipeRow(settings, onUpdate)

        TryItSection()
    }
}

/**
 * The "Neural swipe" row. Without a usable swipe pack it stays visible but switched off and
 * disabled, with the reason, so the option can be found; with the pack it shows the credit
 * FUTO's licence requires.
 */
@Composable
private fun NeuralSwipeRow(
    settings: KeyboardSettings,
    onUpdate: ((KeyboardSettings) -> KeyboardSettings) -> Unit,
) {
    val pack = rememberSwipePackState()
    val installed = pack is SwipePackState.Installed
    SwitchRow(
        title = stringResource(R.string.editing_neural_swipe),
        supportingText =
            stringResource(
                when (pack) {
                    is SwipePackState.Installed,
                    SwipePackState.Checking -> R.string.editing_neural_swipe_summary
                    SwipePackState.NotInstalled -> R.string.editing_neural_swipe_not_installed
                    SwipePackState.Unusable -> R.string.editing_neural_swipe_unusable
                }
            ),
        checked = installed && settings.neuralSwipe,
        onCheckedChange = { v -> onUpdate { it.copy(neuralSwipe = v) } },
        enabled = installed && settings.gestureTyping,
    )
    if (installed) {
        Text(
            stringResource(R.string.editing_neural_swipe_credit),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

private fun speedLabel(speed: CursorSpeed): Int =
    when (speed) {
        CursorSpeed.Slow -> R.string.editing_speed_slow
        CursorSpeed.Normal -> R.string.editing_speed_normal
        CursorSpeed.Fast -> R.string.editing_speed_fast
    }
