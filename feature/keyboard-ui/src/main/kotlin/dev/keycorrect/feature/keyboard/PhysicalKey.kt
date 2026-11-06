package dev.keycorrect.feature.keyboard

import android.view.KeyEvent

/** What a key of a physical keyboard means to the prediction pipeline; see [physicalKeyFor]. */
sealed interface PhysicalKey {
    /** A printable character, already cased by the keyboard's shift and caps lock. */
    data class Text(val text: String) : PhysicalKey

    data object Space : PhysicalKey

    data object Backspace : PhysicalKey

    /** Enter: finishes the word, then the app gets the key itself. */
    data object Enter : PhysicalKey

    /**
     * A shortcut or navigation key (Ctrl/Alt/Meta combinations, arrows, Tab, Esc, function keys,
     * Delete): the word in progress ends as typed, then the app gets the key untouched.
     */
    data object Shortcut : PhysicalKey

    /** A modifier or lock key on its own: changes nothing, so the word goes on. */
    data object Ignore : PhysicalKey
}

private const val COMBINING_ACCENT = Int.MIN_VALUE // KeyCharacterMap.COMBINING_ACCENT: a dead key
private const val FIRST_PRINTABLE = 0x20
private const val DELETE_CHAR = 0x7f

private val MODIFIER_KEYS =
    setOf(
        KeyEvent.KEYCODE_SHIFT_LEFT,
        KeyEvent.KEYCODE_SHIFT_RIGHT,
        KeyEvent.KEYCODE_CTRL_LEFT,
        KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_ALT_LEFT,
        KeyEvent.KEYCODE_ALT_RIGHT,
        KeyEvent.KEYCODE_META_LEFT,
        KeyEvent.KEYCODE_META_RIGHT,
        KeyEvent.KEYCODE_CAPS_LOCK,
        KeyEvent.KEYCODE_NUM_LOCK,
        KeyEvent.KEYCODE_SCROLL_LOCK,
        KeyEvent.KEYCODE_FUNCTION,
        KeyEvent.KEYCODE_SYM,
    )

/**
 * Classifies a key press. [unicodeChar] is `KeyEvent.getUnicodeChar()` (it applies the event's
 * shift and caps lock state, so case comes out right); [ctrl], [alt] and [meta] are the shortcut
 * modifiers held (right Alt, AltGr, is not counted: it types characters on many layouts).
 */
fun physicalKeyFor(
    keyCode: Int,
    unicodeChar: Int,
    ctrl: Boolean,
    alt: Boolean,
    meta: Boolean,
): PhysicalKey =
    when {
        keyCode in MODIFIER_KEYS -> PhysicalKey.Ignore
        ctrl || alt || meta -> PhysicalKey.Shortcut
        keyCode == KeyEvent.KEYCODE_DEL -> PhysicalKey.Backspace
        keyCode == KeyEvent.KEYCODE_SPACE -> PhysicalKey.Space
        keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ->
            PhysicalKey.Enter
        unicodeChar == 0 ||
            unicodeChar and COMBINING_ACCENT != 0 ||
            unicodeChar < FIRST_PRINTABLE ||
            unicodeChar == DELETE_CHAR -> PhysicalKey.Shortcut
        else -> PhysicalKey.Text(String(Character.toChars(unicodeChar)))
    }
