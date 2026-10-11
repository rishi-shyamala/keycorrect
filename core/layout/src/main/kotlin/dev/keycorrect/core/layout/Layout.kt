package dev.keycorrect.core.layout

/** Layout category from the file's `kind`. */
enum class LayoutKind(val json: String) {
    Letters("letters"),
    Symbols("symbols"),
    Numpad("numpad"),
    Phone("phone"),
    Datetime("datetime"),
    Bottom("bottom"),

    /** Extra keys composed above the rows of every layer (the terminal row). */
    Extra("extra"),
}

/** Whether a bottom row is appended at runtime ([Auto]) or the rows are already complete. */
enum class BottomRowMode(val json: String) {
    Auto("auto"),
    Included("included"),
}

/**
 * A parsed and validated layout file. Compose it into a [KeyboardLayer] with [KeyboardComposer].
 */
class Layout
internal constructor(
    val id: String,
    val name: String,
    val kind: LayoutKind,
    val bottomRow: BottomRowMode,
    private val onLettersLayer: List<ResolvedRow>,
    private val otherwise: List<ResolvedRow>,
) {
    /**
     * Rows for the layer named [layerId]. Only bottom layouts differ by layer, because their
     * `modeSwitch` key resolves to `?123` on the letters layer and `ABC` elsewhere.
     */
    fun rows(layerId: String): List<ResolvedRow> =
        if (layerId == KeyboardSet.LETTERS) onLettersLayer else otherwise

    override fun toString() = "Layout($id)"
}
