package dev.keycorrect.feature.keyboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InflateCoordinatorTest {
    private class FakeScheduler : TimeoutScheduler {
        class Task(val delayMs: Long, val action: Runnable, var cancelled: Boolean = false)

        val tasks = mutableListOf<Task>()

        override fun schedule(delayMs: Long, action: Runnable): TimeoutHandle {
            val task = Task(delayMs, action)
            tasks += task
            return TimeoutHandle { task.cancelled = true }
        }

        /** Runs every scheduled, not-cancelled task once. */
        fun fire() {
            val pending = tasks.filter { !it.cancelled }
            tasks.clear()
            pending.forEach { it.action.run() }
        }

        val pendingCount
            get() = tasks.count { !it.cancelled }
    }

    private val scheduler = FakeScheduler()
    private val published = mutableListOf<List<String>>()
    private val coordinator = InflateCoordinator<String>(300L, scheduler) { published += it }

    @Test
    fun publishesOnceWhenAllSlotsResolveInPlatformOrder() {
        val batch = coordinator.begin(3)
        batch.resolve(2, "c")
        batch.resolve(0, "a")
        assertThat(published).isEmpty()
        batch.resolve(1, "b")
        assertThat(published).containsExactly(listOf("a", "b", "c"))
        assertThat(scheduler.pendingCount).isEqualTo(0)
    }

    @Test
    fun partialListIsNeverPublishedBeforeTheTimeout() {
        val batch = coordinator.begin(3)
        batch.resolve(0, "a")
        batch.resolve(1, "b")
        assertThat(published).isEmpty()
        assertThat(scheduler.tasks.single().delayMs).isEqualTo(300L)
    }

    @Test
    fun nullViewsAreSkipped() {
        val batch = coordinator.begin(3)
        batch.resolve(0, "a")
        batch.resolve(1, null)
        batch.resolve(2, "c")
        assertThat(published).containsExactly(listOf("a", "c"))
    }

    @Test
    fun allNullPublishesEmptyList() {
        val batch = coordinator.begin(2)
        batch.resolve(0, null)
        batch.resolve(1, null)
        assertThat(published).containsExactly(emptyList<String>())
        assertThat(scheduler.tasks).isEmpty()
    }

    @Test
    fun timeoutPublishesWhatIsReadyThenLateItemsRepublish() {
        val batch = coordinator.begin(3)
        batch.resolve(1, "b")
        batch.resolve(0, "a")
        scheduler.fire()
        assertThat(published).containsExactly(listOf("a", "b"))
        batch.resolve(2, "c")
        assertThat(published).containsExactly(listOf("a", "b"), listOf("a", "b", "c")).inOrder()
    }

    @Test
    fun timeoutIsStartedOnlyByFirstViewAndOnlyOnce() {
        val batch = coordinator.begin(4)
        batch.resolve(0, null)
        assertThat(scheduler.tasks).isEmpty()
        batch.resolve(1, "b")
        batch.resolve(2, "c")
        assertThat(scheduler.tasks).hasSize(1)
    }

    @Test
    fun timeoutIsCancelledWhenBatchCompletesFirst() {
        val batch = coordinator.begin(2)
        batch.resolve(0, "a")
        batch.resolve(1, "b")
        scheduler.fire()
        assertThat(published).containsExactly(listOf("a", "b"))
    }

    @Test
    fun lateNullAfterTimeoutDoesNotRepublish() {
        val batch = coordinator.begin(2)
        batch.resolve(0, "a")
        scheduler.fire()
        batch.resolve(1, null)
        assertThat(published).containsExactly(listOf("a"))
    }

    @Test
    fun callbacksAfterCancelAreDropped() {
        val batch = coordinator.begin(2)
        batch.resolve(0, "a")
        coordinator.cancel()
        batch.resolve(1, "b")
        scheduler.fire()
        assertThat(published).isEmpty()
        assertThat(scheduler.pendingCount).isEqualTo(0)
    }

    @Test
    fun supersededBatchIsIgnored() {
        val old = coordinator.begin(2)
        old.resolve(0, "old-a")
        val fresh = coordinator.begin(1)
        old.resolve(1, "old-b")
        fresh.resolve(0, "new")
        scheduler.fire()
        assertThat(published).containsExactly(listOf("new"))
    }

    @Test
    fun resolvingASlotTwiceKeepsTheFirstValue() {
        val batch = coordinator.begin(2)
        batch.resolve(0, "a")
        batch.resolve(0, "x")
        batch.resolve(1, "b")
        assertThat(published).containsExactly(listOf("a", "b"))
    }

    @Test
    fun emptyBatchPublishesNothing() {
        coordinator.begin(0)
        scheduler.fire()
        assertThat(published).isEmpty()
    }
}
