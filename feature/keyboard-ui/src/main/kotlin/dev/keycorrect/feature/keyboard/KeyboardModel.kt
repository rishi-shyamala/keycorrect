package dev.keycorrect.feature.keyboard

/** What happens when a key is activated. */
sealed interface KeyAction {
    data class Text(val text: String) : KeyAction

    data object Shift : KeyAction

    data object Backspace : KeyAction

    data object Space : KeyAction

    data object Enter : KeyAction

    data class SwitchLayer(val layer: Layer) : KeyAction
}

enum class Layer {
    Letters,
    Symbols,
}

/** Visual/behavioural class of a key, used for styling. */
enum class KeyRole {
    Character,
    Modifier,
    Space,
    Action,
}

/**
 * One key. [width] is relative to a standard key (1.0). Phase 0 placeholder for the JSON layout
 * schema planned in docs/PLAN.md §13.
 */
data class Key(
    val label: String,
    val action: KeyAction,
    val width: Float = 1f,
    val role: KeyRole = KeyRole.Character,
) {
    /** Keys that act on press and auto-repeat while held. */
    val repeatable: Boolean
        get() = action == KeyAction.Backspace
}

data class KeyboardLayer(val rows: List<List<Key>>)

/** Built-in layouts for Phase 0. Replaced by the layout engine in Phase 1. */
object BuiltInLayouts {
    private fun chars(chars: String) = chars.map {
        Key(it.toString(), KeyAction.Text(it.toString()))
    }

    private val shift = Key("⇧", KeyAction.Shift, width = 1.5f, role = KeyRole.Modifier)
    private val backspace = Key("⌫", KeyAction.Backspace, width = 1.5f, role = KeyRole.Modifier)
    private val enter = Key("↵", KeyAction.Enter, width = 1.5f, role = KeyRole.Action)
    private val space = Key("", KeyAction.Space, width = 5f, role = KeyRole.Space)
    private val comma = Key(",", KeyAction.Text(","))
    private val period = Key(".", KeyAction.Text("."))

    val letters =
        KeyboardLayer(
            listOf(
                chars("qwertyuiop"),
                chars("asdfghjkl"),
                listOf(shift) + chars("zxcvbnm") + backspace,
                listOf(
                    Key(
                        "?123",
                        KeyAction.SwitchLayer(Layer.Symbols),
                        width = 1.5f,
                        role = KeyRole.Modifier,
                    ),
                    comma,
                    space,
                    period,
                    enter,
                ),
            )
        )

    val symbols =
        KeyboardLayer(
            listOf(
                chars("1234567890"),
                chars("@#\$_&-+()/"),
                listOf(Key("%", KeyAction.Text("%"), width = 1.5f)) + chars("*\"':;!?") + backspace,
                listOf(
                    Key(
                        "ABC",
                        KeyAction.SwitchLayer(Layer.Letters),
                        width = 1.5f,
                        role = KeyRole.Modifier,
                    ),
                    comma,
                    space,
                    period,
                    enter,
                ),
            )
        )

    fun forLayer(layer: Layer): KeyboardLayer =
        when (layer) {
            Layer.Letters -> letters
            Layer.Symbols -> symbols
        }
}
