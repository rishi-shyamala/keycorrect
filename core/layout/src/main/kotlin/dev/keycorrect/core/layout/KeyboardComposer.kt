package dev.keycorrect.core.layout

import java.util.concurrent.ConcurrentHashMap

/** Maps layer ids to layout ids. */
data class KeyboardSet(val layouts: Map<String, String>) {
    /** The layout id for [layerId]. */
    fun layoutId(layerId: String): String =
        layouts[layerId] ?: throw IllegalArgumentException("No layer '$layerId' in keyboard set")

    companion object {
        const val LETTERS = "letters"
        const val SYMBOLS = "symbols"
        const val SYMBOLS2 = "symbols2"

        /** The built-in set (docs/LAYOUT_FORMAT.md). */
        val Default =
            KeyboardSet(
                mapOf(
                    LETTERS to "qwerty",
                    SYMBOLS to "symbols",
                    SYMBOLS2 to "symbols_more",
                    "numpad" to "numpad",
                    "phone" to "phone",
                    "datetime" to "datetime",
                )
            )
    }
}

/** Which bottom row to append, chosen by field type. */
enum class BottomRowVariant(val layoutId: String) {
    Text("bottom_text"),
    Email("bottom_email"),
    Url("bottom_url"),
}

/** [numberRow] prepends a row of digits to the letters layer. */
data class ComposeOptions(val numberRow: Boolean = false)

/** Builds ready-to-draw [KeyboardLayer]s from layouts, adding bottom and number rows. */
class KeyboardComposer(private val repository: LayoutRepository) {
    private data class CacheKey(
        val set: KeyboardSet,
        val layerId: String,
        val variant: BottomRowVariant,
        val options: ComposeOptions,
    )

    private val cache = ConcurrentHashMap<CacheKey, KeyboardLayer>()

    /** @throws LayoutParseException if a needed layout is missing or invalid. */
    fun compose(
        set: KeyboardSet,
        layerId: String,
        variant: BottomRowVariant = BottomRowVariant.Text,
        options: ComposeOptions = ComposeOptions(),
    ): KeyboardLayer =
        cache.getOrPut(CacheKey(set, layerId, variant, options)) {
            val layout = repository.get(set.layoutId(layerId))
            val rows = buildList {
                if (options.numberRow && layerId == KeyboardSet.LETTERS) add(NUMBER_ROW)
                addAll(layout.rows(layerId))
                if (layout.bottomRow == BottomRowMode.Auto) {
                    addAll(repository.get(variant.layoutId).rows(layerId))
                }
            }
            KeyboardLayer(layerId, rows)
        }

    private companion object {
        val NUMBER_ROW =
            ResolvedRow(
                "1234567890".map { ResolvedKey(it.toString(), KeyAction.Text(it.toString())) }
            )
    }
}
