package dev.keycorrect.core.data

import androidx.sqlite.db.SupportSQLiteDatabase

/** Tables that stores can observe through [KeyCorrectDatabase] invalidation. */
enum class DbTable(internal val sqlName: String) {
    USER_WORDS("user_words"),
    SHORTCUTS("shortcuts"),
}

/** Upgrades the schema from [from] to [to] (always `from + 1` by convention). */
class Migration(val from: Int, val to: Int, val migrate: (SupportSQLiteDatabase) -> Unit)

internal object Schema {
    /** Current schema version. Bump together with a new entry in [MIGRATIONS]. */
    const val VERSION = 5

    /** Ordered migrations. The database is created at v1 and migrated up, so there is one path. */
    val MIGRATIONS: List<Migration> =
        listOf(
            Migration(1, 2, ::migrateV1ToV2),
            Migration(2, 3, ::migrateV2ToV3),
            Migration(3, 4, ::migrateV3ToV4),
            Migration(4, 5, ::migrateV4ToV5),
        )

    /** The frozen v1 DDL. Never edit; add a [Migration] instead. */
    fun createV1(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE user_words (
                word TEXT NOT NULL PRIMARY KEY,
                frequency INTEGER NOT NULL DEFAULT 128,
                locale TEXT,
                created_at INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0
            )
            """
                .trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE shortcuts (
                "trigger" TEXT NOT NULL PRIMARY KEY,
                expansion TEXT NOT NULL,
                mode TEXT NOT NULL CHECK (mode IN ('SUGGEST', 'AUTO_EXPAND')),
                created_at INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0
            )
            """
                .trimIndent()
        )
    }

    /**
     * v1 -> v2: the learning tables (one per `PersistedLearning` row list, plus journal and meta).
     * Frozen: never edit; add a [Migration] instead. Row tables have no primary key on purpose:
     * rowid order is the list order, which makes save/load a lossless round trip.
     */
    fun migrateV1ToV2(db: SupportSQLiteDatabase) {
        LEARNING_V2_DDL.forEach(db::execSQL)
    }

    /** v3: per-word / per-pair counts of kept undos (implicit-add and suppression thresholds). */
    fun migrateV2ToV3(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE learning_keeps (typed TEXT NOT NULL, corrected TEXT NOT NULL, " +
                "count INTEGER NOT NULL, first_millis INTEGER NOT NULL, last_millis INTEGER NOT NULL)"
        )
    }

    /** Tables created by [migrateV3ToV4]. */
    val EMOJI_TABLES: List<String> = listOf("emoji_recents", "emoji_variants")

    /** v4: emoji recents (one row per emoji value) and the chosen variant per grid emoji. */
    fun migrateV3ToV4(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE emoji_recents (emoji TEXT NOT NULL PRIMARY KEY, base TEXT NOT NULL, " +
                "last_used INTEGER NOT NULL, use_count INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX emoji_recents_last_used ON emoji_recents (last_used)")
        db.execSQL(
            "CREATE TABLE emoji_variants (base TEXT NOT NULL PRIMARY KEY, " +
                "variant TEXT NOT NULL, updated_at INTEGER NOT NULL)"
        )
    }

    /** Tables created by [migrateV4ToV5]. */
    val CLIP_TABLES: List<String> = listOf("clips")

    /**
     * v5: clipboard history. Image bytes are not stored here; `image_file` names an encrypted blob
     * file. `kind` is a [ClipKind] name; booleans are 0/1; `expires_at` is NULL for no expiry.
     */
    fun migrateV4ToV5(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE clips (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "kind TEXT NOT NULL CHECK (kind IN ('Text', 'Image')), text TEXT, " +
                "mime_type TEXT, image_file TEXT, image_bytes INTEGER NOT NULL DEFAULT 0, " +
                "created_at INTEGER NOT NULL, pinned INTEGER NOT NULL DEFAULT 0, " +
                "expires_at INTEGER, sensitive INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX clips_created_at ON clips (created_at)")
    }

    /** Every learning table in the current schema (v2 tables plus later additions). */
    val LEARNING_TABLES: List<String>
        get() = LEARNING_V2_TABLES + "learning_keeps"

    /** Names of the tables created by [migrateV1ToV2], row tables first. */
    val LEARNING_V2_TABLES: List<String> =
        listOf(
            "learning_ngrams",
            "learning_corrections",
            "learning_suppressions",
            "learning_blocks",
            "learning_user_words",
            "learning_candidates",
            "learning_candidate_sessions",
            "learning_casing",
            "learning_touch",
            "learning_touch_profiles",
            "learning_ranker_weights",
            "learning_outcomes",
            "learning_sentences",
            "learning_pending_adds",
            "learning_journal",
            "learning_meta",
        )

    private val LEARNING_V2_DDL: List<String> =
        listOf(
            "CREATE TABLE learning_ngrams (ord INTEGER NOT NULL, key TEXT NOT NULL, " +
                "weight REAL NOT NULL, last_active INTEGER NOT NULL)",
            "CREATE TABLE learning_corrections (typed TEXT NOT NULL, target TEXT NOT NULL, " +
                "bucket TEXT NOT NULL, score REAL NOT NULL, last_active INTEGER NOT NULL)",
            "CREATE TABLE learning_suppressions (typed TEXT NOT NULL, target TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL)",
            "CREATE TABLE learning_blocks (word TEXT NOT NULL, created_at INTEGER NOT NULL)",
            "CREATE TABLE learning_user_words (key TEXT NOT NULL, word TEXT NOT NULL, " +
                "reason INTEGER NOT NULL, created_at INTEGER NOT NULL, removed INTEGER NOT NULL)",
            "CREATE TABLE learning_candidates (key TEXT NOT NULL, word TEXT NOT NULL, " +
                "commit_weight REAL NOT NULL, first_seen INTEGER NOT NULL, " +
                "last_seen INTEGER NOT NULL)",
            "CREATE TABLE learning_candidate_sessions (key TEXT NOT NULL, " +
                "session_id INTEGER NOT NULL, weight REAL NOT NULL)",
            "CREATE TABLE learning_casing (key TEXT NOT NULL, surface TEXT NOT NULL, " +
                "count REAL NOT NULL)",
            "CREATE TABLE learning_touch (touch_key TEXT NOT NULL, char_code INTEGER NOT NULL, " +
                "samples INTEGER NOT NULL, weight REAL NOT NULL, sum_x REAL NOT NULL, " +
                "sum_y REAL NOT NULL, sum_xx REAL NOT NULL, sum_yy REAL NOT NULL, " +
                "last_active INTEGER NOT NULL)",
            "CREATE TABLE learning_touch_profiles (touch_key TEXT NOT NULL, words INTEGER NOT NULL)",
            "CREATE TABLE learning_ranker_weights (checkpoint_millis INTEGER NOT NULL, " +
                "idx INTEGER NOT NULL, value REAL NOT NULL)",
            "CREATE TABLE learning_outcomes (kind INTEGER NOT NULL, time_millis INTEGER NOT NULL, " +
                "a INTEGER NOT NULL, b INTEGER NOT NULL)",
            "CREATE TABLE learning_sentences (session_id INTEGER NOT NULL, text TEXT NOT NULL, " +
                "time_millis INTEGER NOT NULL)",
            "CREATE TABLE learning_pending_adds (word TEXT NOT NULL, reason INTEGER NOT NULL)",
            "CREATE TABLE learning_journal (time_millis INTEGER NOT NULL, payload TEXT NOT NULL)",
            "CREATE INDEX learning_journal_time ON learning_journal (time_millis)",
            "CREATE TABLE learning_meta (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
        )

    /**
     * Picks the chain of migrations leading from [from] to [to].
     *
     * @throws IllegalStateException when the chain has a gap.
     */
    fun plan(migrations: List<Migration>, from: Int, to: Int): List<Migration> {
        require(from <= to) { "Downgrade $from -> $to is not supported" }
        val steps = ArrayList<Migration>()
        var version = from
        while (version < to) {
            val next =
                migrations.firstOrNull { it.from == version && it.to <= to }
                    ?: error("No migration from schema version $version towards $to")
            steps += next
            version = next.to
        }
        return steps
    }

    fun migrate(db: SupportSQLiteDatabase, migrations: List<Migration>, from: Int, to: Int) {
        plan(migrations, from, to).forEach { it.migrate(db) }
    }
}
