package dev.keycorrect.core.layout

/** What happens when a key is activated. */
sealed interface KeyAction {
    /** Types [text], or [shiftedText] (when non-null) while shift is on. */
    data class Text(val text: String, val shiftedText: String? = null) : KeyAction

    data object Shift : KeyAction

    data object Backspace : KeyAction

    data object Space : KeyAction

    data object Enter : KeyAction

    data class SwitchLayer(val layerId: String) : KeyAction

    /**
     * Sends a raw key event: the terminal row's Esc, Tab, arrows, Home/End, PgUp/PgDn. [code] is an
     * Android `KeyEvent` key code (see [KeyCodes]).
     */
    data class KeyCode(val code: Int) : KeyAction

    /** Ctrl or Alt for the next key: one-shot on a tap, locked on a double tap. */
    data class Modifier(val modifier: KeyModifier) : KeyAction

    /** Opens the emoji panel; a long-press opens the clipboard (Phase 4). */
    data object Emoji : KeyAction

    /** A non-interactive gap. */
    data object None : KeyAction
}

enum class KeyModifier {
    Ctrl,
    Alt,
}

/**
 * Android `KeyEvent` key codes used by layouts. They are fixed platform API values; this module is
 * plain JVM, so they are repeated here rather than read from `android.view.KeyEvent`.
 */
object KeyCodes {
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val TAB = 61
    const val DEL = 67
    const val PAGE_UP = 92
    const val PAGE_DOWN = 93
    const val ESCAPE = 111
    const val FORWARD_DEL = 112
    const val MOVE_HOME = 122
    const val MOVE_END = 123
}

/** Visual class of a key, used for styling. */
enum class KeyStyle {
    Character,
    Modifier,
    Space,
    Action,
}

/**
 * One key with every default applied. [width] is relative to a standard key (1.0). [hint] is the
 * small corner label; [shiftedLabel] is what [label] shows while shift is on (null: unchanged).
 */
data class ResolvedKey(
    val label: String,
    val action: KeyAction,
    val width: Float = 1f,
    val style: KeyStyle = KeyStyle.Character,
    val popup: List<ResolvedKey> = emptyList(),
    val hint: String? = null,
    val description: String = label,
    val shiftedLabel: String? = null,
) {
    /** Keys that act on press and auto-repeat while held. */
    val repeatable: Boolean
        get() = action == KeyAction.Backspace

    /** Spacers take space but never receive touches. */
    val isSpacer: Boolean
        get() = action == KeyAction.None
}

/** A row of keys; [height] is a weight relative to the other rows. */
data class ResolvedRow(val keys: List<ResolvedKey>, val height: Float = 1f) {
    val width: Float
        get() = keys.sumOf { it.width.toDouble() }.toFloat()
}

/** A row added to a layout when the number row is on: [row] goes in at row index [insertAt]. */
data class NumberRowExtra(val insertAt: Int, val row: ResolvedRow)

/**
 * A fully composed keyboard page, ready to draw.
 *
 * [gridUnits] is the minimum number of key units the geometry divides the width by: every key unit
 * is `width / max(gridUnits, widest row)`. The composer sets 10 (the letters grid) on every layer
 * except the numeric pads, so all of those share one letter-key width. 0 (the default) means the
 * unit is `width / widest row`, which numeric pads and hand-built layers keep.
 */
data class KeyboardLayer(val id: String, val rows: List<ResolvedRow>, val gridUnits: Float = 0f)
