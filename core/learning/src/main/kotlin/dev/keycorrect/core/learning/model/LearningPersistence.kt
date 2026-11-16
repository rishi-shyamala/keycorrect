package dev.keycorrect.core.learning.model

import kotlin.coroutines.cancellation.CancellationException

/**
 * Glue between a [PersonalModel] (learning thread) and a [LearningStore]. Call these from the
 * learning coroutine only.
 */
class LearningPersistence(
    private val model: PersonalModel,
    private val store: LearningStore,
    /** Receives a short, content-free warning (an exception TYPE name, never a message). */
    private val onWarning: (String) -> Unit = {},
) {
    /**
     * Loads state and the retained journal; returns false if the store was empty. Sentences past
     * their TTL are dropped on the way in, and the store is rewritten at once if that removed any
     * (expired text must not linger in the database).
     */
    suspend fun restore(nowMillis: Long): Boolean {
        val state = store.load() ?: return false
        val before = state.sentences.size
        model.import(
            state,
            store.journalSince(nowMillis - model.config.journalRetentionMillis),
            nowMillis,
        )
        if (model.sentences.size() < before) {
            // The model is already restored and the pruned text is gone from memory; a failed
            // rewrite only leaves the expired rows in the store until the next flush persists the
            // pruned state. Not a restore failure. Cancellation still propagates.
            try {
                store.save(model.export())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onWarning("Rewriting pruned learning data failed: ${e.javaClass.name}")
            }
        }
        return true
    }

    /**
     * Persists the full state and the new journal entries, and trims the journal to its retention
     * and the sentence buffer to its TTL. One atomic [LearningStore.commit]; if it fails, or the
     * flush is cancelled before it finished, the entries go back to the model's queue (the next
     * flush writes them) and the exception propagates.
     */
    suspend fun flush(nowMillis: Long) {
        val entries = model.drainJournalForPersistence()
        try {
            model.sentences.prune(nowMillis)
            store.commit(model.export(), entries, nowMillis - model.config.journalRetentionMillis)
        } catch (e: Throwable) {
            // Cancellation included: the drained entries must not be lost with an abandoned flush.
            model.requeueJournal(entries)
            throw e
        }
    }

    /**
     * Forgets since [timeMillis] and rewrites state and journal so the store matches, atomically.
     */
    suspend fun forgetSince(timeMillis: Long, nowMillis: Long): ForgetResult {
        val result = model.forgetSince(timeMillis, nowMillis)
        rewrite()
        return result
    }

    /** Forgets a learned correction and rewrites the store's journal and state to match. */
    suspend fun forgetCorrection(typed: String, target: String, nowMillis: Long): Boolean {
        val changed = model.forgetCorrection(typed, target, nowMillis)
        if (changed) rewrite()
        return changed
    }

    private suspend fun rewrite() {
        val remaining = model.journalSnapshot()
        model.drainJournalForPersistence() // the full journal is written below
        store.replaceAll(model.export(), remaining)
    }

    suspend fun clearAll() {
        model.clear()
        store.clear()
    }
}
