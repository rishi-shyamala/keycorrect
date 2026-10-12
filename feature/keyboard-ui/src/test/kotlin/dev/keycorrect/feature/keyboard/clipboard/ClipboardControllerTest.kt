package dev.keycorrect.feature.keyboard.clipboard

import android.text.InputType
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.feature.keyboard.EditorIdentity
import dev.keycorrect.feature.keyboard.FakeBackend
import dev.keycorrect.feature.keyboard.KeyboardController
import dev.keycorrect.feature.keyboard.KeyboardPanel
import dev.keycorrect.feature.keyboard.RecordingSink
import dev.keycorrect.feature.keyboard.ScriptedPredictor
import dev.keycorrect.feature.keyboard.SimEditor
import dev.keycorrect.feature.keyboard.clipboard.FakeClipboardEnvironment.Companion.clip
import dev.keycorrect.feature.keyboard.testEnv
import org.junit.Test

/** The clipboard through [KeyboardController] against [SimEditor] (docs/PHASE4.md, PR C). */
class ClipboardControllerTest {
    private val editor = SimEditor()
    private val backend = FakeBackend()
    private val predictor = ScriptedPredictor()
    private val sink = RecordingSink()
    private val clipboard = FakeClipboardEnvironment()
    private val controller: KeyboardController

    init {
        backend.predictor = predictor
        backend.known += listOf("hello", "world")
        controller =
            KeyboardController(
                editor,
                KeyboardComposer(BuiltInLayouts.repository()),
                testEnv(backend),
                learning = sink,
                clipboard = clipboard,
            )
        editor.onOperation = { editor.echo(controller) }
    }

    private val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

    private fun start(text: String = "", inputType: Int = InputType.TYPE_CLASS_TEXT) {
        editor.setText(text)
        controller.onStartInput(
            FieldProfile.from(inputType, 0),
            KeyboardSettings(),
            identity = EditorIdentity(1, "pkg", inputType, 0, null),
            initialSelStart = editor.cursor,
            initialSelEnd = editor.cursor,
        )
    }

    private fun type(text: String) = text.forEach { controller.onKey(KeyAction.Text("$it")) }

    // ------------------------------------------------------------------ the long press

    @Test
    fun aLongPressOnTheEmojiKeyOpensTheClipboard() {
        start()
        controller.onKeyLongPress(KeyAction.Emoji)
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Clipboard)
    }

    @Test
    fun aShortTapStillOpensEmoji() {
        start()
        controller.onKey(KeyAction.Emoji)
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Emoji)
    }

    @Test
    fun aLongPressOnOtherKeysDoesNothing() {
        start()
        controller.onKeyLongPress(KeyAction.Space)
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Keys)
    }

    @Test
    fun theClipboardIsNotOpenedWhenDisabled() {
        clipboard.enabled = false
        start()
        assertThat(controller.clipboardAvailable).isFalse()
        controller.onKeyLongPress(KeyAction.Emoji)
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Keys)
    }

    @Test
    fun theClipboardIsNotOpenedInASensitiveField() {
        start(inputType = password)
        controller.onKeyLongPress(KeyAction.Emoji)
        controller.showPanel(KeyboardPanel.Clipboard)
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Keys)
    }

    @Test
    fun leavingThePanelDropsItsInlineState() {
        start()
        controller.openClipboard()
        controller.clipboardPanel.openMenu(7)
        controller.clipboardPanel.askToClear()
        assertThat(controller.clipboardPanel.menuFor).isNull() // asking closes the menu
        controller.showPanel(KeyboardPanel.Keys)
        assertThat(controller.clipboardPanel.confirmingClear).isFalse()
    }

    // ------------------------------------------------------------------ pasting

    @Test
    fun pickingAClipCommitsItsTextAndReturnsToTheKeys() {
        clipboard.texts[1] = "pasted text"
        start("hi ")
        controller.openClipboard()
        controller.onClipPicked(1)
        assertThat(editor.text.toString()).isEqualTo("hi pasted text")
        assertThat(editor.cursor).isEqualTo(editor.text.length)
        assertThat(clipboard.pasted).containsExactly(1L)
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Keys)
    }

    @Test
    fun pickingEndsTheComposingWordWithoutLearningAnything() {
        clipboard.texts[1] = "secret-ish "
        start()
        type("helo")
        assertThat(editor.composing).isEqualTo("helo")
        val before = sink.events.toList()
        val sentenceBefore = sink.sentence.toList()
        controller.onClipPicked(1)
        assertThat(editor.composing).isEmpty()
        assertThat(editor.text.toString()).isEqualTo("helosecret-ish ")
        // Typing goes on as a new word; the tracker saw the paste as the keyboard's own edit.
        type("a")
        assertThat(editor.composing).isEqualTo("a")
        assertThat(sink.events).isEqualTo(before)
        assertThat(sink.sentence).isEqualTo(sentenceBefore)
    }

    @Test
    fun aMissingClipPastesNothing() {
        start("x")
        controller.onClipPicked(99)
        assertThat(editor.text.toString()).isEqualTo("x")
    }

    @Test
    fun pastingIntoASensitiveFieldWorksAndLearnsNothing() {
        clipboard.texts[1] = "123456"
        start(inputType = password)
        controller.onClipPicked(1)
        assertThat(editor.text.toString()).isEqualTo("123456")
        assertThat(sink.isSilent).isTrue()
    }

    // ------------------------------------------------------------------ the strip chip

    @Test
    fun theChipAppearsOnlyWhenTheStripIsEmptyAndAClipIsFresh() {
        start()
        assertThat(controller.stripClip).isNull() // nothing fresh
        clipboard.fresh = clip(1, "hello there")
        assertThat(controller.stripClip?.id).isEqualTo(1L)
        clipboard.enabled = false
        assertThat(controller.stripClip).isNull()
    }

    @Test
    fun theChipHidesWhileAWordIsBeingComposed() {
        clipboard.fresh = clip(1, "copied")
        start()
        type("h")
        assertThat(controller.stripClip).isNull()
        controller.onKey(KeyAction.Space)
        assertThat(controller.stripClip?.id).isEqualTo(1L)
    }

    @Test
    fun theChipHidesWhileASelectionStripShowsOrAPanelIsOpen() {
        clipboard.fresh = clip(1, "copied")
        start("some text")
        controller.onSelectionChanged(0, 4)
        assertThat(controller.stripClip).isNull()
        controller.onSelectionChanged(9, 9)
        assertThat(controller.stripClip).isNotNull()
        controller.showPanel(KeyboardPanel.Emoji)
        assertThat(controller.stripClip).isNull()
    }

    @Test
    fun dismissingTheChipHidesItForThatClipOnly() {
        clipboard.fresh = clip(1, "one")
        start()
        controller.dismissClipChip(1)
        assertThat(controller.stripClip).isNull()
        clipboard.fresh = clip(2, "two")
        assertThat(controller.stripClip?.id).isEqualTo(2L)
    }

    @Test
    fun pastingFromTheChipHidesIt() {
        clipboard.fresh = clip(1, "one")
        clipboard.texts[1] = "one"
        start()
        controller.onClipPicked(1)
        assertThat(controller.stripClip).isNull()
        assertThat(editor.text.toString()).isEqualTo("one")
    }

    @Test
    fun theChipIsOfferedAndPastesInAPasswordField() {
        clipboard.fresh = clip(1, "hunter2")
        clipboard.texts[1] = "hunter2"
        start(inputType = password)
        assertThat(controller.stripClip?.id).isEqualTo(1L)
        controller.onClipPicked(1)
        assertThat(editor.text.toString()).isEqualTo("hunter2")
    }

    // ------------------------------------------------------------------ actions

    @Test
    fun pinDeleteCleanAndClearCallThrough() {
        start()
        controller.openClipboard()
        controller.pinClip(1, true)
        controller.pinClip(1, false)
        controller.clipboardPanel.openMenu(2)
        controller.deleteClip(2)
        assertThat(controller.clipboardPanel.menuFor).isNull()
        controller.cleanClipUrl(3)
        controller.clipboardPanel.askToClear()
        assertThat(controller.clipboardPanel.confirmingClear).isTrue()
        controller.clearClips()
        assertThat(clipboard.pins).containsExactly(1L to true, 1L to false).inOrder()
        assertThat(clipboard.deleted).containsExactly(2L)
        assertThat(clipboard.cleaned).containsExactly(3L)
        assertThat(clipboard.clears).isEqualTo(1)
        assertThat(controller.clipboardPanel.confirmingClear).isFalse()
    }

    @Test
    fun cancellingTheClearConfirmationClearsNothing() {
        start()
        controller.clipboardPanel.askToClear()
        controller.clipboardPanel.cancelClear()
        assertThat(clipboard.clears).isEqualTo(0)
        assertThat(controller.clipboardPanel.confirmingClear).isFalse()
    }
}
