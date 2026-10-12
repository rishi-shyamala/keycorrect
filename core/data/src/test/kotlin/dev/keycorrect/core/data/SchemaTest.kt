package dev.keycorrect.core.data

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class SchemaTest {
    private fun m(from: Int, to: Int) = Migration(from, to) {}

    @Test
    fun planChainsMigrationsInOrder() {
        val all = listOf(m(2, 3), m(1, 2), m(3, 4))
        val plan = Schema.plan(all, 1, 4)
        assertThat(plan.map { it.from to it.to }).containsExactly(1 to 2, 2 to 3, 3 to 4).inOrder()
    }

    @Test
    fun planForSameVersionIsEmpty() {
        assertThat(Schema.plan(emptyList(), 1, 1)).isEmpty()
    }

    @Test
    fun planFailsOnGap() {
        assertThrows(IllegalStateException::class.java) { Schema.plan(listOf(m(1, 2)), 1, 3) }
    }

    @Test
    fun planRejectsDowngrade() {
        assertThrows(IllegalArgumentException::class.java) { Schema.plan(listOf(m(1, 2)), 2, 1) }
    }

    @Test
    fun currentVersionIsReachableFromV1() {
        // Guards against bumping VERSION without adding the migration.
        assertThat(Schema.plan(Schema.MIGRATIONS, 1, Schema.VERSION).lastOrNull()?.to ?: 1)
            .isEqualTo(Schema.VERSION)
    }

    @Test
    fun rawKeySyntaxIsHexQuoted() {
        val key = byteArrayOf(0x00, 0x0F, 0xFF.toByte(), 0x7A)
        assertThat(String(KeyCorrectDatabase.toRawKeySyntax(key))).isEqualTo("x'000fff7a'")
    }

    @Test
    fun v1ToV2MigrationCreatesEveryLearningTable() {
        val statements = ArrayList<String>()
        val db =
            java.lang.reflect.Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java),
            ) { _, method, args ->
                if (method.name == "execSQL") statements += args[0] as String
                null
            } as androidx.sqlite.db.SupportSQLiteDatabase

        Schema.migrate(db, Schema.MIGRATIONS, 1, 2)

        val created =
            statements
                .filter { it.startsWith("CREATE TABLE ") }
                .map { it.removePrefix("CREATE TABLE ").substringBefore(' ') }
        assertThat(created).containsExactlyElementsIn(Schema.LEARNING_V2_TABLES)
        assertThat(statements.any { it.startsWith("CREATE INDEX") && "time_millis" in it }).isTrue()
        assertThat(statements.none { it.contains("user_words (") && "learning_" !in it }).isTrue()
    }

    @Test
    fun everyPersistedLearningListHasATable() {
        val lists =
            dev.keycorrect.core.learning.model.PersistedLearning::class.java.declaredFields.count {
                List::class.java.isAssignableFrom(it.type)
            }
        // Row tables + journal + meta.
        assertThat(Schema.LEARNING_TABLES).hasSize(lists + 2)
    }

    @Test
    fun versionIsFive() {
        assertThat(Schema.VERSION).isEqualTo(5)
        assertThat(Schema.plan(Schema.MIGRATIONS, 1, 2)).hasSize(1)
        assertThat(Schema.plan(Schema.MIGRATIONS, 3, 4)).hasSize(1)
        assertThat(Schema.plan(Schema.MIGRATIONS, 4, 5)).hasSize(1)
    }

    @Test
    fun v3ToV4MigrationCreatesOnlyTheEmojiTables() {
        val statements = ArrayList<String>()
        val db =
            java.lang.reflect.Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java),
            ) { _, method, args ->
                if (method.name == "execSQL") statements += args[0] as String
                null
            } as androidx.sqlite.db.SupportSQLiteDatabase

        Schema.migrate(db, Schema.MIGRATIONS, 3, 4)

        val created =
            statements
                .filter { it.startsWith("CREATE TABLE ") }
                .map { it.removePrefix("CREATE TABLE ").substringBefore(' ') }
        assertThat(created).containsExactlyElementsIn(Schema.EMOJI_TABLES)
        assertThat(statements.none { it.startsWith("DROP") || it.startsWith("ALTER") }).isTrue()
    }

    @Test
    fun v4ToV5MigrationCreatesOnlyTheClipsTable() {
        val statements = ArrayList<String>()
        val db =
            java.lang.reflect.Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java),
            ) { _, method, args ->
                if (method.name == "execSQL") statements += args[0] as String
                null
            } as androidx.sqlite.db.SupportSQLiteDatabase

        Schema.migrate(db, Schema.MIGRATIONS, 4, 5)

        val created =
            statements
                .filter { it.startsWith("CREATE TABLE ") }
                .map { it.removePrefix("CREATE TABLE ").substringBefore(' ') }
        assertThat(created).containsExactlyElementsIn(Schema.CLIP_TABLES)
        assertThat(statements.none { it.startsWith("DROP") || it.startsWith("ALTER") }).isTrue()
    }
}
