package dev.keycorrect.feature.keyboard

import android.view.inputmethod.EditorInfo
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.input.EnterAction
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyCodes
import dev.keycorrect.core.layout.KeyModifier
import dev.keycorrect.core.layout.ResolvedKey
import org.junit.Test

class KeyDrawingTest {
    private val enter = ResolvedKey("↵", KeyAction.Enter, description = "Enter")
    private val shift = ResolvedKey("⇧", KeyAction.Shift, description = "Shift")
    private val letter = ResolvedKey("a", KeyAction.Text("a"))

    @Test
    fun enterIsSpokenAsItsLiveAction() {
        val cases =
            mapOf(
                EditorInfo.IME_ACTION_GO to "Go",
                EditorInfo.IME_ACTION_SEARCH to "Search",
                EditorInfo.IME_ACTION_SEND to "Send",
                EditorInfo.IME_ACTION_NEXT to "Next",
                EditorInfo.IME_ACTION_PREVIOUS to "Previous",
                EditorInfo.IME_ACTION_DONE to "Done",
            )
        for ((id, name) in cases) {
            val spoken = enterDescription(EnterAction.Editor(id), fallback = enter.description)
            assertThat(keyDescription(enter, null, spoken)).isEqualTo(name)
        }
    }

    @Test
    fun plainEnterKeepsTheStaticDescription() {
        assertThat(enterDescription(EnterAction.NewLine, "Enter")).isEqualTo("Enter")
        assertThat(enterDescription(EnterAction.Editor(EditorInfo.IME_ACTION_UNSPECIFIED), "Enter"))
            .isEqualTo("Enter")
        assertThat(keyDescription(enter, null)).isEqualTo("Enter")
    }

    @Test
    fun visibleLabelAbbreviatesPrevious() {
        assertThat(enterLabel(EnterAction.Editor(EditorInfo.IME_ACTION_PREVIOUS))).isEqualTo("Prev")
        assertThat(enterLabel(EnterAction.Editor(EditorInfo.IME_ACTION_SEND))).isEqualTo("Send")
        assertThat(enterLabel(EnterAction.NewLine)).isEqualTo("↵")
    }

    @Test
    fun shiftDescriptionFollowsState() {
        assertThat(keyDescription(shift, ShiftState.Off)).isEqualTo("Shift")
        assertThat(keyDescription(shift, ShiftState.Once)).isEqualTo("Shift, on")
        assertThat(keyDescription(shift, ShiftState.Auto)).isEqualTo("Shift, on")
        assertThat(keyDescription(shift, ShiftState.Locked)).isEqualTo("Caps lock on")
        assertThat(keyDescription(letter, null)).isEqualTo("a")
    }

    @Test
    fun keysShowTheirShiftedLabelUnderEffectiveShift() {
        val key = ResolvedKey("a", KeyAction.Text("a", "A"), shiftedLabel = "A")
        val plain = ResolvedKey("1", KeyAction.Text("1"))
        val held = KeyboardState().effectiveShift(shiftHeld = true)
        assertThat(keyLabel(key, held, "")).isEqualTo("A")
        assertThat(keyLabel(plain, held, "")).isEqualTo("1") // no shiftedLabel: unchanged
        assertThat(keyLabel(key, KeyboardState().effectiveShift(false), "")).isEqualTo("a")
        assertThat(keyDescription(shift, held)).isEqualTo("Shift, on")
    }

    @Test
    fun modifierKeysAreDescribedByTheirLatch() {
        val ctrl =
            ResolvedKey("Ctrl", KeyAction.Modifier(KeyModifier.Ctrl), description = "Control")
        assertThat(keyDescription(ctrl, null)).isEqualTo("Control")
        assertThat(keyDescription(ctrl, null, latch = ModifierLatch.OneShot))
            .isEqualTo("Control, on")
        assertThat(keyDescription(ctrl, null, latch = ModifierLatch.Locked))
            .isEqualTo("Control, locked")
    }

    @Test
    fun keyCodeKeysUseTheLayoutDescription() {
        val esc = ResolvedKey("Esc", KeyAction.KeyCode(KeyCodes.ESCAPE), description = "Escape")
        assertThat(keyDescription(esc, null)).isEqualTo("Escape")
    }

    @Test
    fun latchOfFollowsTheModifierKeyOnly() {
        var now = 0L
        val modifiers = TerminalModifiers { now }
        val ctrl = ResolvedKey("Ctrl", KeyAction.Modifier(KeyModifier.Ctrl))
        val alt = ResolvedKey("Alt", KeyAction.Modifier(KeyModifier.Alt))
        modifiers.onTap(KeyModifier.Ctrl)
        assertThat(latchOf(ctrl, modifiers)).isEqualTo(ModifierLatch.OneShot)
        assertThat(latchOf(alt, modifiers)).isEqualTo(ModifierLatch.Off)
        assertThat(latchOf(letter, modifiers)).isEqualTo(ModifierLatch.Off)
        assertThat(latchOf(ctrl, null)).isEqualTo(ModifierLatch.Off)
        now += 1
    }
}
