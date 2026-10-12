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

/**
 * [numberRow] prepends a row of digits to the letters layer.
 *
 * [emojiKey] keeps the bottom row's emoji key; without it (sensitive fields) the key is dropped and
 * its width goes to the space key, so the row keeps its width.
 *
 * [terminalRow] puts the terminal extra-keys row (`terminal_row`) above the rows of every layer, so
 * it stays on screen when the layer changes.
 *
 * [signedNumber] and [decimalNumber] mirror the `TYPE_NUMBER_FLAG_SIGNED` / `..._DECIMAL` flags of
 * a number field and only affect `numpad` layouts. When [signedNumber] is false the `-` key becomes
 * a spacer; when [decimalNumber] is false the `.` and `,` keys do. Both separators count as decimal
 * separators (the numpad offers both so one layout serves every locale; there is no thousands
 * grouping key because number fields reject it). The spacer keeps the key's width, so geometry is
 * stable and touches go to the neighbouring keys.
 */
data class ComposeOptions(
    val numberRow: Boolean = false,
    val signedNumber: Boolean = true,
    val decimalNumber: Boolean = true,
    val terminalRow: Boolean = false,
    val emojiKey: Boolean = true,
)

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
                if (options.terminalRow) addAll(repository.get(TERMINAL_ROW).rows(layerId))
                if (options.numberRow && layerId == KeyboardSet.LETTERS) add(NUMBER_ROW)
                val own = layout.rows(layerId)
                addAll(if (layout.kind == LayoutKind.Numpad) restrictNumpad(own, options) else own)
                if (layout.bottomRow == BottomRowMode.Auto) {
                    val bottom = repository.get(variant.layoutId).rows(layerId)
                    addAll(if (options.emojiKey) bottom else bottom.map(::withoutEmojiKey))
                }
            }
            KeyboardLayer(layerId, rows)
        }

    /** The row without its emoji key, the key's width given to the space key. */
    private fun withoutEmojiKey(row: ResolvedRow): ResolvedRow {
        val emoji = row.keys.firstOrNull { it.action == KeyAction.Emoji } ?: return row
        val keys =
            row.keys
                .filter { it !== emoji }
                .map {
                    if (it.action == KeyAction.Space) it.copy(width = it.width + emoji.width)
                    else it
                }
        return row.copy(keys = keys)
    }

    private fun restrictNumpad(
        rows: List<ResolvedRow>,
        options: ComposeOptions,
    ): List<ResolvedRow> =
        if (options.signedNumber && options.decimalNumber) {
            rows
        } else {
            rows.map { row -> row.copy(keys = row.keys.map { hideIfDisallowed(it, options) }) }
        }

    private fun hideIfDisallowed(key: ResolvedKey, options: ComposeOptions): ResolvedKey {
        val typed = (key.action as? KeyAction.Text)?.text
        val hidden =
            (typed == "-" && !options.signedNumber) ||
                ((typed == "." || typed == ",") && !options.decimalNumber)
        return if (hidden) {
            ResolvedKey(label = "", action = KeyAction.None, width = key.width, description = "")
        } else {
            key
        }
    }

    private companion object {
        const val TERMINAL_ROW = "terminal_row"

        val NUMBER_ROW =
            ResolvedRow(
                "1234567890".map { ResolvedKey(it.toString(), KeyAction.Text(it.toString())) }
            )
    }
}
