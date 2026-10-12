package dev.keycorrect.core.clipboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ClipPolicyTest {
    private val now = 10_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun clip(
        id: Long,
        age: Long = 0,
        pinned: Boolean = false,
        imageBytes: Long = 0,
    ) = ClipMeta(id, now - age, pinned, imageBytes)

    private fun delete(vararg clips: ClipMeta, max: Int = 30, budget: Long = 20L * 1024 * 1024) =
        ClipPolicy.idsToDelete(clips.toList(), now, max, 7 * day, budget)

    @Test
    fun emptyAndFreshClipsAreKept() {
        assertThat(delete()).isEmpty()
        assertThat(delete(clip(1), clip(2, age = day))).isEmpty()
    }

    @Test
    fun oldUnpinnedItemsAreDeleted() {
        val ids =
            delete(clip(1, age = 7 * day + 1), clip(2, age = 7 * day), clip(3, age = 30 * day))
        assertThat(ids).containsExactly(1L, 3L).inOrder()
    }

    @Test
    fun pinnedItemsNeverExpire() {
        val ids =
            delete(
                clip(1, age = 100 * day, pinned = true),
                clip(2, age = 8 * day, pinned = true),
            )
        assertThat(ids).isEmpty()
    }

    @Test
    fun itemsBeyondMaxAreDeletedOldestFirst() {
        val clips =
            (1L..5L).map { clip(it, age = (5 - it) * 1000) } // id 5 is oldest? no: id 5 age 0
        val ids =
            ClipPolicy.idsToDelete(
                clips,
                now,
                maxItems = 3,
                ttlMillis = 7 * day,
                imageBudgetBytes = 1L shl 30,
            )
        // newest = id 5 (age 0), then 4, 3; oldest are 2 and 1
        assertThat(ids).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun pinnedDoNotCountTowardMax() {
        val clips =
            listOf(
                clip(1, age = 5000, pinned = true),
                clip(2, age = 4000, pinned = true),
                clip(3, age = 3000),
                clip(4, age = 2000),
                clip(5, age = 1000),
            )
        val ids =
            ClipPolicy.idsToDelete(
                clips,
                now,
                maxItems = 2,
                ttlMillis = 7 * day,
                imageBudgetBytes = 1L shl 30,
            )
        assertThat(ids).containsExactly(3L)
    }

    @Test
    fun equalAgesBreakTiesByIdSoNewerIdSurvives() {
        val ids =
            ClipPolicy.idsToDelete(
                listOf(clip(1), clip(2), clip(3)),
                now,
                maxItems = 2,
                ttlMillis = 7 * day,
                imageBudgetBytes = 1L shl 30,
            )
        assertThat(ids).containsExactly(1L)
    }

    @Test
    fun maxItemsZeroDeletesAllUnpinned() {
        val ids =
            ClipPolicy.idsToDelete(
                listOf(clip(1), clip(2, pinned = true)),
                now,
                maxItems = 0,
                ttlMillis = 7 * day,
                imageBudgetBytes = 1L shl 30,
            )
        assertThat(ids).containsExactly(1L)
    }

    @Test
    fun imagesBeyondBudgetAreDeletedOldestFirst() {
        val ids =
            delete(
                clip(1, age = 3000, imageBytes = 400),
                clip(2, age = 2000, imageBytes = 400),
                clip(3, age = 1000, imageBytes = 400),
                budget = 1000,
            )
        assertThat(ids).containsExactly(1L)
    }

    @Test
    fun imageTrimmingKeepsTextClipsAndStopsAtBudget() {
        val ids =
            delete(
                clip(1, age = 5000),
                clip(2, age = 4000, imageBytes = 600),
                clip(3, age = 3000, imageBytes = 600),
                clip(4, age = 2000, imageBytes = 600),
                budget = 1300,
            )
        assertThat(ids).containsExactly(2L)
    }

    @Test
    fun pinnedImagesCountTowardBudgetButSurvive() {
        val ids =
            delete(
                clip(1, age = 3000, pinned = true, imageBytes = 800),
                clip(2, age = 2000, imageBytes = 300),
                clip(3, age = 1000, imageBytes = 100),
                budget = 1000,
            )
        assertThat(ids).containsExactly(2L)
    }

    @Test
    fun overBudgetWithOnlyPinnedImagesDeletesNothing() {
        assertThat(delete(clip(1, pinned = true, imageBytes = 5000), budget = 100)).isEmpty()
    }

    @Test
    fun ruleCombinationsDoNotDoubleCountDeletedItems() {
        val ids =
            delete(
                clip(1, age = 8 * day, imageBytes = 900), // expired by age; frees its bytes
                clip(2, age = 2000, imageBytes = 900),
                clip(3, age = 1000, imageBytes = 50),
                budget = 1000,
            )
        assertThat(ids).containsExactly(1L)
    }

    @Test
    fun resultIsDeterministicAndSortedRegardlessOfInputOrder() {
        val clips = (1L..6L).map { clip(it, age = it * day) }
        val a = ClipPolicy.idsToDelete(clips, now, maxItems = 2)
        val b = ClipPolicy.idsToDelete(clips.reversed(), now, maxItems = 2)
        assertThat(a).isEqualTo(b)
        assertThat(a).containsExactly(3L, 4L, 5L, 6L).inOrder()
    }

    @Test
    fun defaultsAreThirtyItemsSevenDaysTwentyMegabytes() {
        assertThat(ClipPolicy.DEFAULT_MAX_ITEMS).isEqualTo(30)
        assertThat(ClipPolicy.DEFAULT_TTL_MILLIS).isEqualTo(7 * day)
        assertThat(ClipPolicy.DEFAULT_IMAGE_BUDGET_BYTES).isEqualTo(20L * 1024 * 1024)
        assertThat(ClipPolicy.idsToDelete(listOf(clip(1, age = 8 * day)), now)).containsExactly(1L)
    }
}
