package dev.keycorrect.core.settings

/**
 * Which screen shape a keyboard size belongs to: phone or tablet (smallest width of at least
 * [TABLET_MIN_SMALLEST_WIDTH_DP]) crossed with portrait or landscape (docs/PHASE8.md, P8-2).
 */
data class SizeProfile(val tablet: Boolean, val landscape: Boolean) {
    /** The stable name used in stored keys, e.g. `phone_landscape`. */
    val id: String
        get() = (if (tablet) "tablet" else "phone") + "_" + (if (landscape) "landscape" else "portrait")

    companion object {
        const val TABLET_MIN_SMALLEST_WIDTH_DP = 600

        /** All four profiles, phone first, portrait before landscape. */
        val ALL: List<SizeProfile> =
            listOf(false, true).flatMap { tablet ->
                listOf(false, true).map { landscape -> SizeProfile(tablet, landscape) }
            }

        /** The profile of a screen with this smallest width and orientation. */
        fun of(smallestWidthDp: Int, landscape: Boolean) =
            SizeProfile(smallestWidthDp >= TABLET_MIN_SMALLEST_WIDTH_DP, landscape)

        fun fromId(id: String?): SizeProfile? = ALL.firstOrNull { it.id == id }
    }
}

/** The settings that are remembered per [SizeProfile]; every other setting is global. */
data class SizeSettings(
    val keyHeightDp: Int,
    val bottomKeyWidth: Float,
    val modeEnterKeyWidth: Float,
    val edgePaddingDp: Int,
    val keyGapDp: Int,
    val numberRow: Boolean,
) {
    /** Returns a copy with every value clamped into its valid range. */
    fun sanitized(): SizeSettings =
        copy(
            keyHeightDp = keyHeightDp.coerceIn(KeyboardSettings.KEY_HEIGHT_DP_RANGE),
            bottomKeyWidth =
                if (bottomKeyWidth.isNaN()) KeyboardSettings.DEFAULT_BOTTOM_KEY_WIDTH
                else bottomKeyWidth.coerceIn(KeyboardSettings.BOTTOM_KEY_WIDTH_RANGE),
            modeEnterKeyWidth =
                if (modeEnterKeyWidth.isNaN()) KeyboardSettings.DEFAULT_MODE_ENTER_KEY_WIDTH
                else modeEnterKeyWidth.coerceIn(KeyboardSettings.MODE_ENTER_KEY_WIDTH_RANGE),
            edgePaddingDp = edgePaddingDp.coerceIn(KeyboardSettings.EDGE_PADDING_DP_RANGE),
            keyGapDp = keyGapDp.coerceIn(KeyboardSettings.KEY_GAP_DP_RANGE),
        )
}
