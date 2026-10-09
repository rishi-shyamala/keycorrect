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
}
