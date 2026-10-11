package dev.keycorrect.feature.keyboard

import android.text.InputType
import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyCodes
import dev.keycorrect.core.layout.KeyModifier
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.layout.KeyboardSet
import dev.keycorrect.core.settings.KeyboardSettings
import org.junit.Test

class TerminalRowControllerTest {
    private val editor = SimEditor()
    private var now = 10_000L
    private val controller =
        KeyboardController(editor, KeyboardComposer(BuiltInLayouts.repository()), clock = { now })

    private val ctrl = KeyAction.Modifier(KeyModifier.Ctrl)
    private val alt = KeyAction.Modifier(KeyModifier.Alt)
    private val ctrlMeta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    private val altMeta = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON

    private fun startTerminal(fieldId: Int = 1) =
        controller.onStartInput(
            FieldProfile.from(InputType.TYPE_NULL, 0),
            KeyboardSettings(),
            identity = EditorIdentity(fieldId, "term.app", InputType.TYPE_NULL, 0, null),
        )

    private fun startText() =
        controller.onStartInput(
            FieldProfile.from(InputType.TYPE_CLASS_TEXT, 0),
            KeyboardSettings(),
            identity = EditorIdentity(2, "app", InputType.TYPE_CLASS_TEXT, 0, null),
        )

    private fun text(s: String) = controller.onKey(KeyAction.Text(s))

    private fun labels() = controller.layer.rows.flatMap { r -> r.keys.map { it.label } }

    @Test
    fun terminalFieldsGetTheRowOnEveryLayerAndNoHeightJump() {
        startText()
        val plainUnits = controller.heightUnits
        assertThat(labels()).doesNotContain("Esc")
        startTerminal()
        assertThat(labels()).contains("Esc")
        val units = controller.heightUnits
        assertThat(units).isGreaterThan(plainUnits)
        controller.onKey(KeyAction.SwitchLayer(KeyboardSet.SYMBOLS))
        assertThat(labels()).contains("Esc")
        assertThat(controller.layer.rows.sumOf { it.height.toDouble() }.toFloat())
            .isLessThan(units + 1e-3f)
        assertThat(controller.heightUnits).isEqualTo(units)
    }

    @Test
    fun ctrlThenLetterSendsTheKeyWithCtrlMetaAndClears() {
        startTerminal()
        controller.onKey(ctrl)
        assertThat(controller.terminalModifiers.ctrl).isEqualTo(ModifierLatch.OneShot)
        text("c")
        assertThat(editor.keys).containsExactly(KeyEvent.KEYCODE_C to ctrlMeta)
        assertThat(controller.terminalModifiers.ctrl).isEqualTo(ModifierLatch.Off)
        assertThat(editor.text.toString()).isEmpty()
        text("c") // modifier is gone: typed as text
        assertThat(editor.keys).hasSize(1)
        assertThat(editor.text.toString()).isEqualTo("c")
    }

    @Test
    fun doubleTapLocksAndStaysUntilTappedAgain() {
        startTerminal()
        controller.onKey(ctrl)
        now += 200
        controller.onKey(ctrl)
        assertThat(controller.terminalModifiers.ctrl).isEqualTo(ModifierLatch.Locked)
        text("a")
        text("d")
        assertThat(editor.keys)
            .containsExactly(KeyEvent.KEYCODE_A to ctrlMeta, KeyEvent.KEYCODE_D to ctrlMeta)
            .inOrder()
        assertThat(controller.terminalModifiers.ctrl).isEqualTo(ModifierLatch.Locked)
        controller.onKey(ctrl)
        assertThat(controller.terminalModifiers.ctrl).isEqualTo(ModifierLatch.Off)
    }

    @Test
    fun ctrlAndAltCombine() {
        startTerminal()
        controller.onKey(ctrl)
        controller.onKey(alt)
        text("x")
        assertThat(editor.keys).containsExactly(KeyEvent.KEYCODE_X to (ctrlMeta or altMeta))
    }

    @Test
    fun escapeAndArrowsSendPlainKeysAndTakeTheModifier() {
        startTerminal()
        controller.onKey(KeyAction.KeyCode(KeyCodes.ESCAPE))
        assertThat(editor.keys).containsExactly(KeyEvent.KEYCODE_ESCAPE to 0)
        controller.onKey(ctrl)
        controller.onKey(KeyAction.KeyCode(KeyCodes.DPAD_LEFT))
        assertThat(editor.keys.last()).isEqualTo(KeyEvent.KEYCODE_DPAD_LEFT to ctrlMeta)
        assertThat(controller.terminalModifiers.ctrl).isEqualTo(ModifierLatch.Off)
    }

    @Test
    fun spaceEnterAndBackspaceTakeTheModifier() {
        startTerminal()
        controller.onKey(ctrl)
        controller.onKey(KeyAction.Space)
        controller.onKey(alt)
        controller.onKey(KeyAction.Enter)
        controller.onKey(ctrl)
        controller.onKey(KeyAction.Backspace)
        assertThat(editor.keys)
            .containsExactly(
                KeyEvent.KEYCODE_SPACE to ctrlMeta,
                KeyEvent.KEYCODE_ENTER to altMeta,
                KeyEvent.KEYCODE_DEL to ctrlMeta,
            )
            .inOrder()
        assertThat(editor.enters).isEqualTo(0)
    }

    @Test
    fun shiftedLetterKeepsShiftInTheMeta() {
        startTerminal()
        val shiftMeta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        controller.onKey(KeyAction.Shift)
        controller.onKey(ctrl)
        controller.onKey(KeyAction.Text("c", "C"))
        assertThat(editor.keys.last()).isEqualTo(KeyEvent.KEYCODE_C to (ctrlMeta or shiftMeta))
    }

    @Test
    fun textWithoutAKeyCodeIsTypedAndEndsOneShot() {
        startTerminal()
        controller.onKey(ctrl)
        text("é")
        assertThat(editor.keys).isEmpty()
        assertThat(editor.text.toString()).isEqualTo("é")
        assertThat(controller.terminalModifiers.ctrl).isEqualTo(ModifierLatch.Off)
    }

    @Test
    fun newFieldClearsModifiers() {
        startTerminal(fieldId = 1)
        controller.onKey(ctrl)
        now += 100
        controller.onKey(ctrl)
        startTerminal(fieldId = 2)
        assertThat(controller.terminalModifiers.active).isFalse()
    }

    @Test
    fun keyCodeKeysOutsideTerminalsSendPlainEvents() {
        startText()
        controller.onKey(KeyAction.KeyCode(KeyCodes.TAB))
        assertThat(editor.keys).containsExactly(KeyEvent.KEYCODE_TAB to 0)
    }

    @Test
    fun modifiersDoNothingToTextOutsideTerminals() {
        startText()
        controller.onKey(ctrl)
        text("c")
        assertThat(editor.keys).isEmpty()
        assertThat(editor.text.toString()).isEqualTo("c")
    }
}
