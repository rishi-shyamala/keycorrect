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

    /** A non-interactive gap. */
    data object None : KeyAction
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

/** A fully composed keyboard page, ready to draw. */
data class KeyboardLayer(val id: String, val rows: List<ResolvedRow>)
