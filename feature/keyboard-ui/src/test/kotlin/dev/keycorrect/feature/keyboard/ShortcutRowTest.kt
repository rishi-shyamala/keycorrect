package dev.keycorrect.feature.keyboard

import android.text.InputType
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.feature.keyboard.clipboard.FakeClipboardEnvironment
import dev.keycorrect.feature.keyboard.clipboard.FakeClipboardEnvironment.Companion.clip
import org.junit.Test

/** The shortcut row's visibility rule and actions (docs/PHASE4.md, feedback round 1, #5). */
class ShortcutRowTest {
    private val editor = SimEditor()
    private val backend = FakeBackend()
    private val clipboard = FakeClipboardEnvironment()
    private val controller: KeyboardController

    init {
        backend.predictor = ScriptedPredictor()
        controller =
            KeyboardController(
                editor,
                KeyboardComposer(BuiltInLayouts.repository()),
                testEnv(backend),
                clipboard = clipboard,
            )
        editor.onOperation = { editor.echo(controller) }
    }

    private val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

    private fun start(inputType: Int = InputType.TYPE_CLASS_TEXT) {
        editor.setText("")
        controller.onStartInput(
            FieldProfile.from(inputType, 0),
            KeyboardSettings(),
            identity = EditorIdentity(1, "pkg", inputType, 0, null),
            initialSelStart = editor.cursor,
            initialSelEnd = editor.cursor,
        )
    }

    // ------------------------------------------------------------------ the rule

    @Test
    fun predictionsOwnTheAreaWhileTypingOrSelecting() {
        assertThat(
                stripContent(
                    stripEmpty = false,
                    composing = false,
                    selectionStrip = false,
                    hasClip = false,
                )
            )
            .isEqualTo(StripContent.Predictions)
        assertThat(
                stripContent(
                    stripEmpty = true,
                    composing = true,
                    selectionStrip = false,
                    hasClip = false,
                )
            )
            .isEqualTo(StripContent.Predictions)
        assertThat(
                stripContent(
                    stripEmpty = true,
                    composing = false,
                    selectionStrip = true,
                    hasClip = false,
                )
            )
            .isEqualTo(StripContent.Predictions)
        // Even with a fresh clip, typing wins.
        assertThat(
                stripContent(
                    stripEmpty = false,
                    composing = true,
                    selectionStrip = false,
                    hasClip = true,
                )
            )
            .isEqualTo(StripContent.Predictions)
    }

    @Test
    fun theClipChipOutranksTheShortcutsAndYieldsToThemWhenGone() {
        assertThat(
                stripContent(
                    stripEmpty = true,
                    composing = false,
                    selectionStrip = false,
                    hasClip = true,
                )
            )
            .isEqualTo(StripContent.ClipChip)
        assertThat(
                stripContent(
                    stripEmpty = true,
                    composing = false,
                    selectionStrip = false,
                    hasClip = false,
                )
            )
            .isEqualTo(StripContent.Shortcuts)
    }

    @Test
    fun sensitiveFieldsLoseEmojiAndClipboard() {
        assertThat(shortcutsFor(sensitive = false, clipboardAvailable = true))
            .containsExactly(
                Shortcut.Editing,
                Shortcut.Emoji,
                Shortcut.Clipboard,
                Shortcut.Settings,
            )
            .inOrder()
        assertThat(shortcutsFor(sensitive = true, clipboardAvailable = true))
            .containsExactly(Shortcut.Editing, Shortcut.Settings)
            .inOrder()
    }

    @Test
    fun clipboardIsHiddenWhenUnavailable() {
        assertThat(shortcutsFor(sensitive = false, clipboardAvailable = false))
            .containsExactly(Shortcut.Editing, Shortcut.Emoji, Shortcut.Settings)
            .inOrder()
    }

    // ------------------------------------------------------------------ on the controller

    @Test
    fun theRowShowsWhenIdleAndGivesWayToTyping() {
        start()
        assertThat(controller.shortcutRowVisible).isTrue()
        controller.onKey(KeyAction.Text("h"))
        assertThat(controller.shortcutRowVisible).isFalse()
    }

    @Test
    fun aFreshClipTakesTheAreaUntilDismissed() {
        start()
        clipboard.fresh = clip(1, "copied")
        assertThat(controller.shortcutRowVisible).isFalse()
        controller.dismissClipChip(1)
        assertThat(controller.shortcutRowVisible).isTrue()
    }

    @Test
    fun theRowIsHiddenWhileAPanelIsOpen() {
        start()
        controller.onShortcut(Shortcut.Editing) {}
        assertThat(controller.shortcutRowVisible).isFalse()
    }

    @Test
    fun theControllerListsTheFieldsShortcuts() {
        start()
        assertThat(controller.shortcuts).contains(Shortcut.Clipboard)
        clipboard.enabled = false
        assertThat(controller.shortcuts).doesNotContain(Shortcut.Clipboard)
        clipboard.enabled = true
        start(password)
        assertThat(controller.shortcuts).containsExactly(Shortcut.Editing, Shortcut.Settings)
    }

    @Test
    fun theButtonsOpenTheirPanels() {
        start()
        controller.onShortcut(Shortcut.Editing) {}
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Editing)
        controller.showPanel(KeyboardPanel.Keys)
        controller.onShortcut(Shortcut.Emoji) {}
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Emoji)
        controller.showPanel(KeyboardPanel.Keys)
        controller.onShortcut(Shortcut.Clipboard) {}
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Clipboard)
    }

    @Test
    fun settingsCallsTheHostAndLeavesThePanelAlone() {
        start()
        var opened = 0
        controller.onShortcut(Shortcut.Settings) { opened++ }
        assertThat(opened).isEqualTo(1)
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Keys)
    }

    @Test
    fun emojiAndClipboardStayClosedInASensitiveField() {
        start(password)
        controller.onShortcut(Shortcut.Emoji) {}
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Keys)
        controller.onShortcut(Shortcut.Clipboard) {}
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Keys)
    }
}
