package dev.keycorrect.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keycorrect.AppContainer
import dev.keycorrect.R
import dev.keycorrect.core.settings.KeyboardSettings
import kotlinx.coroutines.launch

/** Root of the settings UI: top bar, back stack navigation and the current screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsApp(
    container: AppContainer,
    status: ImeStatus,
    onOpenKeyboardSettings: () -> Unit,
    onChooseKeyboard: () -> Unit,
) {
    val backStack =
        rememberSaveable(
            saver =
                listSaver<SnapshotStateList<SettingsDestination>, String>(
                    save = { stack -> stack.map { it.route } },
                    restore = { routes ->
                        routes.map(SettingsDestination::fromRoute).toMutableStateList()
                    },
                )
        ) {
            mutableStateListOf<SettingsDestination>(SettingsDestination.Home)
        }
    val current = backStack.last()
    val canGoBack = backStack.size > 1
    BackHandler(enabled = canGoBack) { backStack.removeAt(backStack.lastIndex) }

    val repository = container.settingsRepository
    val writeScope = container.applicationScope
    val settings by
        repository.settings.collectAsStateWithLifecycle(initialValue = KeyboardSettings())
    val onUpdate: ((KeyboardSettings) -> KeyboardSettings) -> Unit = { transform ->
        writeScope.launch { repository.update(transform) }
    }
    val navigate: (SettingsDestination) -> Unit = { backStack.add(it) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(current.titleRes)) },
                navigationIcon = {
                    if (canGoBack) {
                        val glyph =
                            if (LocalLayoutDirection.current == LayoutDirection.Rtl) "→" else "←"
                        val description = stringResource(R.string.navigate_up)
                        IconButton(
                            onClick = { backStack.removeAt(backStack.lastIndex) },
                            modifier = Modifier.semantics { contentDescription = description },
                        ) {
                            Text(
                                glyph,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.clearAndSetSemantics {},
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Crossfade(targetState = current, label = "settings-destination") { destination ->
            when (destination) {
                SettingsDestination.Home -> HomeScreen(padding, status, onNavigate = navigate)
                SettingsDestination.Setup ->
                    SetupScreen(padding, status, onOpenKeyboardSettings, onChooseKeyboard)
                SettingsDestination.Keyboard -> KeyboardSettingsScreen(padding, settings, onUpdate)
                SettingsDestination.Editing -> EditingSettingsScreen(padding, settings, onUpdate)
                SettingsDestination.Emoji -> EmojiSettingsScreen(padding, settings, onUpdate)
                SettingsDestination.Voice -> VoiceSettingsScreen(padding, settings, onUpdate)
                SettingsDestination.LanguageModel ->
                    LanguageModelScreen(padding, container, settings, onUpdate)
                SettingsDestination.Clipboard ->
                    ClipboardSettingsScreen(padding, container, settings, onUpdate)
                SettingsDestination.TextCorrection ->
                    TextCorrectionScreen(padding, settings, onUpdate)
                SettingsDestination.PersonalDictionary ->
                    PersonalDictionaryScreen(padding, container)
                SettingsDestination.Shortcuts -> ShortcutsScreen(padding, container)
                SettingsDestination.Learning ->
                    LearningScreen(padding, container, settings, onUpdate, onNavigate = navigate)
                SettingsDestination.LearnedWords -> LearnedWordsScreen(padding, container)
                SettingsDestination.LearnedCorrections ->
                    LearnedCorrectionsScreen(padding, container)
                SettingsDestination.NeverCorrected -> NeverCorrectedScreen(padding, container)
                SettingsDestination.NeverSuggested -> NeverSuggestedScreen(padding, container)
                SettingsDestination.Feedback -> FeedbackSettingsScreen(padding, settings, onUpdate)
                SettingsDestination.Backup -> BackupScreen(padding, container)
                SettingsDestination.Advanced -> AdvancedScreen(padding, settings, onUpdate)
                SettingsDestination.About -> AboutScreen(padding, onNavigate = navigate)
                SettingsDestination.Licenses -> LicensesScreen(padding)
                SettingsDestination.AutofillTest -> AutofillTestScreen(padding)
            }
        }
    }
}
