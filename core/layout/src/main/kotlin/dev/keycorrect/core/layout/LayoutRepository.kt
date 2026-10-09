package dev.keycorrect.core.layout

import java.util.concurrent.ConcurrentHashMap

/**
 * Loads layouts by id through [loader] (returns the JSON text, or null if unknown), parsing and
 * validating each once. Thread-safe.
 */
class LayoutRepository(private val loader: (id: String) -> String?) {
    private val cache = ConcurrentHashMap<String, Layout>()

    /** @throws LayoutParseException if the layout is missing or invalid. */
    fun get(id: String): Layout =
        cache.getOrPut(id) {
            val text = loader(id) ?: throw LayoutParseException("Layout '$id': not found")
            LayoutParser.parse(text, expectedId = id)
        }
}

/** Layouts bundled in this module's resources (`layouts/<id>.json`). */
object BuiltInLayouts {
    private val idPattern = Regex("[a-z0-9_]+")

    val loader: (String) -> String? = { id ->
        if (!idPattern.matches(id)) {
            null
        } else {
            BuiltInLayouts::class.java.getResourceAsStream("/layouts/$id.json")?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        }
    }

    /** A repository over the bundled layouts. */
    fun repository() = LayoutRepository(loader)
}
