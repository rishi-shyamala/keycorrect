package dev.keycorrect.core.learning.model

import dev.keycorrect.core.learning.LearningUpdate
import dev.keycorrect.core.learning.TouchSample

/*
 * Persistence boundary of the personal models (docs/PLAN.md §6.4). Everything is plain rows of
 * primitives and strings so a SQLCipher/Room store maps each list to one table. The store encrypts;
 * the model never touches I/O.
 */

/** One n-gram: [order] 1..3, [key] = words joined by a single space (already normalised). */
data class NgramRow(
    val order: Int,
    val key: String,
    val weight: Double,
    /** Active-clock millis at which [weight] was valid. */
    val lastActiveMillis: Long,
)

/** Correction memory cell; [bucket] "" is the bucket-free (global) cell. */
data class CorrectionRow(
    val typed: String,
    val target: String,
    val bucket: String,
    val score: Double,
    val lastActiveMillis: Long,
)

data class SuppressionRow(val typed: String, val target: String, val createdAtMillis: Long)

data class BlockRow(val word: String, val createdAtMillis: Long)

/**
 * A user dictionary word. [reason] = ordinal of [LearningUpdate.AddUserWord.Reason]. [removed] rows
 * are tombstones (the user removed the word; it is never auto-promoted again).
 */
data class UserWordRow(
    val key: String,
    val word: String,
    val reason: Int,
    val createdAtMillis: Long,
    val removed: Boolean,
)

data class CandidateWordRow(
    val key: String,
    val word: String,
    val commitWeight: Double,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
)

data class CandidateSessionRow(val key: String, val sessionId: Long, val weight: Double)

data class CasingRow(val key: String, val surface: String, val count: Double)

/** Touch statistics of one key: decayed weighted sums (mean = sumX / weight). */
data class TouchRow(
    val touchKey: String,
    /** Key character as a UTF-16 code. */
    val char: Int,
    /** Raw (undecayed) sample count, used for the evidence threshold. */
    val samples: Int,
    val weight: Double,
    val sumX: Double,
    val sumY: Double,
    val sumXX: Double,
    val sumYY: Double,
    val lastActiveMillis: Long,
)

data class TouchProfileRow(val touchKey: String, val words: Int)

/** Ranker weight [index]; [checkpointMillis] = -1 for the live weights, else a checkpoint time. */
data class RankerWeightRow(val checkpointMillis: Long, val index: Int, val value: Double)

/**
 * [kind] 0 = autocorrect outcome (a = 1 reverted / 0 kept), 1 = phrase feedback (a offered, b
 * accepted).
 */
data class OutcomeRow(val kind: Int, val timeMillis: Long, val a: Int, val b: Int)

data class SentenceRow(val sessionId: Long, val text: String, val timeMillis: Long)

/** A user-dictionary add the app has not yet confirmed to have stored. */
data class PendingAddRow(val word: String, val reason: Int)

/** Complete model state as rows. Lists are sorted so equal states compare equal. */
data class PersistedLearning(
    val formatVersion: Int = FORMAT_VERSION,
    val activeClockMillis: Long = 0,
    val lastWallMillis: Long = Long.MIN_VALUE,
    val rankerUpdates: Long = 0,
    val lastCheckpointMillis: Long = Long.MIN_VALUE,
    val ngrams: List<NgramRow> = emptyList(),
    val corrections: List<CorrectionRow> = emptyList(),
    val suppressions: List<SuppressionRow> = emptyList(),
    val blocks: List<BlockRow> = emptyList(),
    val userWords: List<UserWordRow> = emptyList(),
    val candidates: List<CandidateWordRow> = emptyList(),
    val candidateSessions: List<CandidateSessionRow> = emptyList(),
    val casing: List<CasingRow> = emptyList(),
    val touch: List<TouchRow> = emptyList(),
    val touchProfiles: List<TouchProfileRow> = emptyList(),
    val rankerWeights: List<RankerWeightRow> = emptyList(),
    val outcomes: List<OutcomeRow> = emptyList(),
    val sentences: List<SentenceRow> = emptyList(),
    val pendingAdds: List<PendingAddRow> = emptyList(),
) {
    companion object {
        const val FORMAT_VERSION = 1
    }
}

/** A journaled (additive) update, kept 24 h so "forget since T" can subtract it. */
data class JournalEntry(val timeMillis: Long, val update: LearningUpdate)

/**
 * Where learning lives between runs. Implemented by an encrypted store in the app and by
 * [InMemoryLearningStore] in tests. Journal payloads: use [JournalCodec.encode] /
 * [JournalCodec.decode].
 */
interface LearningStore {
    suspend fun load(): PersistedLearning?

    suspend fun save(state: PersistedLearning)

    suspend fun appendJournal(entries: List<JournalEntry>)

    /** Entries with `timeMillis >= timeMillis`, oldest first. */
    suspend fun journalSince(timeMillis: Long): List<JournalEntry>

    /** Delete entries with `timeMillis < timeMillis` (pass Long.MAX_VALUE to delete all). */
    suspend fun truncateJournalBefore(timeMillis: Long)

    /** Delete everything (state and journal). */
    suspend fun clear()
}

class InMemoryLearningStore : LearningStore {
    var state: PersistedLearning? = null
        private set

    private val journal = ArrayList<JournalEntry>()

    override suspend fun load(): PersistedLearning? = state

    override suspend fun save(state: PersistedLearning) {
        this.state = state
    }

    override suspend fun appendJournal(entries: List<JournalEntry>) {
        synchronized(journal) { journal.addAll(entries) }
    }

    override suspend fun journalSince(timeMillis: Long): List<JournalEntry> =
        synchronized(journal) { journal.filter { it.timeMillis >= timeMillis } }

    override suspend fun truncateJournalBefore(timeMillis: Long) {
        synchronized(journal) { journal.removeAll { it.timeMillis < timeMillis } }
    }

    override suspend fun clear() {
        state = null
        synchronized(journal) { journal.clear() }
    }

    fun journalSize(): Int = synchronized(journal) { journal.size }
}

/**
 * Stable text encoding of a [LearningUpdate] for the journal store.
 *
 * Format v1: fields joined by U+001F; field 0 = `v1`, field 1 = type tag (W word-used, N new-word,
 * A add-word, C correction, S suppress, B block, T touch, P preference, O outcome, F phrase, K
 * casing). Strings are percent-escaped for `%`, U+001F and U+001E. A list is one field: `count`
 * followed by its items, all joined by U+001E. Floats use [Float.toString] (exact round trip). A
 * touch sample item is `charCode,dx,dy`. [decode] returns null for unknown versions or corrupt
 * data, so an old app never crashes on a newer journal.
 */
object JournalCodec {
    const val VERSION = "v1"
    private const val FS = '\u001F'
    private const val RS = '\u001E'

    fun encode(update: LearningUpdate): String {
        val f = ArrayList<String>()
        f += VERSION
        when (update) {
            is LearningUpdate.WordUsed -> {
                f += "W"
                f += list(update.context)
                f += esc(update.word)
                f += update.weight.toString()
            }
            is LearningUpdate.NewWordEvidence -> {
                f += "N"
                f += esc(update.word)
                f += update.sessionId.toString()
                f += update.weight.toString()
            }
            is LearningUpdate.AddUserWord -> {
                f += "A"
                f += esc(update.word)
                f += update.reason.ordinal.toString()
            }
            is LearningUpdate.CorrectionEvidence -> {
                f += "C"
                f += esc(update.typed)
                f += esc(update.target)
                f += esc(update.contextBucket)
                f += update.delta.toString()
            }
            is LearningUpdate.SuppressCorrection -> {
                f += "S"
                f += esc(update.typed)
                f += esc(update.target)
            }
            is LearningUpdate.BlockWord -> {
                f += "B"
                f += esc(update.word)
            }
            is LearningUpdate.TouchEvidence -> {
                f += "T"
                f += esc(update.touchKey)
                f += listRaw(update.samples.map { "${it.char.code},${it.dx},${it.dy}" })
            }
            is LearningUpdate.Preference -> {
                f += "P"
                f += esc(update.typed)
                f += list(update.context)
                f += esc(update.preferred)
                f += esc(update.other)
                f += update.weight.toString()
            }
            is LearningUpdate.AutocorrectOutcome -> {
                f += "O"
                f += if (update.reverted) "1" else "0"
            }
            is LearningUpdate.PhraseFeedback -> {
                f += "F"
                f += update.offeredWords.toString()
                f += update.acceptedWords.toString()
            }
            is LearningUpdate.CasingEvidence -> {
                f += "K"
                f += esc(update.surface)
            }
        }
        return f.joinToString(FS.toString())
    }

    fun decode(text: String): LearningUpdate? =
        try {
            val f = text.split(FS)
            if (f.size < 2 || f[0] != VERSION) null else decodeFields(f)
        } catch (_: RuntimeException) {
            null
        }

    private fun decodeFields(f: List<String>): LearningUpdate? =
        when (f[1]) {
            "W" -> LearningUpdate.WordUsed(unlist(f[2]), unesc(f[3]), f[4].toFloat())
            "N" -> LearningUpdate.NewWordEvidence(unesc(f[2]), f[3].toLong(), f[4].toFloat())
            "A" ->
                LearningUpdate.AddUserWord(
                    unesc(f[2]),
                    LearningUpdate.AddUserWord.Reason.entries[f[3].toInt()],
                )
            "C" ->
                LearningUpdate.CorrectionEvidence(
                    unesc(f[2]),
                    unesc(f[3]),
                    unesc(f[4]),
                    f[5].toFloat(),
                )
            "S" -> LearningUpdate.SuppressCorrection(unesc(f[2]), unesc(f[3]))
            "B" -> LearningUpdate.BlockWord(unesc(f[2]))
            "T" ->
                LearningUpdate.TouchEvidence(
                    unesc(f[2]),
                    unlistRaw(f[3]).map {
                        val p = it.split(',')
                        TouchSample(p[0].toInt().toChar(), p[1].toFloat(), p[2].toFloat())
                    },
                )
            "P" ->
                LearningUpdate.Preference(
                    unesc(f[2]),
                    unlist(f[3]),
                    unesc(f[4]),
                    unesc(f[5]),
                    f[6].toFloat(),
                )
            "O" -> LearningUpdate.AutocorrectOutcome(f[2] == "1")
            "F" -> LearningUpdate.PhraseFeedback(f[2].toInt(), f[3].toInt())
            "K" -> LearningUpdate.CasingEvidence(unesc(f[2]))
            else -> null
        }

    private fun esc(s: String): String {
        if (s.none { it == '%' || it == FS || it == RS }) return s
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '%' -> sb.append("%25")
                FS -> sb.append("%1F")
                RS -> sb.append("%1E")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun unesc(s: String): String =
        if ('%' !in s) s
        else s.replace("%1F", FS.toString()).replace("%1E", RS.toString()).replace("%25", "%")

    private fun list(items: List<String>): String = listRaw(items.map(::esc))

    private fun listRaw(items: List<String>): String =
        (listOf(items.size.toString()) + items).joinToString(RS.toString())

    private fun unlist(s: String): List<String> = unlistRaw(s).map(::unesc)

    private fun unlistRaw(s: String): List<String> {
        val parts = s.split(RS)
        val n = parts[0].toInt()
        require(parts.size == n + 1)
        return parts.drop(1)
    }
}
