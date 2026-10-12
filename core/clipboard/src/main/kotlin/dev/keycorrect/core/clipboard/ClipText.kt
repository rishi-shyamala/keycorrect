package dev.keycorrect.core.clipboard

/** One-line display text for a clip. */
object ClipText {
    private val WHITESPACE = Regex("\\s+")
    const val DEFAULT_MAX_CHARS = 120

    /** Whitespace collapsed to single spaces, trimmed, cut with an ellipsis at [maxChars]. */
    fun preview(text: String, maxChars: Int = DEFAULT_MAX_CHARS): String {
        val line = text.replace(WHITESPACE, " ").trim()
        return when {
            line.length <= maxChars -> line
            else -> cut(line, maxChars) + "…"
        }
    }

    /** At most `maxChars - 1` chars, never splitting a surrogate pair. */
    private fun cut(line: String, maxChars: Int): String {
        var end = (maxChars - 1).coerceIn(0, line.length)
        if (end in 1 until line.length && line[end - 1].isHighSurrogate()) end--
        return line.substring(0, end)
    }
}
