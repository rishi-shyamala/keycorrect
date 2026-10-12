package dev.keycorrect.tools.emojibuild

import java.io.File

/** Raised for a missing or unparseable input; the CLI prints it and exits with status 1. */
class InputException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal fun invalid(message: String): Nothing = throw InputException(message)

/** One data line of emoji-test.txt. */
data class TestEntry(
    val codepoints: List<Int>,
    val status: String,
    val name: String,
    val group: String,
) {
    val value: String
        get() = buildString { codepoints.forEach { appendCodePoint(it) } }
}

data class EmojiTestFile(val unicodeVersion: String, val entries: List<TestEntry>)

/** Parses Unicode `emoji-test.txt`: `codepoints ; status # <emoji> E<ver> <name>`. */
object EmojiTestParser {
    private val VERSION = Regex("""#\s*Version:\s*(\d+\.\d+)""")
    private val STATUSES =
        setOf("component", "fully-qualified", "minimally-qualified", "unqualified")

    fun parse(file: File): EmojiTestFile {
        if (!file.isFile) invalid("emoji-test file not found: ${file.path}")
        return parse(file.readText(Charsets.UTF_8), file.path)
    }

    fun parse(text: String, source: String): EmojiTestFile {
        var version: String? = null
        var group: String? = null
        val entries = ArrayList<TestEntry>()
        for ((i, raw) in text.lineSequence().withIndex()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("# group:") -> group = line.removePrefix("# group:").trim()
                line.startsWith("#") ->
                    if (version == null) version = VERSION.find(line)?.groupValues?.get(1)
                else -> {
                    val g = group ?: invalid("$source:${i + 1}: entry before any group")
                    entries += parseLine(line, g, "$source:${i + 1}")
                }
            }
        }
        if (version == null) invalid("$source: no '# Version:' header line")
        if (entries.isEmpty()) invalid("$source: no emoji entries")
        return EmojiTestFile(version, entries)
    }

    private fun parseLine(line: String, group: String, where: String): TestEntry {
        val semi = line.indexOf(';')
        val hash = line.indexOf('#')
        if (semi < 0 || hash < semi) invalid("$where: expected 'codepoints ; status # ...'")
        val codepoints =
            line
                .substring(0, semi)
                .trim()
                .split(' ')
                .filter { it.isNotEmpty() }
                .map {
                    it.toIntOrNull(HEX)?.takeIf { cp -> cp in 0..Character.MAX_CODE_POINT }
                        ?: invalid("$where: bad code point '$it'")
                }
        val status = line.substring(semi + 1, hash).trim()
        if (codepoints.isEmpty() || status !in STATUSES) {
            invalid("$where: bad code points or status '$status'")
        }
        // "<emoji> E<version> <name>"
        val comment = line.substring(hash + 1).trim().split(' ', limit = COMMENT_FIELDS)
        if (comment.size < COMMENT_FIELDS || !comment[1].startsWith("E")) {
            invalid("$where: expected '# <emoji> E<version> <name>'")
        }
        return TestEntry(codepoints, status, comment[2].trim(), group)
    }

    private const val HEX = 16
    private const val COMMENT_FIELDS = 3
}
