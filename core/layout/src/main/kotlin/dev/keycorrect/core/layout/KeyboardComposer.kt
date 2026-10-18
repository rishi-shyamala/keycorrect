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
 * [numberRow] prepends a row of digits to the letters layer. The symbol layers get their layout's
 * `numberRowExtra` row instead (page 1 inserts operators under its digits, page 2 a digits row on
 * top), so every layer has the same number of rows and the same key height.
 *
 * [bottomKeyWidth] is the width, in letter-key units, of the bottom row's emoji, comma and period
 * keys; [modeEnterKeyWidth] that of its mode-switch (?123/ABC) and Enter keys. The space key takes
 * the rest so the row is exactly 10 units. If the side keys would leave the space less than 1 unit,
 * they shrink in proportion to leave it exactly 1 (the row stays 10 units wide).
 *
 * [emojiKey] keeps the bottom row's emoji key; without it (sensitive fields) the key is dropped and
 * its width goes to the space key, so the row keeps its width.
 *
 * [fieldModes] offers the manual field-kind switch (a plain text field): the long-press popup of
 * the mode-switch key (`fieldMode` entries in the bottom layout), and on a number or phone pad a
 * bottom "ABC" key that goes back. Without it the popup entries are dropped and the pads get no
 * extra key.
 *
 * [symbolHints] gives each key of the letters layer the symbol at the same row and column of the
 * composed first symbols page as its corner hint and first popup entry (function keys do not count
 * as columns). With the number row on, the top letter row has no digit hints or digit popup
 * entries, since the digits are on the row above.
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
    val bottomKeyWidth: Float = DEFAULT_BOTTOM_KEY_WIDTH,
    val fieldModes: Boolean = false,
    val modeEnterKeyWidth: Float = DEFAULT_MODE_ENTER_KEY_WIDTH,
    val symbolHints: Boolean = true,
) {
    companion object {
        const val DEFAULT_BOTTOM_KEY_WIDTH = 0.75f
        const val DEFAULT_MODE_ENTER_KEY_WIDTH = 1.15f
    }
}

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
            val numeric = layout.kind.isNumeric
            val rows = buildList {
                if (options.terminalRow) addAll(repository.get(TERMINAL_ROW).rows(layerId))
                if (options.numberRow && layerId == KeyboardSet.LETTERS) add(NUMBER_ROW)
                val own = layout.rows(layerId)
                if (numeric) {
                    addAll(
                        if (layout.kind == LayoutKind.Numpad) restrictNumpad(own, options) else own
                    )
                    val back = if (options.fieldModes) textModeRow(layout.kind, own) else null
                    if (back != null) add(back)
                } else {
                    val sized = own.map(::capFunctionKeys)
                    val capped =
                        if (layout.kind == LayoutKind.Letters) {
                                val symbols =
                                    if (options.symbolHints) symbolRows(set, variant, options)
                                    else null
                                withLetterHints(sized, size, symbols, options.numberRow)
                            } else {
                                sized
                            }
                            .toMutableList()
                    val extra = layout.numberRowExtra
                    if (options.numberRow && extra != null) capped.add(extra.insertAt, extra.row)
                    addAll(capped)
                }
                if (layout.bottomRow == BottomRowMode.Auto) {
                    val bottom = repository.get(variant.layoutId).rows(layerId)
                    addAll(bottom.map { sizeBottomRow(it, options) })
                }
            }
            KeyboardLayer(layerId, rows, if (numeric) 0f else GRID_UNITS)
        }

    /** Modifier and action keys (shift, backspace, layer, enter, ...) are at most 1.5 units. */
    private fun capFunctionKeys(row: ResolvedRow): ResolvedRow =
        if (row.keys.none { it.isFunctionKey && it.width > MAX_FUNCTION_WIDTH }) {
            row
        } else {
            row.copy(
                keys =
                    row.keys.map {
                        if (it.isFunctionKey && it.width > MAX_FUNCTION_WIDTH) {
                            it.copy(width = MAX_FUNCTION_WIDTH)
                        } else {
                            it
                        }
                    }
            )
        }

    /** The rows of the composed first symbols page, or null if [set] has none. */
    private fun symbolRows(
        set: KeyboardSet,
        variant: BottomRowVariant,
        options: ComposeOptions,
    ): List<ResolvedRow>? =
        if (KeyboardSet.SYMBOLS in set.layouts) {
            compose(set, KeyboardSet.SYMBOLS, variant, options).rows
        } else {
            null
        }

    /**
     * A bottom row (one with a space key) with the emoji key dropped if disabled, the mode-switch
     * and Enter keys [ComposeOptions.modeEnterKeyWidth] wide, every other key
     * [ComposeOptions.bottomKeyWidth] wide, and the space key taking the rest of 10 units.
     */
    private fun sizeBottomRow(row: ResolvedRow, options: ComposeOptions): ResolvedRow {
        if (row.keys.none { it.action == KeyAction.Space }) return row
        val keys =
            (if (options.emojiKey) row.keys else row.keys.filter { it.action != KeyAction.Emoji })
                .map { if (options.fieldModes) it else it.withoutFieldModes() }
        val side = options.bottomKeyWidth.coerceAtLeast(MIN_BOTTOM_KEY_WIDTH)
        val modeEnter = options.modeEnterKeyWidth.coerceAtLeast(MIN_BOTTOM_KEY_WIDTH)
        fun wanted(key: ResolvedKey) =
            if (key.action == KeyAction.Enter || key.action is KeyAction.SwitchLayer) modeEnter
            else side
        val sides = keys.filter { it.action != KeyAction.Space }.sumOf { wanted(it).toDouble() }
        // Side keys so wide that the space would drop under its minimum shrink in proportion.
        val scale = minOf(1.0, (GRID_UNITS - MIN_SPACE_WIDTH) / sides).toFloat()
        val space = GRID_UNITS - (sides * scale).toFloat()
        return row.copy(
            keys =
                keys.map {
                    it.copy(width = if (it.action == KeyAction.Space) space else wanted(it) * scale)
                }
        )
    }

    private fun ResolvedKey.withoutFieldModes(): ResolvedKey =
        if (popup.any { it.action is KeyAction.SetFieldMode }) copy(popup = emptyList()) else this

    /**
     * The key that takes a number or phone pad back to text, as a thin row of its own under the
     * pad: tap for the text keyboard, long-press for the other kinds. The entries are those of the
     * text bottom row's mode-switch key; null if that layout has none (a user-edited set).
     */
    private fun textModeRow(kind: LayoutKind, pad: List<ResolvedRow>): ResolvedRow? {
        val entries =
            if (kind == LayoutKind.Numpad || kind == LayoutKind.Phone) modeEntries() else null
        return entries?.let { modeRow(pad.maxOf { row -> row.width }, it) }
    }

    private fun modeEntries(): List<ResolvedKey>? =
        repository
            .get(BottomRowVariant.Text.layoutId)
            .rows(KeyboardSet.LETTERS)
            .flatMap { it.keys }
            .firstOrNull { k -> k.popup.any { it.action is KeyAction.SetFieldMode } }
            ?.popup

    private fun modeRow(width: Float, entries: List<ResolvedKey>): ResolvedRow {
        val back = FieldMode.Text
        return ResolvedRow(
            listOf(
                ResolvedKey(
                    label = "ABC",
                    action = KeyAction.SetFieldMode(back),
                    width = width,
                    style = KeyStyle.Modifier,
                    popup = entries,
                    hint = null,
                    description = back.description,
                )
            ),
            TEXT_MODE_ROW_HEIGHT,
        )
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

    private val LayoutKind.isNumeric: Boolean
        get() = this == LayoutKind.Numpad || this == LayoutKind.Phone || this == LayoutKind.Datetime

    private val ResolvedKey.isFunctionKey: Boolean
        get() = style == KeyStyle.Modifier || style == KeyStyle.Action

    private companion object {
        const val TERMINAL_ROW = "terminal_row"

        /** The letters grid: ten standard keys across. */
        const val GRID_UNITS = 10f
        const val MAX_FUNCTION_WIDTH = 1.5f
        const val MIN_BOTTOM_KEY_WIDTH = 0.1f
        const val MIN_SPACE_WIDTH = 1f

        /** The "ABC" row under a number or phone pad reached by the manual field switch. */
        const val TEXT_MODE_ROW_HEIGHT = 0.6f

        val NUMBER_ROW =
            ResolvedRow(
                "1234567890".map { ResolvedKey(it.toString(), KeyAction.Text(it.toString())) }
            )
    }
}
