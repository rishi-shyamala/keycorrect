package dev.keycorrect.core.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class InvalidationTrackerTest {
    @Test
    fun emitsInitialThenRequeriesOnEachChangeOfTheSameTable() =
        runTest(UnconfinedTestDispatcher()) {
            val tracker = InvalidationTracker()
            var value = 0
            val seen = mutableListOf<Int>()
            val job = launch {
                tracker.observe(DbTable.USER_WORDS) { value }.take(3).toList(seen)
            }
            value = 1
            tracker.notifyChanged(DbTable.USER_WORDS)
            value = 2
            tracker.notifyChanged(DbTable.USER_WORDS)
            job.join()
            assertThat(seen).containsExactly(0, 1, 2).inOrder()
        }

    @Test
    fun ignoresOtherTablesAndEqualResults() =
        runTest(UnconfinedTestDispatcher()) {
            val tracker = InvalidationTracker()
            var value = 0
            var queries = 0
            val seen = mutableListOf<Int>()
            val job = launch {
                tracker
                    .observe(DbTable.SHORTCUTS) {
                        queries++
                        value
                    }
                    .take(2)
                    .toList(seen)
            }
            tracker.notifyChanged(DbTable.USER_WORDS)
            assertThat(queries).isEqualTo(1)
            tracker.notifyChanged(DbTable.SHORTCUTS) // same result: suppressed
            value = 5
            tracker.notifyChanged(DbTable.SHORTCUTS)
            job.join()
            assertThat(seen).containsExactly(0, 5).inOrder()
        }
}
