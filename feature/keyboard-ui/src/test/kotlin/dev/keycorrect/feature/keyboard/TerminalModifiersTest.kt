package dev.keycorrect.feature.keyboard

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.layout.KeyModifier
import org.junit.Test

class TerminalModifiersTest {
    private var now = 1_000L
    private val modifiers = TerminalModifiers { now }

    private fun tap(m: KeyModifier = KeyModifier.Ctrl, after: Long = 0) {
        now += after
        modifiers.onTap(m)
    }

    @Test
    fun startsOff() {
        assertThat(modifiers.ctrl).isEqualTo(ModifierLatch.Off)
        assertThat(modifiers.alt).isEqualTo(ModifierLatch.Off)
        assertThat(modifiers.active).isFalse()
        assertThat(modifiers.metaState).isEqualTo(0)
    }

    @Test
    fun tapIsOneShotAndConsumeClearsIt() {
        tap()
        assertThat(modifiers.ctrl).isEqualTo(ModifierLatch.OneShot)
        assertThat(modifiers.active).isTrue()
        modifiers.consume()
        assertThat(modifiers.ctrl).isEqualTo(ModifierLatch.Off)
    }

    @Test
    fun secondTapWithinTheWindowLocks() {
        tap()
        tap(after = TerminalModifiers.DOUBLE_TAP_MS)
        assertThat(modifiers.ctrl).isEqualTo(ModifierLatch.Locked)
        modifiers.consume()
        assertThat(modifiers.ctrl).isEqualTo(ModifierLatch.Locked)
    }

    @Test
    fun lateSecondTapTurnsOneShotOff() {
        tap()
        tap(after = TerminalModifiers.DOUBLE_TAP_MS + 1)
        assertThat(modifiers.ctrl).isEqualTo(ModifierLatch.Off)
    }

    @Test
    fun tapWhileLockedTurnsOff() {
        tap()
        tap(after = 100)
        tap(after = 100)
        assertThat(modifiers.ctrl).isEqualTo(ModifierLatch.Off)
        // And the next tap starts over, even if it comes right after.
        tap(after = 50)
        assertThat(modifiers.ctrl).isEqualTo(ModifierLatch.OneShot)
    }

    @Test
    fun aTapAfterTheOneShotWasUsedStartsANewOneShot() {
        tap()
        modifiers.consume()
        tap(after = 100)
        assertThat(modifiers.ctrl).isEqualTo(ModifierLatch.OneShot)
    }

    @Test
    fun ctrlAndAltAreIndependent() {
        tap(KeyModifier.Ctrl)
        tap(KeyModifier.Alt, after = 100)
        assertThat(modifiers.ctrl).isEqualTo(ModifierLatch.OneShot)
        assertThat(modifiers.alt).isEqualTo(ModifierLatch.OneShot)
        assertThat(modifiers.metaState)
            .isEqualTo(TerminalModifiers.CTRL_META or TerminalModifiers.ALT_META)
        tap(KeyModifier.Alt, after = 100) // second Alt tap locks Alt only
        modifiers.consume()
        assertThat(modifiers.ctrl).isEqualTo(ModifierLatch.Off)
        assertThat(modifiers.alt).isEqualTo(ModifierLatch.Locked)
        assertThat(modifiers.metaState).isEqualTo(TerminalModifiers.ALT_META)
    }

    @Test
    fun metaFlagsIncludeTheLeftVariant() {
        tap()
        assertThat(modifiers.metaState and KeyEvent.META_CTRL_ON).isNotEqualTo(0)
        assertThat(modifiers.metaState and KeyEvent.META_CTRL_LEFT_ON).isNotEqualTo(0)
        assertThat(modifiers.metaState and KeyEvent.META_ALT_ON).isEqualTo(0)
    }

    @Test
    fun resetTurnsEverythingOff() {
        tap()
        tap(after = 10)
        tap(KeyModifier.Alt, after = 10)
        modifiers.reset()
        assertThat(modifiers.active).isFalse()
        // The tap history is forgotten too: no accidental lock.
        tap(after = 10)
        assertThat(modifiers.ctrl).isEqualTo(ModifierLatch.OneShot)
    }

    @Test
    fun textMapsToKeyCodes() {
        assertThat(keyCodeForText("c")).isEqualTo(TerminalKey(KeyEvent.KEYCODE_C))
        assertThat(keyCodeForText("C")).isEqualTo(TerminalKey(KeyEvent.KEYCODE_C, shift = true))
        assertThat(keyCodeForText("7")).isEqualTo(TerminalKey(KeyEvent.KEYCODE_7))
        assertThat(keyCodeForText("[")).isEqualTo(TerminalKey(KeyEvent.KEYCODE_LEFT_BRACKET))
        assertThat(keyCodeForText("\\")).isEqualTo(TerminalKey(KeyEvent.KEYCODE_BACKSLASH))
        assertThat(keyCodeForText("é")).isNull()
        assertThat(keyCodeForText(".com")).isNull()
        assertThat(keyCodeForText("")).isNull()
    }
}
