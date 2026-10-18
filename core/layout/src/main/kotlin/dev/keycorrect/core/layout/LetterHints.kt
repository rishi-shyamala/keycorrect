package dev.keycorrect.core.layout

/**
 * Symbol hints and number-row hint rules for the letters layer (see [ComposeOptions.symbolHints]).
 *
 * Applies the rules to the letter [rows], which start at row index [firstRow] of the final layer:
 * digit entries come off the top row if the number row is on ([numberRow]), and each character key
 * gets the symbol at its row and column of [symbols] (null: no symbol hints) as its hint and first
 * popup entry.
 */
internal fun withLetterHints(
    rows: List<ResolvedRow>,
    firstRow: Int,
    symbols: List<ResolvedRow>?,
    numberRow: Boolean,
): List<ResolvedRow> = rows.mapIndexed { index, row ->
    val symbolKeys = symbols?.getOrNull(firstRow + index)?.keys?.filter { it.isTextKey }
    var column = 0
    row.copy(
        keys =
            row.keys.map { key ->
                if (!key.isTextKey) return@map key
                val symbol = symbolKeys?.getOrNull(column++)
                val plain = if (numberRow && index == 0) key.withoutDigitEntries() else key
                if (symbol == null) plain else plain.withSymbolHint(symbol)
            }
    )
}

/** A key that types text (letters, digits, symbols), as opposed to a function key or spacer. */
private val ResolvedKey.isTextKey: Boolean
    get() = style == KeyStyle.Character && action is KeyAction.Text

private fun ResolvedKey.withoutDigitEntries(): ResolvedKey {
    val kept = popup.filterNot { it.label.isDigit() }
    if (kept.size == popup.size) return this
    return copy(popup = kept, hint = if (hint?.isDigit() == true) null else hint)
}

private fun ResolvedKey.withSymbolHint(symbol: ResolvedKey): ResolvedKey {
    val entry = ResolvedKey(symbol.label, symbol.action, description = symbol.description)
    return copy(
        hint = symbol.label,
        popup = if (popup.any { it.label == symbol.label }) popup else listOf(entry) + popup,
    )
}

private fun String.isDigit() = length == 1 && this[0] in '0'..'9'
