package dev.keycorrect.core.layout

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Marks an absent `hint` so an explicit `null` (hide the hint) can be told apart. */
internal const val HINT_UNSET = "\u0000unset"

/** On-disk shape of a layout file (docs/LAYOUT_FORMAT.md). Strings are validated after decoding. */
@Serializable
internal data class LayoutFile(
    val format: Int,
    val id: String,
    val name: String,
    val kind: String = "letters",
    val bottomRow: String? = null,
    val rows: List<@Serializable(with = RowSerializer::class) Row>,
)

@Serializable
internal data class Row(
    val height: Float = 1f,
    val keys: List<@Serializable(with = KeySerializer::class) Key>,
)

@Serializable
internal data class Key(
    val type: String = "char",
    val label: String? = null,
    val text: String? = null,
    val shifted: String? = null,
    val popup: List<@Serializable(with = KeySerializer::class) Key> = emptyList(),
    val hint: String? = HINT_UNSET,
    val width: Float? = null,
    val target: String? = null,
    val style: String? = null,
    val description: String? = null,
)

/** Accepts a bare string as shorthand for `{ "label": ... }`. */
internal object KeySerializer : JsonTransformingSerializer<Key>(Key.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonPrimitive && element.isString) {
            buildJsonObject { put("label", element.content) }
        } else {
            element
        }
}

/** Accepts a bare array as shorthand for `{ "keys": [...] }`. */
internal object RowSerializer : JsonTransformingSerializer<Row>(Row.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonArray) buildJsonObject { put("keys", element) } else element
}
