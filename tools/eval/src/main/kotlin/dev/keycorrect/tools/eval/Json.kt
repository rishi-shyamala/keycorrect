package dev.keycorrect.tools.eval

/** Minimal JSON writer for maps, lists, numbers, strings and booleans (no dependency needed). */
object Json {
    fun write(value: Any?, indent: Int = 0): String =
        StringBuilder().also { append(it, value, indent) }.toString()

    private fun append(sb: StringBuilder, value: Any?, indent: Int) {
        when (value) {
            null -> sb.append("null")
            is String -> quote(sb, value)
            is Boolean -> sb.append(value)
            is Int,
            is Long -> sb.append(value)
            is Float -> number(sb, value.toDouble())
            is Double -> number(sb, value)
            is Map<*, *> -> {
                if (value.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append("{\n")
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(",\n")
                    first = false
                    pad(sb, indent + 1)
                    quote(sb, k.toString())
                    sb.append(": ")
                    append(sb, v, indent + 1)
                }
                sb.append('\n')
                pad(sb, indent)
                sb.append('}')
            }
            is Iterable<*> -> {
                val items = value.toList()
                if (items.isEmpty()) {
                    sb.append("[]")
                    return
                }
                val simple = items.all {
                    it == null || it is Number || it is String || it is Boolean
                }
                if (simple) {
                    sb.append('[')
                    items.forEachIndexed { i, v ->
                        if (i > 0) sb.append(", ")
                        append(sb, v, indent)
                    }
                    sb.append(']')
                } else {
                    sb.append("[\n")
                    items.forEachIndexed { i, v ->
                        if (i > 0) sb.append(",\n")
                        pad(sb, indent + 1)
                        append(sb, v, indent + 1)
                    }
                    sb.append('\n')
                    pad(sb, indent)
                    sb.append(']')
                }
            }
            else -> quote(sb, value.toString())
        }
    }

    private fun number(sb: StringBuilder, v: Double) {
        if (v.isNaN() || v.isInfinite()) sb.append("null")
        else sb.append(String.format(java.util.Locale.ROOT, "%.6g", v).let { trim(it) })
    }

    private fun trim(s: String): String =
        if (s.contains('e') || s.contains('E') || !s.contains('.')) s
        else s.trimEnd('0').trimEnd('.')

    private fun pad(sb: StringBuilder, level: Int) {
        repeat(level) { sb.append("  ") }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
