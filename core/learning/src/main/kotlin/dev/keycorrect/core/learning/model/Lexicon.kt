package dev.keycorrect.core.learning.model

import dev.keycorrect.core.learning.LearningUpdate.AddUserWord

/** A word on the user dictionary as the model knows it (for the "What KeyCorrect learned" UI). */
data class UserWordInfo(val word: String, val reason: AddUserWord.Reason, val createdAtMillis: Long)

/** What [PersonalModel.forgetSince] removed, so the app can mirror it in its own stores. */
data class ForgetResult(
    val removedUserWords: List<String>,
    val removedSuppressions: List<Pair<String, String>>,
    val removedBlocks: List<String>,
    val subtractedUpdates: Int,
    val droppedOutcomes: Int,
    val removedSentences: Int,
    /** Time of the ranker checkpoint the weights were restored from, null = reset to initial. */
    val rankerRestoredFromMillis: Long?,
)

/**
 * The discrete, rule-based part of the personal model: user words (with new-word promotion),
 * suppressions, blocklist and casing preferences. Single-writer.
 */
internal class Lexicon(private val cfg: PersonalModelConfig) {
    class UserWord(
        val word: String,
        val reason: AddUserWord.Reason,
        val createdAt: Long,
        val removed: Boolean,
    )

    class Candidate(
        var word: String,
        var commitWeight: Double,
        val firstSeen: Long,
        var lastSeen: Long,
        val sessions: LinkedHashMap<Long, Double> = LinkedHashMap(),
    )

    val userWords = HashMap<String, UserWord>()
    val suppressions = HashMap<String, Long>() // "typed SEP target" -> createdAt
    val blocked = HashMap<String, Long>() // key -> createdAt
    val casing = HashMap<String, HashMap<String, Double>>() // key -> surface -> count
    val candidates = HashMap<String, Candidate>()

    // ---- queries ----

    fun isUserWord(key: String) = userWords[key]?.removed == false

    fun isBlocked(key: String) = blocked.containsKey(key)

    fun suppressionKey(typed: String, target: String) = Text.join(Text.key(typed), Text.key(target))

    // ---- user words ----

    /** Returns true if the word was newly added (the caller emits the side effect). */
    fun addUserWord(word: String, reason: AddUserWord.Reason, now: Long): Boolean {
        val key = Text.key(word)
        if (key.isEmpty() || isBlocked(key)) return false
        val existing = userWords[key]
        if (existing != null && !existing.removed) return false
        if (existing != null && existing.removed && reason != AddUserWord.Reason.Explicit) {
            return false // the user removed it; only an explicit add brings it back
        }
        userWords[key] = UserWord(Text.fold(word), reason, now, false)
        candidates.remove(key)
        return true
    }

    fun removeUserWord(word: String, now: Long): Boolean {
        val key = Text.key(word)
        val e = userWords[key] ?: return false
        if (e.removed) return false
        userWords[key] = UserWord(e.word, e.reason, now, true)
        return true
    }

    // ---- new-word promotion (§6.3 row 4) ----

    /** Accumulates evidence; returns the promoted word's surface form when it crosses the rule. */
    fun noteCandidate(word: String, sessionId: Long, weight: Double, now: Long): String? {
        val key = Text.key(word)
        if (key.isEmpty() || weight <= 0.0 || isBlocked(key) || userWords.containsKey(key))
            return null
        val c = candidates.getOrPut(key) { Candidate(Text.fold(word), 0.0, now, now) }
        c.commitWeight += weight
        c.lastSeen = now
        c.sessions[sessionId] = (c.sessions[sessionId] ?: 0.0) + weight
        while (c.sessions.size > 16) c.sessions.remove(c.sessions.keys.first())
        val sessions = c.sessions.values.count { it >= cfg.promoteSessionMinWeight }
        if (
            (c.commitWeight >= cfg.promoteMinCommits && sessions >= cfg.promoteMinSessions) ||
                (c.commitWeight >= cfg.promoteFastWeight && sessions >= cfg.promoteFastSessions)
        ) {
            return if (addUserWord(c.word, AddUserWord.Reason.Promoted, now)) c.word else null
        }
        return null
    }

    fun forgetCandidate(word: String, sessionId: Long, weight: Double) {
        val key = Text.key(word)
        val c = candidates[key] ?: return
        c.commitWeight -= weight
        val s = (c.sessions[sessionId] ?: 0.0) - weight
        if (s <= 1e-9) c.sessions.remove(sessionId) else c.sessions[sessionId] = s
        if (c.commitWeight <= 1e-9) candidates.remove(key)
    }

    fun pruneCandidates(now: Long) {
        candidates.values.removeAll { now - it.lastSeen > cfg.candidateTtlMillis }
        if (candidates.size > cfg.maxCandidateWords) {
            val drop =
                candidates.entries
                    .sortedBy { it.value.lastSeen }
                    .take(candidates.size - cfg.maxCandidateWords)
            for (e in drop) candidates.remove(e.key)
        }
    }

    // ---- suppressions / blocks ----

    fun suppress(typed: String, target: String, now: Long) {
        if (Text.key(typed).isEmpty() || Text.key(target).isEmpty()) return
        suppressions.putIfAbsent(suppressionKey(typed, target), now)
    }

    fun removeSuppression(typed: String, target: String) =
        suppressions.remove(suppressionKey(typed, target)) != null

    fun block(word: String, now: Long) {
        val key = Text.key(word)
        if (key.isEmpty()) return
        blocked.putIfAbsent(key, now)
        candidates.remove(key)
    }

    fun unblock(word: String) = blocked.remove(Text.key(word)) != null

    // ---- casing (row 13) ----

    fun addCasing(surface: String, delta: Double) {
        val key = Text.key(surface)
        if (key.isEmpty()) return
        val m = casing.getOrPut(key) { HashMap() }
        val s = Text.fold(surface)
        val v = (m[s] ?: 0.0) + delta
        if (v <= 1e-9) m.remove(s) else m[s] = v
        if (m.isEmpty()) casing.remove(key)
        if (m.values.sum() > 100.0) for (e in m.entries) e.setValue(e.value / 2)
    }

    /**
     * Learned non-lowercase surface per key: the top variant with enough, and strictly leading,
     * support.
     */
    fun preferredCasing(): Map<String, String> {
        val out = HashMap<String, String>()
        for ((key, m) in casing) {
            val sorted = m.entries.sortedByDescending { it.value }
            val top = sorted.first()
            if (top.value < cfg.casingMinObservations) continue
            if (sorted.size > 1 && sorted[1].value >= top.value) continue
            if (top.key == key) continue
            out[key] = top.key
        }
        return out
    }

    // ---- forget ----

    fun forgetSince(t: Long): Triple<List<String>, List<Pair<String, String>>, List<String>> {
        val words = userWords.filter { it.value.createdAt >= t }.keys.toList()
        val removedWords = ArrayList<String>()
        for (k in words) {
            val e = userWords.remove(k)!!
            if (!e.removed) removedWords += e.word
        }
        val sup = suppressions.filter { it.value >= t }.keys.toList()
        sup.forEach { suppressions.remove(it) }
        val blk = blocked.filter { it.value >= t }.keys.toList()
        blk.forEach { blocked.remove(it) }
        return Triple(removedWords, sup.map { it.split(Text.SEP).let { p -> p[0] to p[1] } }, blk)
    }

    // ---- persistence ----

    fun importRows(state: PersistedLearning) {
        userWords.clear()
        suppressions.clear()
        blocked.clear()
        casing.clear()
        candidates.clear()
        for (r in state.userWords) {
            val reason =
                AddUserWord.Reason.entries.getOrElse(r.reason) { AddUserWord.Reason.Explicit }
            userWords[r.key] = UserWord(r.word, reason, r.createdAtMillis, r.removed)
        }
        for (r in state.suppressions) suppressions[Text.join(r.typed, r.target)] = r.createdAtMillis
        for (r in state.blocks) blocked[r.word] = r.createdAtMillis
        for (r in state.casing) casing.getOrPut(r.key) { HashMap() }[r.surface] = r.count
        for (r in state.candidates) {
            candidates[r.key] =
                Candidate(r.word, r.commitWeight, r.firstSeenMillis, r.lastSeenMillis)
        }
        for (r in state.candidateSessions) candidates[r.key]?.sessions?.put(r.sessionId, r.weight)
    }

    fun userWordRows() =
        userWords
            .map { (k, v) -> UserWordRow(k, v.word, v.reason.ordinal, v.createdAt, v.removed) }
            .sortedBy { it.key }

    fun suppressionRows() =
        suppressions
            .map { (k, t) -> k.split(Text.SEP).let { SuppressionRow(it[0], it[1], t) } }
            .sortedWith(compareBy({ it.typed }, { it.target }))

    fun blockRows() = blocked.map { BlockRow(it.key, it.value) }.sortedBy { it.word }

    fun casingRows() =
        casing
            .flatMap { (k, m) -> m.map { CasingRow(k, it.key, it.value) } }
            .sortedWith(compareBy({ it.key }, { it.surface }))

    fun candidateRows() =
        candidates
            .map { (k, c) -> CandidateWordRow(k, c.word, c.commitWeight, c.firstSeen, c.lastSeen) }
            .sortedBy { it.key }

    fun candidateSessionRows() =
        candidates
            .flatMap { (k, c) -> c.sessions.map { CandidateSessionRow(k, it.key, it.value) } }
            .sortedWith(compareBy({ it.key }, { it.sessionId }))
}
