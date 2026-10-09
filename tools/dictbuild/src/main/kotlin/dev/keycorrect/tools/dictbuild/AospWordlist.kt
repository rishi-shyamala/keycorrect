package dev.keycorrect.tools.dictbuild

import java.io.BufferedReader

/** One word line of an AOSP LatinIME `*.combined` wordlist. */
data class VocabEntry(
    val word: String,
    /** The `f=` value, 0..255 (0 = "never suggest" policy, not "rare"). */
    val freq: Int,
    /** The `originalFreq=` value (frequency before offensive/blocked adjustments), or [freq]. */
    val originalFreq: Int,
    /** abbreviation, hand-added, possibly_offensive, not_a_word, ... */
    val flags: Set<String>,
) {
    val notAWord: Boolean
        get() = NOT_A_WORD in flags

    companion object {
        const val NOT_A_WORD = "not_a_word"
        const val POSSIBLY_OFFENSIVE = "possibly_offensive"
    }
}

data class AospWordlist(
    /** Parsed `dictionary=...,locale=...,date=...,version=...` header line. */
    val header: Map<String, String>,
    val entries: List<VocabEntry>,
    /** Indented child lines (`shortcut=`, `bigram=`) that were skipped. */
    val skippedChildLines: Int,
)

/**
 * Parser for the AOSP combined wordlist text format (see `dictionaries/sample.combined`): a header
 * line, then ` word=...,f=...` lines (one leading space), each optionally followed by
 * deeper-indented `shortcut=` / `bigram=` lines. Shortcuts and bigrams are not used here (our
 * n-grams come from corpora), so they are skipped and counted.
 */
object AospWordlistParser {
    fun parse(reader: BufferedReader): AospWordlist {
        var header: Map<String, String> = emptyMap()
        val byWord = LinkedHashMap<String, VocabEntry>()
        var skipped = 0
        reader.forEachLine { raw ->
            if (raw.isBlank() || raw.startsWith("#")) return@forEachLine
            if (raw.startsWith("dictionary=")) {
                header = attributes(raw)
                return@forEachLine
            }
            val indent = raw.length - raw.trimStart().length
            if (indent >= 2) {
                skipped++
                return@forEachLine
            }
            val attrs = attributes(raw.trim())
            val word = attrs["word"] ?: return@forEachLine
            val freq = attrs["f"]?.toIntOrNull() ?: 0
            val flags = LinkedHashSet<String>()
            attrs["flags"]?.split('|')?.filter { it.isNotBlank() }?.let { flags.addAll(it) }
            if (attrs["possibly_offensive"] == "true") flags.add(VocabEntry.POSSIBLY_OFFENSIVE)
            if (attrs["not_a_word"] == "true") flags.add(VocabEntry.NOT_A_WORD)
            val entry = VocabEntry(word, freq, attrs["originalFreq"]?.toIntOrNull() ?: freq, flags)
            val prev = byWord[word]
            if (prev == null || entry.originalFreq > prev.originalFreq) byWord[word] = entry
        }
        return AospWordlist(header, byWord.values.toList(), skipped)
    }

    /** `a=1,b=2` -> map. Values never contain commas in the AOSP lists. */
    internal fun attributes(line: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (part in line.split(',')) {
            val eq = part.indexOf('=')
            if (eq > 0) out[part.substring(0, eq).trim()] = part.substring(eq + 1)
        }
        return out
    }
}
