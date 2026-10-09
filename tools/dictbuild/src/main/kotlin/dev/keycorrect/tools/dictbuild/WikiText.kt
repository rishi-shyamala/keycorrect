package dev.keycorrect.tools.dictbuild

/**
 * Wikitext to plain-sentence extraction, good enough for n-gram counting (not a full MediaWiki
 * parser). Removes comments, `<ref>` and similar blocks, templates (nested), tables, file /
 * category / interwiki links, external-link URLs, emphasis quotes, headings, HTML tags and
 * entities; `[[target|label]]` becomes `label`.
 */
object WikiText {
    private val BLOCK_TAGS =
        setOf(
            "ref",
            "references",
            "gallery",
            "math",
            "timeline",
            "imagemap",
            "syntaxhighlight",
            "source",
            "score",
            "mapframe",
            "maplink",
            "templatedata",
            "hiero",
            "pre",
        )
    private val SPACE_TAGS =
        setOf(
            "br",
            "p",
            "div",
            "li",
            "ul",
            "ol",
            "dl",
            "dd",
            "dt",
            "tr",
            "td",
            "th",
            "table",
            "hr",
            "center",
            "blockquote",
            "h1",
            "h2",
            "h3",
            "h4",
            "h5",
            "h6",
        )
    private val DROP_NAMESPACES = setOf("file", "image", "media", "category")
    private val INTERWIKI =
        setOf(
            "wikipedia",
            "wp",
            "wikt",
            "wiktionary",
            "commons",
            "c",
            "w",
            "meta",
            "m",
            "wikidata",
            "d",
            "species",
            "wikisource",
            "wikibooks",
            "wikiquote",
            "wikinews",
            "wikiversity",
            "s",
            "b",
            "q",
            "n",
            "v",
            "simple",
        )
    private val LANG_PREFIX = Regex("[a-z]{2,3}(-[a-z]+)?")
    private val URL =
        Regex("(?i)\\b(?:https?|ftp)://[^\\s<>\\[\\]|}\"]+|\\bwww\\.[^\\s<>\\[\\]|}\"]+")
    private val QUOTES = Regex("'{2,}")
    private val MAGIC = Regex("__[A-Z]+__")
    private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);")
    private val HEADING = Regex("^=+.*=+\\s*$")
    private val LIST_MARKER = Regex("^[*#:;]+\\s*")

    /** Full markup removal. Block structure is kept: the result is newline-separated lines. */
    fun strip(source: String): String {
        var s = removeComments(source)
        s = removeBlockTags(s)
        s = removeTemplates(s)
        s = removeTables(s)
        s = replaceLinks(s)
        s = replaceExternalLinks(s)
        s = URL.replace(s, " ")
        s = QUOTES.replace(s, "")
        s = MAGIC.replace(s, "")
        s = stripTags(s)
        return decodeEntities(s)
    }

    /** Extracts sentences from raw wikitext, passing each to [sink]. */
    fun sentences(source: String, sink: (String) -> Unit) {
        for (rawLine in strip(source).split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty() || HEADING.matches(line)) continue
            val first = line[0]
            if (first == '|' || first == '!' || first == '{' || first == '}') continue
            if (first == '*' || first == '#' || first == ':' || first == ';') {
                // List items: keep only complete sentences (most are fragments / dates / names).
                val item = LIST_MARKER.replace(line, "")
                if (endsSentence(item)) splitSentences(item, sink)
                continue
            }
            splitSentences(line, sink)
        }
    }

    private fun endsSentence(s: String): Boolean {
        var i = s.length - 1
        while (i >= 0 && s[i] in "\"')]”’ ") i--
        return i >= 0 && s[i] in ".!?"
    }

    // --- individual passes (internal for tests) ---------------------------------------------

    internal fun removeComments(s: String): String {
        if (!s.contains("<!--")) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (true) {
            val a = s.indexOf("<!--", i)
            if (a < 0) {
                sb.append(s, i, s.length)
                break
            }
            sb.append(s, i, a)
            val b = s.indexOf("-->", a + 4)
            if (b < 0) break
            i = b + 3
        }
        return sb.toString()
    }

    internal fun removeBlockTags(s: String): String {
        if (!s.contains('<')) return s
        val n = s.length
        val sb = StringBuilder(n)
        var i = 0
        while (i < n) {
            val lt = s.indexOf('<', i)
            if (lt < 0) {
                sb.append(s, i, n)
                break
            }
            sb.append(s, i, lt)
            var j = lt + 1
            while (j < n && s[j].isLetter()) j++
            val name = s.substring(lt + 1, j).lowercase()
            if (name in BLOCK_TAGS) {
                val gt = s.indexOf('>', j)
                if (gt < 0) {
                    i = n
                    continue
                }
                if (s[gt - 1] == '/') {
                    i = gt + 1
                    continue
                }
                val close = s.indexOf("</$name", gt + 1, ignoreCase = true)
                if (close < 0) {
                    i = gt + 1
                    continue
                }
                val closeGt = s.indexOf('>', close)
                i = if (closeGt < 0) n else closeGt + 1
                continue
            }
            sb.append('<')
            i = lt + 1
        }
        return sb.toString()
    }

    /** Removes `{{...}}` including nested templates. An unterminated opener is kept literal. */
    internal fun removeTemplates(s: String): String {
        if (!s.contains("{{")) return s
        val n = s.length
        val sb = StringBuilder(n)
        var depth = 0
        var outerStart = -1
        var i = 0
        while (i < n) {
            if (s.startsWith("{{", i)) {
                if (depth == 0) outerStart = i
                depth++
                i += 2
                continue
            }
            if (depth > 0 && s.startsWith("}}", i)) {
                depth--
                i += 2
                continue
            }
            if (depth == 0) sb.append(s[i])
            i++
        }
        if (depth > 0) sb.append(removeTemplates(s.substring(outerStart + 2)))
        return sb.toString()
    }

    /** Drops `{| ... |}` tables (nested too) line by line. */
    internal fun removeTables(s: String): String {
        if (!s.contains("{|")) return s
        val sb = StringBuilder(s.length)
        var depth = 0
        for (line in s.split('\n')) {
            val t = line.trimStart()
            if (t.startsWith("{|")) {
                depth++
                continue
            }
            if (depth > 0) {
                if (t.startsWith("|}")) depth--
                continue
            }
            sb.append(line).append('\n')
        }
        return sb.toString()
    }

    /** `[[a|b]]` -> `b`, `[[a]]` -> `a`; File/Image/Category/interwiki links are dropped. */
    internal fun replaceLinks(s: String): String {
        if (!s.contains("[[")) return s
        val n = s.length
        val sb = StringBuilder(n)
        var i = 0
        while (i < n) {
            val open = s.indexOf("[[", i)
            if (open < 0) {
                sb.append(s, i, n)
                break
            }
            sb.append(s, i, open)
            var depth = 1
            var j = open + 2
            while (j < n && depth > 0) {
                if (s.startsWith("[[", j)) {
                    depth++
                    j += 2
                } else if (s.startsWith("]]", j)) {
                    depth--
                    j += 2
                } else {
                    j++
                }
            }
            if (depth > 0) {
                sb.append("[[")
                i = open + 2
                continue
            }
            sb.append(renderLink(s.substring(open + 2, j - 2)))
            i = j
        }
        return sb.toString()
    }

    private fun renderLink(inner: String): String {
        var pipe = -1
        var depth = 0
        var k = 0
        while (k < inner.length) {
            if (inner.startsWith("[[", k)) {
                depth++
                k += 2
            } else if (inner.startsWith("]]", k)) {
                depth--
                k += 2
            } else if (inner[k] == '|' && depth == 0) {
                pipe = k
                break
            } else {
                k++
            }
        }
        val rawTarget = (if (pipe < 0) inner else inner.substring(0, pipe)).trim()
        val leadingColon = rawTarget.startsWith(":")
        val target = rawTarget.removePrefix(":").trim()
        val colon = target.indexOf(':')
        var dropNamespace = false
        if (colon > 0) {
            val prefix = target.substring(0, colon).trim().lowercase()
            if (prefix in DROP_NAMESPACES) {
                if (!leadingColon) return ""
                dropNamespace = true
            } else if (
                !leadingColon && (prefix in INTERWIKI || LANG_PREFIX.matches(prefix) && colon <= 6)
            ) {
                return ""
            }
        }
        if (pipe >= 0) {
            val label = inner.substring(pipe + 1)
            if (label.isNotBlank()) return replaceLinks(label)
        }
        var shown = target.substringBefore('#')
        if (dropNamespace) shown = shown.substringAfter(':')
        return shown
    }

    /** `[http://x label]` -> `label`; `[http://x]` -> nothing. */
    internal fun replaceExternalLinks(s: String): String {
        if (!s.contains('[')) return s
        val n = s.length
        val sb = StringBuilder(n)
        var i = 0
        while (i < n) {
            val c = s[i]
            if (
                c == '[' &&
                    (s.startsWith("http://", i + 1, ignoreCase = true) ||
                        s.startsWith("https://", i + 1, ignoreCase = true) ||
                        s.startsWith("ftp://", i + 1, ignoreCase = true) ||
                        s.startsWith("//", i + 1))
            ) {
                val close = s.indexOf(']', i)
                val nl = s.indexOf('\n', i)
                if (close > 0 && (nl < 0 || close < nl)) {
                    val body = s.substring(i + 1, close)
                    val sp = body.indexOfFirst { it.isWhitespace() }
                    if (sp >= 0) sb.append(body.substring(sp + 1))
                    i = close + 1
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** Removes remaining HTML tags (keeping their text); block-ish tags become a space. */
    internal fun stripTags(s: String): String {
        if (!s.contains('<')) return s
        val n = s.length
        val sb = StringBuilder(n)
        var i = 0
        while (i < n) {
            val c = s[i]
            if (
                c == '<' && i + 1 < n && (s[i + 1].isLetter() || s[i + 1] == '/' || s[i + 1] == '!')
            ) {
                val gt = s.indexOf('>', i)
                if (gt > 0 && gt - i < 2000) {
                    var j = i + 1
                    if (s[j] == '/') j++
                    val start = j
                    while (j < gt && s[j].isLetterOrDigit()) j++
                    if (s.substring(start, j).lowercase() in SPACE_TAGS) sb.append(' ')
                    i = gt + 1
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    internal fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        return ENTITY.replace(s) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    codePoint(body.substring(2).toIntOrNull(16))
                body.startsWith("#") -> codePoint(body.substring(1).toIntOrNull())
                else ->
                    when (body) {
                        "nbsp",
                        "thinsp",
                        "ensp",
                        "emsp" -> " "
                        "amp" -> "&"
                        "lt" -> "<"
                        "gt" -> ">"
                        "quot" -> "\""
                        "apos" -> "'"
                        "ndash",
                        "mdash" -> " – "
                        else -> " "
                    }
            }
        }
    }

    private fun codePoint(cp: Int?): String =
        if (cp != null && cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) {
            String(Character.toChars(cp))
        } else {
            " "
        }

    // --- sentence splitting ------------------------------------------------------------------

    private val ABBREVIATIONS =
        setOf(
            "mr",
            "mrs",
            "ms",
            "dr",
            "prof",
            "sr",
            "jr",
            "st",
            "mt",
            "ft",
            "lt",
            "col",
            "gen",
            "sgt",
            "capt",
            "cmdr",
            "hon",
            "rev",
            "vs",
            "cf",
            "approx",
            "vol",
            "fig",
            "dept",
            "est",
            "gov",
            "sen",
            "rep",
            "pres",
        )

    /**
     * Splits one line of prose on `.!?` followed by space and a capital / digit / opening quote.
     */
    fun splitSentences(line: String, sink: (String) -> Unit) {
        val n = line.length
        var start = 0
        var i = 0
        while (i < n) {
            val c = line[i]
            if (c == '.' || c == '!' || c == '?') {
                var end = i + 1
                while (end < n && line[end] in ".!?") end++ // runs like "?!" or "..."
                var closeEnd = end
                while (closeEnd < n && line[closeEnd] in "\"')]”’") closeEnd++
                var next = closeEnd
                while (next < n && line[next] == ' ') next++
                val boundary =
                    when {
                        closeEnd >= n -> true
                        next == closeEnd -> false // no space after the terminator ("3.5", "U.S.A")
                        else -> startsSentence(line[next]) && !(c == '.' && isAbbreviation(line, i))
                    }
                if (boundary) {
                    val sentence = line.substring(start, closeEnd).trim()
                    if (sentence.isNotEmpty()) sink(sentence)
                    start = closeEnd
                }
                i = closeEnd
                continue
            }
            i++
        }
        val tail = line.substring(start).trim()
        if (tail.isNotEmpty()) sink(tail)
    }

    private fun startsSentence(c: Char) =
        c.isUpperCase() || c.isDigit() || c == '"' || c == '(' || c == '[' || c == '“'

    private fun isAbbreviation(line: String, dot: Int): Boolean {
        var s = dot
        while (s > 0 && (line[s - 1].isLetter() || line[s - 1] == '.')) s--
        val word = line.substring(s, dot)
        if (word.isEmpty()) return false
        if (word.length == 1 && word[0].isLetter()) return true // initials: "J. Smith"
        if (word.contains('.')) return true // "U.S." / "e.g."
        return word.lowercase() in ABBREVIATIONS
    }
}
