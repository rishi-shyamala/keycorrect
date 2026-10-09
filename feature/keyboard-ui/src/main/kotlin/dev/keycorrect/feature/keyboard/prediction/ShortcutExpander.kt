package dev.keycorrect.feature.keyboard.prediction

import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * An expansion with its variables resolved. [cursor] is the offset in [text] where the cursor
 * should end up (from `{cursor}`), or -1 to leave it after the text.
 */
data class Expansion(val text: String, val cursor: Int = -1) {
    /** The text before the cursor spot (all of it when there is none). */
    val head: String
        get() = if (cursor < 0) text else text.substring(0, cursor)

    /** The text after the cursor spot (empty when there is none). */
    val tail: String
        get() = if (cursor < 0) "" else text.substring(cursor)
}

/**
 * Resolves the variables of a text-expansion shortcut (docs/PLAN.md §12): `{date}` and `{time}`
 * with the formatters given (the app passes the device's date/time settings; the defaults follow
 * the default locale), `{cursor}` marks where the cursor goes (the first one counts; the others are
 * dropped). `{clipboard}` and any unknown `{...}` stay literal for now.
 */
class ShortcutExpander(
    private val now: () -> Long = System::currentTimeMillis,
    private val formatDate: (Long) -> String = { millis ->
        DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(millis))
    },
    private val formatTime: (Long) -> String = { millis ->
        DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date(millis))
    },
) {
    fun expand(template: String): Expansion {
        if ('{' !in template) return Expansion(template)
        val out = StringBuilder(template.length + 16)
        var cursor = -1
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '{') {
                val end = template.indexOf('}', i)
                if (end > i) {
                    val replacement =
                        when (template.substring(i + 1, end)) {
                            "date" -> formatDate(now())
                            "time" -> formatTime(now())
                            "cursor" -> {
                                if (cursor < 0) cursor = out.length
                                ""
                            }
                            else -> null
                        }
                    if (replacement != null) {
                        out.append(replacement)
                        i = end + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
        return Expansion(out.toString(), cursor)
    }

    /** The expansion as plain text for display: variables resolved, no cursor marker. */
    fun preview(template: String): String = expand(template).text.replace('\n', ' ')
}
