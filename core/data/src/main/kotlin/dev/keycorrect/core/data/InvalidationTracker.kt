package dev.keycorrect.core.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription

/** Per-table change notification (a Room-free "invalidation tracker"). Pure Kotlin. */
internal class InvalidationTracker {
    private val flows =
        DbTable.entries.associateWith {
            MutableSharedFlow<Unit>(
                replay = 0,
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        }

    fun notifyChanged(vararg tables: DbTable) {
        tables.forEach { flows.getValue(it).tryEmit(Unit) }
    }

    /**
     * Emits `query()` once the collector is subscribed, and again after every change to [table]
     * (conflated; consecutive equal results are suppressed).
     */
    fun <T> observe(table: DbTable, query: suspend () -> T): Flow<T> =
        flows
            .getValue(table)
            .onSubscription { emit(Unit) }
            .conflate()
            .map { query() }
            .distinctUntilChanged()
}
