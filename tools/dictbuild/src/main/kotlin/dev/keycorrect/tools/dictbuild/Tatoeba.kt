package dev.keycorrect.tools.dictbuild

data class TatoebaSentence(val id: Long, val lang: String, val text: String)

object Tatoeba {
    /** `id<TAB>lang<TAB>text`; null for malformed lines. */
    fun parseLine(line: String): TatoebaSentence? {
        val a = line.indexOf('\t')
        if (a <= 0) return null
        val b = line.indexOf('\t', a + 1)
        if (b < 0) return null
        val id = line.substring(0, a).toLongOrNull() ?: return null
        val text = line.substring(b + 1).trim()
        if (text.isEmpty()) return null
        return TatoebaSentence(id, line.substring(a + 1, b), text)
    }

    /** SplitMix64 finaliser: a stable, well-mixed hash (sequential ids spread evenly). */
    fun mix64(x: Long): Long {
        var z = x + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** Deterministic held-out selection by sentence id: ~[percent]% of ids. */
    fun isHeldOut(id: Long, percent: Int): Boolean = Math.floorMod(mix64(id), 100L) < percent

    /** Case/whitespace-insensitive 64-bit text fingerprint (FNV-1a) used to drop duplicates. */
    fun textKey(text: String): Long {
        var h = -0x340d631b7bdddcdbL
        var lastSpace = true
        for (ch in text) {
            val c = if (ch.isWhitespace()) ' ' else ch.lowercaseChar()
            if (c == ' ') {
                if (lastSpace) continue
                lastSpace = true
            } else {
                lastSpace = false
            }
            h = (h xor c.code.toLong()) * 0x100000001b3L
        }
        return h
    }
}
