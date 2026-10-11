package dev.keycorrect.feature.keyboard

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.keycorrect.core.layout.KeyModifier

/** How a terminal modifier key (Ctrl or Alt) is latched. */
internal enum class ModifierLatch {
    Off,

    /** Applies to the next key, then clears. */
    OneShot,

    /** Applies to every key until tapped again. */
    Locked,
}

/**
 * Ctrl and Alt of the terminal extra-keys row. A tap turns a modifier on for the next key
 * ([ModifierLatch.OneShot]); a second tap within [DOUBLE_TAP_MS] of the first locks it; a tap while
 * it is locked turns it off. A tap on a one-shot modifier later than that turns it off. Time comes
 * from [clock], so the state machine is testable. The states are observable, so the keys are drawn
 * active.
 */
internal class TerminalModifiers(private val clock: () -> Long) {
    var ctrl by mutableStateOf(ModifierLatch.Off)
        private set

    var alt by mutableStateOf(ModifierLatch.Off)
        private set

    private var lastCtrlTap = 0L
    private var lastAltTap = 0L

    /** The latch of [modifier]. */
    fun latch(modifier: KeyModifier): ModifierLatch =
        when (modifier) {
            KeyModifier.Ctrl -> ctrl
            KeyModifier.Alt -> alt
        }

    /** True while either modifier is on. */
    val active: Boolean
        get() = ctrl != ModifierLatch.Off || alt != ModifierLatch.Off

    /** The `KeyEvent` meta state of the modifiers that are on now (0 when none). */
    val metaState: Int
        get() = metaFor(ctrl, CTRL_META) or metaFor(alt, ALT_META)

    /** A tap on the [modifier] key. */
    fun onTap(modifier: KeyModifier) {
        val now = clock()
        when (modifier) {
            KeyModifier.Ctrl -> {
                ctrl = next(ctrl, now - lastCtrlTap)
                lastCtrlTap = now
            }
            KeyModifier.Alt -> {
                alt = next(alt, now - lastAltTap)
                lastAltTap = now
            }
        }
    }

    /** A key was sent with the modifiers: one-shot ones clear, locked ones stay. */
    fun consume() {
        if (ctrl == ModifierLatch.OneShot) ctrl = ModifierLatch.Off
        if (alt == ModifierLatch.OneShot) alt = ModifierLatch.Off
    }

    /** Turns everything off (a new field). */
    fun reset() {
        ctrl = ModifierLatch.Off
        alt = ModifierLatch.Off
        lastCtrlTap = 0L
        lastAltTap = 0L
    }

    private fun next(current: ModifierLatch, sinceLastTap: Long): ModifierLatch =
        when (current) {
            ModifierLatch.Off -> ModifierLatch.OneShot
            ModifierLatch.OneShot ->
                if (sinceLastTap <= DOUBLE_TAP_MS) ModifierLatch.Locked else ModifierLatch.Off
            ModifierLatch.Locked -> ModifierLatch.Off
        }

    private fun metaFor(latch: ModifierLatch, meta: Int) =
        if (latch == ModifierLatch.Off) 0 else meta

    companion object {
        /** Longest gap between two taps that still counts as a double tap. */
        const val DOUBLE_TAP_MS = 400L

        const val CTRL_META = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        const val ALT_META = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
    }
}

/**
 * The key code that types [text] on a US layout, with the shift it needs, for sending a character
 * as a key event while Ctrl or Alt is on; null for text with no single key (accents, emoji, words).
 */
internal fun keyCodeForText(text: String): TerminalKey? {
    if (text.length != 1) return null
    val c = text[0]
    return when (c) {
        in 'a'..'z' -> TerminalKey(KeyEvent.KEYCODE_A + (c - 'a'))
        in 'A'..'Z' -> TerminalKey(KeyEvent.KEYCODE_A + (c - 'A'), shift = true)
        in '0'..'9' -> TerminalKey(KeyEvent.KEYCODE_0 + (c - '0'))
        else -> PUNCTUATION[c]
    }
}

/** A key code and whether it needs shift to type the character. */
internal data class TerminalKey(val code: Int, val shift: Boolean = false)

private val PUNCTUATION: Map<Char, TerminalKey> =
    mapOf(
        ' ' to TerminalKey(KeyEvent.KEYCODE_SPACE),
        ',' to TerminalKey(KeyEvent.KEYCODE_COMMA),
        '.' to TerminalKey(KeyEvent.KEYCODE_PERIOD),
        '/' to TerminalKey(KeyEvent.KEYCODE_SLASH),
        '\\' to TerminalKey(KeyEvent.KEYCODE_BACKSLASH),
        ';' to TerminalKey(KeyEvent.KEYCODE_SEMICOLON),
        '\'' to TerminalKey(KeyEvent.KEYCODE_APOSTROPHE),
        '[' to TerminalKey(KeyEvent.KEYCODE_LEFT_BRACKET),
        ']' to TerminalKey(KeyEvent.KEYCODE_RIGHT_BRACKET),
        '-' to TerminalKey(KeyEvent.KEYCODE_MINUS),
        '=' to TerminalKey(KeyEvent.KEYCODE_EQUALS),
        '`' to TerminalKey(KeyEvent.KEYCODE_GRAVE),
        '@' to TerminalKey(KeyEvent.KEYCODE_AT),
        '+' to TerminalKey(KeyEvent.KEYCODE_PLUS),
        '*' to TerminalKey(KeyEvent.KEYCODE_STAR),
        '#' to TerminalKey(KeyEvent.KEYCODE_POUND),
    )
