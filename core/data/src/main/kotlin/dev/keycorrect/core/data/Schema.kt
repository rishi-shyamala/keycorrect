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
    const val VERSION = 1

    /** Ordered migrations. The database is created at v1 and migrated up, so there is one path. */
    val MIGRATIONS: List<Migration> = emptyList()

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
