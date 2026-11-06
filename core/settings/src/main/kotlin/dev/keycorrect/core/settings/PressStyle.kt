package dev.keycorrect.core.settings

/** How a held key is shown (docs/PHASE8.md, P8-5). */
enum class PressStyle {
    /** The key takes the pressed color (the look before this was a setting). */
    Shade,
    /** The key keeps its color and gets a thick outline. */
    Outline,
    None;

    companion object {
        fun parse(name: String?): PressStyle? = entries.firstOrNull { it.name == name }
    }
}
