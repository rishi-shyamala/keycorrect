package dev.keycorrect.tools.dictbuild

/** Minimal pretty JSON writer for the stats file (maps, lists, strings, numbers, booleans). */
object Json {
    fun write(value: Any?): String =
        StringBuilder().also { append(it, value, 0) }.append('\n').toString()

    private fun append(sb: StringBuilder, v: Any?, indent: Int) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean,
            is Int,
            is Long -> sb.append(v.toString())
            is Double -> sb.append(if (v.isFinite()) formatDouble(v) else "null")
            is Float -> sb.append(if (v.isFinite()) formatDouble(v.toDouble()) else "null")
            is Map<*, *> -> {
                if (v.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append("{\n")
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(",\n")
                    first = false
                    pad(sb, indent + 1)
                    quote(sb, k.toString())
                    sb.append(": ")
                    append(sb, x, indent + 1)
                }
                sb.append('\n')
                pad(sb, indent)
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(", ")
                    first = false
                    append(sb, x, indent)
                }
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun formatDouble(d: Double): String =
        if (d == Math.rint(d) && Math.abs(d) < 1e15) d.toLong().toString()
        else "%.6g".format(java.util.Locale.ROOT, d)

    private fun pad(sb: StringBuilder, n: Int) = repeat(n) { sb.append("  ") }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
