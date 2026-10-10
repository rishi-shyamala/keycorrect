package dev.keycorrect.ime

import dev.keycorrect.core.decoder.ClassicEngine
import dev.keycorrect.core.engineapi.ComposingInput
import dev.keycorrect.core.engineapi.FieldClass
import dev.keycorrect.core.engineapi.KeyLayoutModel
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.TypingContext
import dev.keycorrect.core.learning.RankingFeatures

/**
 * The model's ranking feature provider, backed by the engine: the features `ClassicEngine` computes
 * for [candidate] when [typed] is decoded after [context]. Learning thread only.
 *
 * The engine's lock is shared with typing decodes, so this uses the non-blocking `tryFeaturesFor`:
 * typing never waits for it. A call is skipped (null, and [onDeferred] so the model's retry is
 * scheduled for later) while the decoder is busy, the engine lock is held, or the [budget] for the
 * current second is used up; see [PredictionHost]. An engine failure is null too (retried a few
 * times, then dropped).
 *
 * The decode is built from the event's context with no touches (the event does not carry the raw
 * taps), so the spatial feature is the literal's neutral value; the `field` argument (the field
 * class of the event the preference came from) supplies the correction-memory bucket.
 */
internal class EngineRankingFeatures(
    private val engine: ClassicEngine,
    private val activity: DecodeActivity,
    private val budget: DecodeBudget,
    private val onDeferred: () -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
) {
    fun features(
        typed: String,
        context: List<String>,
        candidate: String,
        field: FieldClass,
    ): RankingFeatures? {
        if (activity.busy() || !budget.tryAcquire()) {
            onDeferred()
            return null
        }
        val before = context.joinToString(" ", postfix = if (context.isEmpty()) "" else " ")
        val request =
            PredictRequest(
                context = TypingContext(before = before, field = field),
                composing = ComposingInput(typed, List(typed.length) { null }),
                layout = NO_LAYOUT,
                autocorrectAllowed = false,
            )
        return try {
            // Never waits for the engine lock: a typing decode holding it makes this null (busy),
            // and the model retries later. (A candidate outside the decode's pool is null too, so
            // it is retried a few times, bounded by the model's attempts, then dropped.)
            val f = engine.tryFeaturesFor(request, candidate)
            if (f == null) onDeferred()
            f
        } catch (e: Exception) {
            onFailure(e)
            null
        }
    }

    private companion object {
        // Real layouts get ids from 1; no touches means the geometry is never consulted.
        val NO_LAYOUT = KeyLayoutModel(0L, emptyList())
    }
}
