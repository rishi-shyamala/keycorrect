package dev.keycorrect.core.learning.model

/**
 * Counts of "keep" reverts (docs/PLAN.md §6.3 rows 6/7): the user undid an autocorrection typed ->
 * corrected and finished the restored typed word unchanged. Counted per typed word and per (typed,
 * corrected) pair, each with the time of the first and the latest keep. The model compares the
 * counts with its runtime thresholds (see [PersonalModel.updateThresholds]). Single-writer.
 */
internal class KeepCounts(private val maxEntries: Int) {
    class Keep(
        val surface: String,
        val target: String,
        var count: Int,
        val first: Long,
        var last: Long,
    )

    /** key(typed) -> keeps of that word over every correction of it. */
    val words = LinkedHashMap<String, Keep>()

    /** join(key(typed), key(corrected)) -> keeps of that pair. */
    val pairs = LinkedHashMap<String, Keep>()

    fun wordKey(typed: String) = Text.key(typed)

    fun pairKey(typed: String, corrected: String) = Text.join(Text.key(typed), Text.key(corrected))

    fun wordCount(typed: String) = words[wordKey(typed)]?.count ?: 0

    fun pairCount(typed: String, corrected: String) = pairs[pairKey(typed, corrected)]?.count ?: 0

    fun add(typed: String, corrected: String, now: Long) {
        bump(words, wordKey(typed), Text.fold(typed), "", 1, now)
        bump(pairs, pairKey(typed, corrected), Text.fold(typed), Text.fold(corrected), 1, now)
        enforceCap()
    }

    /** Takes one keep back (forget since); entries reaching zero disappear. */
    fun remove(typed: String, corrected: String) {
        drop(words, wordKey(typed))
        drop(pairs, pairKey(typed, corrected))
    }

    /**
     * Forgets the keeps of one pair (the user lifted its suppression) and takes them off the word.
     */
    fun forgetPair(typed: String, corrected: String) {
        val e = pairs.remove(pairKey(typed, corrected)) ?: return
        val w = words[wordKey(typed)] ?: return
        w.count -= e.count
        if (w.count <= 0) words.remove(wordKey(typed))
    }

    private fun bump(
        m: MutableMap<String, Keep>,
        key: String,
        surface: String,
        target: String,
        by: Int,
        now: Long,
    ) {
        val e = m.remove(key) // re-insert last so the map stays in recency order
        m[key] =
            Keep(
                e?.surface ?: surface,
                e?.target ?: target,
                (e?.count ?: 0) + by,
                e?.first ?: now,
                now,
            )
    }

    private fun drop(m: MutableMap<String, Keep>, key: String) {
        val e = m[key] ?: return
        e.count--
        if (e.count <= 0) m.remove(key)
    }

    private fun enforceCap() {
        while (words.size > maxEntries) words.remove(words.keys.first())
        while (pairs.size > maxEntries) pairs.remove(pairs.keys.first())
    }

    fun clear() {
        words.clear()
        pairs.clear()
    }

    fun importRows(rows: List<KeepRow>) {
        clear()
        for (r in rows.sortedBy { it.lastMillis }) {
            if (r.count <= 0 || Text.key(r.typed).isEmpty()) continue
            if (r.corrected.isEmpty()) {
                words[wordKey(r.typed)] = Keep(r.typed, "", r.count, r.firstMillis, r.lastMillis)
            } else {
                pairs[pairKey(r.typed, r.corrected)] =
                    Keep(r.typed, r.corrected, r.count, r.firstMillis, r.lastMillis)
            }
        }
        enforceCap()
    }

    fun exportRows(): List<KeepRow> =
        (words.values + pairs.values)
            .map { KeepRow(it.surface, it.target, it.count, it.first, it.last) }
            .sortedWith(compareBy({ it.typed }, { it.corrected }))
}
