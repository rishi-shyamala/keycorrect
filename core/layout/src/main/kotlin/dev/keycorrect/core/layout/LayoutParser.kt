package dev.keycorrect.core.layout

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Parses and validates layout JSON (docs/LAYOUT_FORMAT.md). */
internal object LayoutParser {
    const val FORMAT = 1

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        allowComments = true
        allowTrailingComma = true
        ignoreUnknownKeys = false
    }

    private val idPattern = Regex("[a-z0-9_]+")

    private enum class Type(val json: String, val width: Float, val style: KeyStyle) {
        Char("char", 1f, KeyStyle.Character),
        Text("text", 1f, KeyStyle.Character),
        Shift("shift", 1.5f, KeyStyle.Modifier),
        Backspace("backspace", 1.5f, KeyStyle.Modifier),
        Space("space", 5f, KeyStyle.Space),
        Enter("enter", 1.5f, KeyStyle.Action),
        Layer("layer", 1.5f, KeyStyle.Modifier),
        ModeSwitch("modeSwitch", 1.5f, KeyStyle.Modifier),
        Spacer("spacer", 1f, KeyStyle.Character),
        KeyCode("keycode", 1f, KeyStyle.Modifier),
        Modifier("modifier", 1f, KeyStyle.Modifier),
        Emoji("emoji", 1f, KeyStyle.Modifier),
    }

    /** A `keycode` key's `code` names: the action code, default label and spoken description. */
    private enum class Code(
        val json: String,
        val code: Int,
        val label: String,
        val description: String,
    ) {
        Escape("escape", KeyCodes.ESCAPE, "Esc", "Escape"),
        Tab("tab", KeyCodes.TAB, "Tab", "Tab"),
        Left("left", KeyCodes.DPAD_LEFT, "←", "Left"),
        Right("right", KeyCodes.DPAD_RIGHT, "→", "Right"),
        Up("up", KeyCodes.DPAD_UP, "↑", "Up"),
        Down("down", KeyCodes.DPAD_DOWN, "↓", "Down"),
        Home("home", KeyCodes.MOVE_HOME, "Home", "Home"),
        End("end", KeyCodes.MOVE_END, "End", "End"),
        PageUp("pageUp", KeyCodes.PAGE_UP, "PgUp", "Page up"),
        PageDown("pageDown", KeyCodes.PAGE_DOWN, "PgDn", "Page down"),
        ForwardDelete("forwardDelete", KeyCodes.FORWARD_DEL, "Del", "Forward delete"),
    }

    private val modifierLabels =
        mapOf(KeyModifier.Ctrl to ("Ctrl" to "Control"), KeyModifier.Alt to ("Alt" to "Alt"))

    private val styles =
        mapOf(
            "character" to KeyStyle.Character,
            "modifier" to KeyStyle.Modifier,
            "space" to KeyStyle.Space,
            "action" to KeyStyle.Action,
        )

    /** [expectedId], when given, must match the file's `id`. */
    fun parse(text: String, expectedId: String? = null): Layout {
        val tree =
            try {
                json.parseToJsonElement(text)
            } catch (e: SerializationException) {
                throw LayoutParseException(
                    "Layout '${expectedId ?: "?"}': invalid JSON: ${e.message}",
                    e,
                )
            }
        if (tree !is JsonObject) {
            throw LayoutParseException("Layout '${expectedId ?: "?"}': top level must be an object")
        }
        val id =
            (tree["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: expectedId ?: "?"
        fun fail(path: String, message: String): Nothing =
            throw LayoutParseException(
                if (path.isEmpty()) "Layout '$id': $message" else "Layout '$id' at $path: $message"
            )

        val format = (tree["format"] as? JsonPrimitive)?.intOrNull
        if (format != FORMAT) {
            fail("format", "unsupported format ${tree["format"] ?: "(missing)"}, expected $FORMAT")
        }
        val file =
            try {
                json.decodeFromJsonElement(LayoutFile.serializer(), tree)
            } catch (e: SerializationException) {
                throw LayoutParseException("Layout '$id': ${e.message}", e)
            }

        if (!idPattern.matches(file.id)) fail("id", "'${file.id}' must match [a-z0-9_]+")
        if (expectedId != null && file.id != expectedId) {
            fail("id", "'${file.id}' does not match requested id '$expectedId'")
        }
        if (file.name.isBlank()) fail("name", "must not be blank")
        val kind =
            LayoutKind.entries.firstOrNull { it.json == file.kind }
                ?: fail("kind", "unknown kind '${file.kind}'")
        val mode =
            if (file.bottomRow == null) {
                if (kind == LayoutKind.Letters || kind == LayoutKind.Symbols) {
                    BottomRowMode.Auto
                } else {
                    BottomRowMode.Included
                }
            } else {
                BottomRowMode.entries.firstOrNull { it.json == file.bottomRow }
                    ?: fail("bottomRow", "unknown bottomRow '${file.bottomRow}'")
            }
        if (kind == LayoutKind.Bottom && mode == BottomRowMode.Auto) {
            fail("bottomRow", "a bottom layout cannot use bottomRow 'auto'")
        }
        if (file.rows.isEmpty()) fail("rows", "must not be empty")

        fun resolveAll(onLetters: Boolean) =
            file.rows.mapIndexed { r, row ->
                val rowPath = "rows[$r]"
                if (!(row.height > 0f))
                    fail("$rowPath.height", "must be positive, got ${row.height}")
                if (row.keys.isEmpty()) fail("$rowPath.keys", "must not be empty")
                ResolvedRow(
                    row.keys.mapIndexed { k, key ->
                        resolveKey(key, "$rowPath.keys[$k]", kind, onLetters, popup = false, ::fail)
                    },
                    row.height,
                )
            }

        val onLetters = resolveAll(onLetters = true)
        val otherwise = if (kind == LayoutKind.Bottom) resolveAll(onLetters = false) else onLetters
        return Layout(file.id, file.name, kind, mode, onLetters, otherwise)
    }

    private fun resolveKey(
        key: Key,
        path: String,
        kind: LayoutKind,
        onLetters: Boolean,
        popup: Boolean,
        fail: (String, String) -> Nothing,
    ): ResolvedKey {
        val type =
            Type.entries.firstOrNull { it.json == key.type }
                ?: fail("$path.type", "unknown type '${key.type}'")
        val isTyped = type == Type.Char || type == Type.Text
        val takesPopup = isTyped || type == Type.KeyCode
        if (popup && !takesPopup)
            fail("$path.type", "popup keys must be char, text or keycode, got '${key.type}'")
        if (popup) {
            // Popup entries are drawn as fixed-size cells in the popup panel, so these are no-ops.
            if (key.popup.isNotEmpty()) fail("$path.popup", "popups cannot be nested")
            if (key.hint != HINT_UNSET) fail("$path.hint", "not valid on popup entries")
            if (key.width != null) fail("$path.width", "not valid on popup entries")
            if (key.style != null) fail("$path.style", "not valid on popup entries")
        } else if (!takesPopup && key.popup.isNotEmpty()) {
            fail("$path.popup", "only valid on char/text/keycode keys, got '${key.type}'")
        }
        if (type == Type.ModeSwitch && kind != LayoutKind.Bottom) {
            fail(path, "modeSwitch is only allowed in bottom layouts")
        }
        if (!isTyped && key.text != null) fail("$path.text", "only valid on char/text keys")
        if (type != Type.Char && key.shifted != null)
            fail("$path.shifted", "only valid on char keys")
        if (type != Type.Layer && key.target != null)
            fail("$path.target", "only valid on layer keys")
        if (type != Type.KeyCode && key.code != null)
            fail("$path.code", "only valid on keycode keys")
        if (type != Type.Modifier && key.modifier != null)
            fail("$path.modifier", "only valid on modifier keys")

        val width = key.width ?: type.width
        if (!(width > 0f)) fail("$path.width", "must be positive, got $width")
        val style =
            key.style?.let { styles[it] ?: fail("$path.style", "unknown style '$it'") }
                ?: type.style
        val popups =
            key.popup.mapIndexed { i, p ->
                resolveKey(p, "$path.popup[$i]", kind, onLetters, popup = true, fail)
            }
        val hint = if (key.hint == HINT_UNSET) popups.firstOrNull()?.label else key.hint

        fun build(
            label: String,
            action: KeyAction,
            description: String,
            shiftedLabel: String? = null,
        ) =
            ResolvedKey(
                label,
                action,
                width,
                style,
                popups,
                hint,
                key.description ?: description,
                shiftedLabel,
            )

        return when (type) {
            Type.Char,
            Type.Text -> {
                val label =
                    key.label ?: key.text ?: fail("$path.label", "missing required label or text")
                val text = key.text ?: label
                if (text.isEmpty()) fail("$path.text", "must not be empty")
                if (type == Type.Text) {
                    build(label, KeyAction.Text(text), label)
                } else if (key.shifted != null) {
                    build(label, KeyAction.Text(text, key.shifted), label, key.shifted)
                } else {
                    build(label, KeyAction.Text(text, upperOrNull(text)), label, upperOrNull(label))
                }
            }
            Type.Shift -> build(key.label ?: "⇧", KeyAction.Shift, "Shift")
            Type.Backspace -> build(key.label ?: "⌫", KeyAction.Backspace, "Delete")
            Type.Space -> build(key.label ?: "", KeyAction.Space, "Space")
            Type.Enter -> build(key.label ?: "↵", KeyAction.Enter, "Enter")
            Type.Layer -> {
                val label = key.label ?: fail("$path.label", "missing required label")
                val target = key.target ?: fail("$path.target", "layer key requires a target")
                build(label, KeyAction.SwitchLayer(target), "Switch to $target")
            }
            Type.ModeSwitch ->
                if (onLetters) {
                    build("?123", KeyAction.SwitchLayer(KeyboardSet.SYMBOLS), "Switch to symbols")
                } else {
                    build("ABC", KeyAction.SwitchLayer(KeyboardSet.LETTERS), "Switch to letters")
                }
            Type.Spacer -> build("", KeyAction.None, "")
            Type.KeyCode -> {
                val name = key.code ?: fail("$path.code", "keycode key requires a code")
                val code =
                    Code.entries.firstOrNull { it.json == name }
                        ?: fail(
                            "$path.code",
                            "unknown code '$name', expected one of " +
                                Code.entries.joinToString { it.json },
                        )
                build(key.label ?: code.label, KeyAction.KeyCode(code.code), code.description)
            }
            Type.Modifier -> {
                val name =
                    key.modifier ?: fail("$path.modifier", "modifier key requires a modifier")
                val modifier =
                    KeyModifier.entries.firstOrNull { it.name.lowercase() == name }
                        ?: fail("$path.modifier", "unknown modifier '$name', expected ctrl or alt")
                val (label, description) = modifierLabels.getValue(modifier)
                build(key.label ?: label, KeyAction.Modifier(modifier), description)
            }
            Type.Emoji -> build(key.label ?: "☺", KeyAction.Emoji, "Emoji")
        }
    }

    /** Upper-case form of a single cased letter (e.g. "q" to "Q"); null for anything else. */
    private fun upperOrNull(s: String): String? {
        if (s.codePointCount(0, s.length) != 1) return null
        val upper = s.uppercase(java.util.Locale.ROOT)
        return upper.takeIf { it != s && it.codePointCount(0, it.length) == 1 }
    }
}
