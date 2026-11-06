package dev.keycorrect.core.settings

/** Where the keyboard's colors come from (docs/PHASE8.md, P8-4). */
enum class ThemeSource {
    /** Material You colors from the wallpaper (the default). */
    Wallpaper,
    /** The look before themes existed. */
    Classic,
    /** Neutral greys with a blue accent, light or dark with the system. */
    Samsung,
    HighContrast,
    /** The user's own colors ([KeyboardSettings.customLightColors] / `customDarkColors`). */
    Custom;

    companion object {
        fun parse(name: String?): ThemeSource? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Custom theme colors as a list of [COUNT] opaque ARGB ints (the order of the theme roles), stored
 * as one short hex string. An empty list means "not edited yet".
 */
object ThemeColors {
    const val COUNT = 8
    private const val OPAQUE = 0xFF000000.toInt()
    private const val HEX_RADIX = 16
    private const val HEX_DIGITS = 6
    private const val RGB_MASK = 0xFFFFFF
    private const val MAX_ENCODED = COUNT * (HEX_DIGITS + 1)

    fun sanitize(colors: List<Int>): List<Int> =
        if (colors.size == COUNT) colors.map { it or OPAQUE } else emptyList()

    fun encode(colors: List<Int>): String =
        sanitize(colors).joinToString(",") {
            (it and RGB_MASK).toString(HEX_RADIX).padStart(HEX_DIGITS, '0')
        }

    fun decode(text: String?): List<Int> {
        if (text.isNullOrEmpty() || text.length > MAX_ENCODED) return emptyList()
        val parts = text.split(',')
        if (parts.size != COUNT) return emptyList()
        return parts.map { part ->
            if (part.length != HEX_DIGITS) return emptyList()
            (part.toIntOrNull(HEX_RADIX) ?: return emptyList()) or OPAQUE
        }
    }
}
