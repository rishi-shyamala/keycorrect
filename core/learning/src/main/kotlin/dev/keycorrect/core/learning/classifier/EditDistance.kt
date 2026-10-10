package dev.keycorrect.core.learning.classifier

/** Optimal-string-alignment distance (Levenshtein plus adjacent transposition = 1). */
internal fun editDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    val d = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) d[i][0] = i
    for (j in 0..b.length) d[0][j] = j
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            var best = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                best = minOf(best, d[i - 2][j - 2] + 1)
            }
            d[i][j] = best
        }
    }
    return d[a.length][b.length]
}

/** Lowercase with typographic apostrophes folded, the comparison form used across learning. */
internal fun fold(s: String): String = s.lowercase().replace('’', '\'').replace('‘', '\'')
