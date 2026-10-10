package dev.keycorrect.core.learning.model

/**
 * Short buffer of recently committed sentences for later on-device finetuning (decision D10: on by
 * default, 7-day TTL, "clear now"). The keyboard calls [recordSentenceWord] for each committed
 * word, in order, with the sentence-end flag. PRIVACY: only the keyboard's privacy gate may call
 * this, and only for non-sensitive input (never password / no-learning / incognito fields, never
 * pasted, autofilled or expanded text); this class cannot tell. Rows are persisted by the
 * (encrypted) store.
 *
 * Words of one session accumulate in an open sentence which closes on [sentenceEnd], at
 * [SentenceBufferConfig.maxSentenceWords], or on [closeSession]. Thread-safe (the finetuning worker
 * may read while the learning thread writes).
 */
class SentenceBuffer(private val config: SentenceBufferConfig = SentenceBufferConfig()) {
    data class Sentence(val sessionId: Long, val text: String, val timeMillis: Long)

    private class Open(val words: ArrayList<String> = ArrayList())

    private val open = LinkedHashMap<Long, Open>()
    private val closed = ArrayDeque<Sentence>()
    private var chars = 0

    @Synchronized
    fun recordSentenceWord(sessionId: Long, word: String, sentenceEnd: Boolean, timeMillis: Long) {
        if (!config.enabled) return
        val w = word.trim().replace(Regex("\\s+"), " ")
        prune(timeMillis)
        if (w.isNotEmpty()) {
            val o = open.getOrPut(sessionId) { Open() }
            o.words += w
            if (o.words.size >= config.maxSentenceWords) close(sessionId, timeMillis)
        }
        if (sentenceEnd) close(sessionId, timeMillis)
        // Bound abandoned open sentences (sessions that never ended).
        while (open.size > 8) close(open.keys.first(), timeMillis)
    }

    /** Closes the open sentence of [sessionId] (field / session ended mid-sentence). */
    @Synchronized
    fun closeSession(sessionId: Long, timeMillis: Long) {
        if (config.enabled) close(sessionId, timeMillis)
    }

    private fun close(sessionId: Long, timeMillis: Long) {
        val o = open.remove(sessionId) ?: return
        if (o.words.isEmpty()) return
        val text = o.words.joinToString(" ")
        closed.addLast(Sentence(sessionId, text, timeMillis))
        chars += text.length
        trim()
    }

    private fun trim() {
        while (
            closed.size > config.maxSentences ||
                (chars > config.maxTotalChars && closed.isNotEmpty())
        ) {
            chars -= closed.removeFirst().text.length
        }
    }

    /** Drops sentences older than the TTL. */
    @Synchronized
    fun prune(nowMillis: Long) {
        val cutoff = nowMillis - config.ttlMillis
        while (closed.isNotEmpty() && closed.first().timeMillis < cutoff) {
            chars -= closed.removeFirst().text.length
        }
    }

    /** Closed sentences newest-last, after TTL pruning at [nowMillis]. */
    @Synchronized
    fun recentSentences(nowMillis: Long, limit: Int = Int.MAX_VALUE): List<Sentence> {
        prune(nowMillis)
        return closed.toList().takeLast(limit)
    }

    @Synchronized fun size(): Int = closed.size

    @Synchronized
    fun clear() {
        open.clear()
        closed.clear()
        chars = 0
    }

    /** "Forget the last N minutes": drops closed and open material from [timeMillis] on. */
    @Synchronized
    fun forgetSince(timeMillis: Long): Int {
        val before = closed.size
        closed.removeAll { it.timeMillis >= timeMillis }
        chars = closed.sumOf { it.text.length }
        open.clear()
        return before - closed.size
    }

    @Synchronized
    fun exportRows(): List<SentenceRow> = closed.map {
        SentenceRow(it.sessionId, it.text, it.timeMillis)
    }

    @Synchronized
    fun importRows(rows: List<SentenceRow>) {
        clear()
        if (!config.enabled) return
        for (r in rows.sortedBy { it.timeMillis }) {
            closed.addLast(Sentence(r.sessionId, r.text, r.timeMillis))
            chars += r.text.length
        }
        trim()
    }
}
