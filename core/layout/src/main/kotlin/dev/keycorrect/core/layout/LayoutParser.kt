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
    }

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
        if (popup && !isTyped)
            fail("$path.type", "popup keys must be char or text, got '${key.type}'")
        if (type == Type.ModeSwitch && kind != LayoutKind.Bottom) {
            fail(path, "modeSwitch is only allowed in bottom layouts")
        }
        if (!isTyped && key.text != null) fail("$path.text", "only valid on char/text keys")
        if (type != Type.Char && key.shifted != null)
            fail("$path.shifted", "only valid on char keys")
        if (type != Type.Layer && key.target != null)
            fail("$path.target", "only valid on layer keys")

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
        }
    }

    /** Upper-case form of a single cased letter (e.g. "q" to "Q"); null for anything else. */
    private fun upperOrNull(s: String): String? {
        if (s.codePointCount(0, s.length) != 1) return null
        val upper = s.uppercase(java.util.Locale.ROOT)
        return upper.takeIf { it != s && it.codePointCount(0, it.length) == 1 }
    }
}
