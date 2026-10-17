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

/** The strip tools: which show, when the strip is busy, and what they do. */
class StripToolTest {
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

    // ------------------------------------------------------------------ the pure rules

    @Test
    fun theStripIsBusyWithAnythingToShow() {
        assertThat(
                stripBusy(
                    stripEmpty = true,
                    composing = false,
                    selectionStrip = false,
                    hasClip = false,
                )
            )
            .isFalse()
        assertThat(
                stripBusy(
                    stripEmpty = false,
                    composing = false,
                    selectionStrip = false,
                    hasClip = false,
                )
            )
            .isTrue()
        assertThat(
                stripBusy(
                    stripEmpty = true,
                    composing = true,
                    selectionStrip = false,
                    hasClip = false,
                )
            )
            .isTrue()
        assertThat(
                stripBusy(
                    stripEmpty = true,
                    composing = false,
                    selectionStrip = true,
                    hasClip = false,
                )
            )
            .isTrue()
        assertThat(
                stripBusy(
                    stripEmpty = true,
                    composing = false,
                    selectionStrip = false,
                    hasClip = true,
                )
            )
            .isTrue()
    }

    @Test
    fun sensitiveFieldsLoseEmojiAndClipboard() {
        assertThat(toolsFor(sensitive = false, clipboardAvailable = true))
            .containsExactly(
                StripTool.Edit,
                StripTool.Emoji,
                StripTool.Clipboard,
                StripTool.Settings,
            )
            .inOrder()
        assertThat(toolsFor(sensitive = true, clipboardAvailable = true))
            .containsExactly(StripTool.Edit, StripTool.Settings)
            .inOrder()
    }

    @Test
    fun clipboardIsHiddenWhenUnavailable() {
        assertThat(toolsFor(sensitive = false, clipboardAvailable = false))
            .containsExactly(StripTool.Edit, StripTool.Emoji, StripTool.Settings)
            .inOrder()
    }

    @Test
    fun editAndEmojiGoLeftClipboardAndSettingsRight() {
        val tools = toolsFor(sensitive = false, clipboardAvailable = true)
        assertThat(leftToolCount(tools)).isEqualTo(2)
        assertThat(leftToolCount(toolsFor(sensitive = true, clipboardAvailable = true)))
            .isEqualTo(1)
    }

    @Test
    fun toolsInterpolateFromEvenSlotsToTheEdges() {
        val slots = ToolSlots(count = 4, leftCount = 2, totalWidth = 400, edgeWidth = 32)
        // Idle: four equal 100 px slots.
        assertThat(slots.width(0f)).isEqualTo(100)
        assertThat((0..3).map { slots.x(it, 0f) }).containsExactly(0, 100, 200, 300).inOrder()
        // Busy: 32 px buttons, two flush left and two flush right.
        assertThat(slots.width(1f)).isEqualTo(32)
        assertThat((0..3).map { slots.x(it, 1f) }).containsExactly(0, 32, 336, 368).inOrder()
        // Halfway is the midpoint.
        assertThat(slots.width(0.5f)).isEqualTo(66)
        assertThat(slots.x(3, 0.5f)).isEqualTo(334)
        // One left tool, one right (sensitive field).
        val two = ToolSlots(count = 2, leftCount = 1, totalWidth = 400, edgeWidth = 32)
        assertThat((0..1).map { two.x(it, 1f) }).containsExactly(0, 368).inOrder()
    }

    // ------------------------------------------------------------------ on the controller

    @Test
    fun theStripIsIdleUntilTyping() {
        start()
        assertThat(controller.stripBusy).isFalse()
        controller.onKey(KeyAction.Text("h"))
        assertThat(controller.stripBusy).isTrue()
    }

    @Test
    fun aFreshClipMakesTheStripBusyUntilDismissed() {
        start()
        clipboard.fresh = clip(1, "copied")
        assertThat(controller.stripBusy).isTrue()
        controller.dismissClipChip(1)
        assertThat(controller.stripBusy).isFalse()
    }

    @Test
    fun theToolsShowOnTheKeysAndEditingPanelsOnly() {
        start()
        assertThat(controller.toolsVisible).isTrue()
        controller.onTool(StripTool.Emoji) {}
        assertThat(controller.toolsVisible).isFalse()
    }

    @Test
    fun theControllerListsTheFieldsTools() {
        start()
        assertThat(controller.tools).contains(StripTool.Clipboard)
        clipboard.enabled = false
        assertThat(controller.tools).doesNotContain(StripTool.Clipboard)
        clipboard.enabled = true
        start(password)
        assertThat(controller.tools).containsExactly(StripTool.Edit, StripTool.Settings).inOrder()
    }

    @Test
    fun theEditToolTogglesTheEditingPanel() {
        start()
        controller.onTool(StripTool.Edit) {}
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Editing)
        controller.onTool(StripTool.Edit) {}
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Keys)
    }

    @Test
    fun theButtonsOpenTheirPanels() {
        start()
        controller.onTool(StripTool.Emoji) {}
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Emoji)
        controller.showPanel(KeyboardPanel.Keys)
        controller.onTool(StripTool.Clipboard) {}
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Clipboard)
    }

    @Test
    fun settingsCallsTheHostAndLeavesThePanelAlone() {
        start()
        var opened = 0
        controller.onTool(StripTool.Settings) { opened++ }
        assertThat(opened).isEqualTo(1)
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Keys)
    }

    @Test
    fun emojiAndClipboardStayClosedInASensitiveField() {
        start(password)
        controller.onTool(StripTool.Emoji) {}
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Keys)
        controller.onTool(StripTool.Clipboard) {}
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Keys)
    }
}
