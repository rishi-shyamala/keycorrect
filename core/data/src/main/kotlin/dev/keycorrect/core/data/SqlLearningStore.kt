package dev.keycorrect.core.data

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import dev.keycorrect.core.learning.model.BlockRow
import dev.keycorrect.core.learning.model.CandidateSessionRow
import dev.keycorrect.core.learning.model.CandidateWordRow
import dev.keycorrect.core.learning.model.CasingRow
import dev.keycorrect.core.learning.model.CorrectionRow
import dev.keycorrect.core.learning.model.JournalCodec
import dev.keycorrect.core.learning.model.JournalEntry
import dev.keycorrect.core.learning.model.KeepRow
import dev.keycorrect.core.learning.model.LearningStore
import dev.keycorrect.core.learning.model.NgramRow
import dev.keycorrect.core.learning.model.OutcomeRow
import dev.keycorrect.core.learning.model.PendingAddRow
import dev.keycorrect.core.learning.model.PersistedLearning
import dev.keycorrect.core.learning.model.RankerWeightRow
import dev.keycorrect.core.learning.model.SentenceRow
import dev.keycorrect.core.learning.model.SuppressionRow
import dev.keycorrect.core.learning.model.TouchProfileRow
import dev.keycorrect.core.learning.model.TouchRow
import dev.keycorrect.core.learning.model.UserWordRow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [LearningStore] on the encrypted [KeyCorrectDatabase] (schema v2, see [Schema.migrateV1ToV2]).
 *
 * [save] replaces every learning table in one transaction (DELETE + prepared-statement inserts), so
 * a crash mid-save leaves the previous state intact. [commit] (state + new journal entries + trim)
 * and [replaceAll] (state + whole journal, used by the forget operations) are each one transaction
 * too, so a crash can never leave state and journal out of step. Row tables keep list order (rowid
 * order), so `load()` returns exactly what was saved. Sentence-buffer rows are user text: they live
 * only in the SQLCipher database and are removed by [clear]. [clear] never touches `user_words` or
 * `shortcuts`.
 *
 * All work runs on the dispatcher injected into [KeyCorrectDatabase]. Calls are serialised so a
 * [load] never observes a half-finished [save].
 */
class SqlLearningStore(private val database: KeyCorrectDatabase) : LearningStore {
    private val lock = Mutex()

    override suspend fun load(): PersistedLearning? = lock.withLock {
        database.read { db -> readState(db) }
    }

    override suspend fun save(state: PersistedLearning) {
        lock.withLock { database.transaction { db -> writeState(db, state) } }
    }

    override suspend fun appendJournal(entries: List<JournalEntry>) {
        if (entries.isEmpty()) return
        lock.withLock { database.transaction { db -> insertJournal(db, entries) } }
    }

    override suspend fun commit(
        state: PersistedLearning,
        entries: List<JournalEntry>,
        truncateBefore: Long,
    ) {
        lock.withLock {
            database.transaction { db ->
                writeState(db, state)
                insertJournal(db, entries)
                deleteJournalBefore(db, truncateBefore)
            }
        }
    }

    override suspend fun replaceAll(state: PersistedLearning, journal: List<JournalEntry>) {
        lock.withLock {
            database.transaction { db ->
                writeState(db, state)
                deleteJournalBefore(db, Long.MAX_VALUE)
                insertJournal(db, journal)
            }
        }
    }

    private fun insertJournal(db: SupportSQLiteDatabase, entries: List<JournalEntry>) {
        if (entries.isEmpty()) return
        db.compileStatement("INSERT INTO learning_journal (time_millis, payload) VALUES (?, ?)")
            .use { st ->
                for (e in entries) {
                    st.bindLong(1, e.timeMillis)
                    st.bindString(2, JournalCodec.encode(e.update))
                    st.executeInsert()
                }
            }
    }

    private fun deleteJournalBefore(db: SupportSQLiteDatabase, timeMillis: Long) {
        db.compileStatement("DELETE FROM learning_journal WHERE time_millis < ?").use { st ->
            st.bindLong(1, timeMillis)
            st.executeUpdateDelete()
        }
    }

    override suspend fun journalSince(timeMillis: Long): List<JournalEntry> = lock.withLock {
        database.read { db ->
            db.query(
                    "SELECT time_millis, payload FROM learning_journal WHERE time_millis >= ? " +
                        "ORDER BY time_millis, rowid",
                    arrayOf<Any?>(timeMillis),
                )
                .use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            // Undecodable (newer/corrupt) payloads are skipped, never fatal.
                            val update = JournalCodec.decode(c.getString(1)) ?: continue
                            add(JournalEntry(c.getLong(0), update))
                        }
                    }
                }
        }
    }

    override suspend fun truncateJournalBefore(timeMillis: Long) {
        lock.withLock {
            database.transaction { db -> deleteJournalBefore(db, timeMillis) }
        }
    }

    override suspend fun clear() {
        lock.withLock { database.transaction { db -> deleteAll(db) } }
    }

    private fun deleteAll(db: SupportSQLiteDatabase) {
        Schema.LEARNING_TABLES.forEach { db.execSQL("DELETE FROM $it") }
    }

    // ---- write ----

    private fun writeState(db: SupportSQLiteDatabase, s: PersistedLearning) {
        // The journal is not part of the state snapshot; keep it across saves.
        Schema.LEARNING_TABLES.filter { it != "learning_journal" }
            .forEach { db.execSQL("DELETE FROM $it") }

        db.insertAll(
            "INSERT INTO learning_ngrams (ord, key, weight, last_active) VALUES (?, ?, ?, ?)",
            s.ngrams,
        ) { st, r ->
            st.bindLong(1, r.order.toLong())
            st.bindString(2, r.key)
            st.bindDouble(3, r.weight)
            st.bindLong(4, r.lastActiveMillis)
        }
        db.insertAll(
            "INSERT INTO learning_corrections (typed, target, bucket, score, last_active) " +
                "VALUES (?, ?, ?, ?, ?)",
            s.corrections,
        ) { st, r ->
            st.bindString(1, r.typed)
            st.bindString(2, r.target)
            st.bindString(3, r.bucket)
            st.bindDouble(4, r.score)
            st.bindLong(5, r.lastActiveMillis)
        }
        db.insertAll(
            "INSERT INTO learning_suppressions (typed, target, created_at) VALUES (?, ?, ?)",
            s.suppressions,
        ) { st, r ->
            st.bindString(1, r.typed)
            st.bindString(2, r.target)
            st.bindLong(3, r.createdAtMillis)
        }
        db.insertAll("INSERT INTO learning_blocks (word, created_at) VALUES (?, ?)", s.blocks) {
            st,
            r ->
            st.bindString(1, r.word)
            st.bindLong(2, r.createdAtMillis)
        }
        db.insertAll(
            "INSERT INTO learning_user_words (key, word, reason, created_at, removed) " +
                "VALUES (?, ?, ?, ?, ?)",
            s.userWords,
        ) { st, r ->
            st.bindString(1, r.key)
            st.bindString(2, r.word)
            st.bindLong(3, r.reason.toLong())
            st.bindLong(4, r.createdAtMillis)
            // Flags: bit 0 removed (tombstone), bit 1 pre-existing in the app's user dictionary.
            st.bindLong(5, (if (r.removed) 1L else 0L) or (if (r.preExisting) 2L else 0L))
        }
        db.insertAll(
            "INSERT INTO learning_candidates " +
                "(key, word, commit_weight, first_seen, last_seen) VALUES (?, ?, ?, ?, ?)",
            s.candidates,
        ) { st, r ->
            st.bindString(1, r.key)
            st.bindString(2, r.word)
            st.bindDouble(3, r.commitWeight)
            st.bindLong(4, r.firstSeenMillis)
            st.bindLong(5, r.lastSeenMillis)
        }
        db.insertAll(
            "INSERT INTO learning_candidate_sessions (key, session_id, weight) VALUES (?, ?, ?)",
            s.candidateSessions,
        ) { st, r ->
            st.bindString(1, r.key)
            st.bindLong(2, r.sessionId)
            st.bindDouble(3, r.weight)
        }
        db.insertAll(
            "INSERT INTO learning_keeps (typed, corrected, count, first_millis, last_millis) " +
                "VALUES (?, ?, ?, ?, ?)",
            s.keeps,
        ) { st, r ->
            st.bindString(1, r.typed)
            st.bindString(2, r.corrected)
            st.bindLong(3, r.count.toLong())
            st.bindLong(4, r.firstMillis)
            st.bindLong(5, r.lastMillis)
        }
        db.insertAll(
            "INSERT INTO learning_casing (key, surface, count) VALUES (?, ?, ?)",
            s.casing,
        ) { st, r ->
            st.bindString(1, r.key)
            st.bindString(2, r.surface)
            st.bindDouble(3, r.count)
        }
        db.insertAll(
            "INSERT INTO learning_touch (touch_key, char_code, samples, weight, sum_x, sum_y, " +
                "sum_xx, sum_yy, last_active) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            s.touch,
        ) { st, r ->
            st.bindString(1, r.touchKey)
            st.bindLong(2, r.char.toLong())
            st.bindLong(3, r.samples.toLong())
            st.bindDouble(4, r.weight)
            st.bindDouble(5, r.sumX)
            st.bindDouble(6, r.sumY)
            st.bindDouble(7, r.sumXX)
            st.bindDouble(8, r.sumYY)
            st.bindLong(9, r.lastActiveMillis)
        }
        db.insertAll(
            "INSERT INTO learning_touch_profiles (touch_key, words) VALUES (?, ?)",
            s.touchProfiles,
        ) { st, r ->
            st.bindString(1, r.touchKey)
            st.bindLong(2, r.words.toLong())
        }
        db.insertAll(
            "INSERT INTO learning_ranker_weights (checkpoint_millis, idx, value) VALUES (?, ?, ?)",
            s.rankerWeights,
        ) { st, r ->
            st.bindLong(1, r.checkpointMillis)
            st.bindLong(2, r.index.toLong())
            st.bindDouble(3, r.value)
        }
        db.insertAll(
            "INSERT INTO learning_outcomes (kind, time_millis, a, b) VALUES (?, ?, ?, ?)",
            s.outcomes,
        ) { st, r ->
            st.bindLong(1, r.kind.toLong())
            st.bindLong(2, r.timeMillis)
            st.bindLong(3, r.a.toLong())
            st.bindLong(4, r.b.toLong())
        }
        db.insertAll(
            "INSERT INTO learning_sentences (session_id, text, time_millis) VALUES (?, ?, ?)",
            s.sentences,
        ) { st, r ->
            st.bindLong(1, r.sessionId)
            st.bindString(2, r.text)
            st.bindLong(3, r.timeMillis)
        }
        db.insertAll(
            "INSERT INTO learning_pending_adds (word, reason) VALUES (?, ?)",
            s.pendingAdds,
        ) { st, r ->
            st.bindString(1, r.word)
            st.bindLong(2, r.reason.toLong())
        }

        val meta =
            listOf(
                META_FORMAT_VERSION to s.formatVersion.toString(),
                META_ACTIVE_CLOCK to s.activeClockMillis.toString(),
                META_LAST_WALL to s.lastWallMillis.toString(),
                META_RANKER_UPDATES to s.rankerUpdates.toString(),
                META_LAST_CHECKPOINT to s.lastCheckpointMillis.toString(),
            )
        db.insertAll("INSERT INTO learning_meta (key, value) VALUES (?, ?)", meta) { st, r ->
            st.bindString(1, r.first)
            st.bindString(2, r.second)
        }
    }

    private inline fun <T> SupportSQLiteDatabase.insertAll(
        sql: String,
        rows: List<T>,
        bind: (SupportSQLiteStatement, T) -> Unit,
    ) {
        if (rows.isEmpty()) return
        compileStatement(sql).use { st ->
            for (r in rows) {
                bind(st, r)
                st.executeInsert()
            }
        }
    }

    // ---- read ----

    private fun readState(db: SupportSQLiteDatabase): PersistedLearning? {
        val meta = HashMap<String, String>()
        db.query("SELECT key, value FROM learning_meta").use { c ->
            while (c.moveToNext()) meta[c.getString(0)] = c.getString(1)
        }
        if (META_FORMAT_VERSION !in meta) return null // never saved
        val d = PersistedLearning()
        return PersistedLearning(
            formatVersion = meta.getValue(META_FORMAT_VERSION).toInt(),
            activeClockMillis = meta[META_ACTIVE_CLOCK]?.toLong() ?: d.activeClockMillis,
            lastWallMillis = meta[META_LAST_WALL]?.toLong() ?: d.lastWallMillis,
            rankerUpdates = meta[META_RANKER_UPDATES]?.toLong() ?: d.rankerUpdates,
            lastCheckpointMillis = meta[META_LAST_CHECKPOINT]?.toLong() ?: d.lastCheckpointMillis,
            ngrams =
                db.readAll("SELECT ord, key, weight, last_active FROM learning_ngrams") {
                    NgramRow(it.getInt(0), it.getString(1), it.getDouble(2), it.getLong(3))
                },
            corrections =
                db.readAll(
                    "SELECT typed, target, bucket, score, last_active FROM learning_corrections"
                ) {
                    CorrectionRow(
                        it.getString(0),
                        it.getString(1),
                        it.getString(2),
                        it.getDouble(3),
                        it.getLong(4),
                    )
                },
            suppressions =
                db.readAll("SELECT typed, target, created_at FROM learning_suppressions") {
                    SuppressionRow(it.getString(0), it.getString(1), it.getLong(2))
                },
            blocks =
                db.readAll("SELECT word, created_at FROM learning_blocks") {
                    BlockRow(it.getString(0), it.getLong(1))
                },
            userWords =
                db.readAll(
                    "SELECT key, word, reason, created_at, removed FROM learning_user_words"
                ) {
                    UserWordRow(
                        it.getString(0),
                        it.getString(1),
                        it.getInt(2),
                        it.getLong(3),
                        it.getInt(4) and 1 != 0,
                        it.getInt(4) and 2 != 0,
                    )
                },
            candidates =
                db.readAll(
                    "SELECT key, word, commit_weight, first_seen, last_seen FROM learning_candidates"
                ) {
                    CandidateWordRow(
                        it.getString(0),
                        it.getString(1),
                        it.getDouble(2),
                        it.getLong(3),
                        it.getLong(4),
                    )
                },
            candidateSessions =
                db.readAll("SELECT key, session_id, weight FROM learning_candidate_sessions") {
                    CandidateSessionRow(it.getString(0), it.getLong(1), it.getDouble(2))
                },
            keeps =
                db.readAll(
                    "SELECT typed, corrected, count, first_millis, last_millis FROM learning_keeps"
                ) {
                    KeepRow(
                        it.getString(0),
                        it.getString(1),
                        it.getInt(2),
                        it.getLong(3),
                        it.getLong(4),
                    )
                },
            casing =
                db.readAll("SELECT key, surface, count FROM learning_casing") {
                    CasingRow(it.getString(0), it.getString(1), it.getDouble(2))
                },
            touch =
                db.readAll(
                    "SELECT touch_key, char_code, samples, weight, sum_x, sum_y, sum_xx, sum_yy, " +
                        "last_active FROM learning_touch"
                ) {
                    TouchRow(
                        it.getString(0),
                        it.getInt(1),
                        it.getInt(2),
                        it.getDouble(3),
                        it.getDouble(4),
                        it.getDouble(5),
                        it.getDouble(6),
                        it.getDouble(7),
                        it.getLong(8),
                    )
                },
            touchProfiles =
                db.readAll("SELECT touch_key, words FROM learning_touch_profiles") {
                    TouchProfileRow(it.getString(0), it.getInt(1))
                },
            rankerWeights =
                db.readAll("SELECT checkpoint_millis, idx, value FROM learning_ranker_weights") {
                    RankerWeightRow(it.getLong(0), it.getInt(1), it.getDouble(2))
                },
            outcomes =
                db.readAll("SELECT kind, time_millis, a, b FROM learning_outcomes") {
                    OutcomeRow(it.getInt(0), it.getLong(1), it.getInt(2), it.getInt(3))
                },
            sentences =
                db.readAll("SELECT session_id, text, time_millis FROM learning_sentences") {
                    SentenceRow(it.getLong(0), it.getString(1), it.getLong(2))
                },
            pendingAdds =
                db.readAll("SELECT word, reason FROM learning_pending_adds") {
                    PendingAddRow(it.getString(0), it.getInt(1))
                },
        )
    }

    /**
     * Reads `SELECT cols FROM table` in saved list order (rowid), [PAGE] rows per query. Paging by
     * rowid matters: a single cursor over 100k+ rows re-steps from the start on every cursor-window
     * refill, which made a 120k-row table take seconds instead of tens of milliseconds.
     */
    private inline fun <T> SupportSQLiteDatabase.readAll(
        sql: String,
        map: (Cursor) -> T,
    ): List<T> {
        val columns = sql.substringBefore(" FROM ")
        val table = sql.substringAfter(" FROM ")
        val page = "$columns, rowid FROM $table WHERE rowid > ? ORDER BY rowid LIMIT $PAGE"
        val out = ArrayList<T>()
        var after = Long.MIN_VALUE
        while (true) {
            var n = 0
            query(page, arrayOf<Any?>(after)).use { c ->
                val rowid = c.columnCount - 1
                while (c.moveToNext()) {
                    out.add(map(c))
                    after = c.getLong(rowid)
                    n++
                }
            }
            if (n < PAGE) return out
        }
    }

    private companion object {
        const val PAGE = 4096
        const val META_FORMAT_VERSION = "format_version"
        const val META_ACTIVE_CLOCK = "active_clock_millis"
        const val META_LAST_WALL = "last_wall_millis"
        const val META_RANKER_UPDATES = "ranker_updates"
        const val META_LAST_CHECKPOINT = "last_checkpoint_millis"
    }
}
