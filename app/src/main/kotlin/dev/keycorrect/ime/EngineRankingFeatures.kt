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
 * `featuresFor` shares the engine's lock with typing decodes, so a call is skipped (null, and
 * [onDeferred] so the model's retry is scheduled for later) while the decoder is busy or the
 * [budget] for the current second is used up; see [PredictionHost]. A candidate the decode does not
 * produce is [RankingFeatures.NOT_DECODABLE], so the model gives up on that preference instead of
 * retrying; an engine failure is null (retried a few times, then dropped).
 *
 * The decode is built from the event's context with no touches (the event does not carry the raw
 * taps), so the spatial feature is the literal's neutral value; [field] supplies the field class
 * for the correction-memory bucket.
 */
internal class EngineRankingFeatures(
    private val engine: ClassicEngine,
    private val activity: DecodeActivity,
    private val budget: DecodeBudget,
    private val field: () -> FieldClass,
    private val onDeferred: () -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
) {
    fun features(typed: String, context: List<String>, candidate: String): RankingFeatures? {
        if (activity.busy() || !budget.tryAcquire()) {
            onDeferred()
            return null
        }
        val before = context.joinToString(" ", postfix = if (context.isEmpty()) "" else " ")
        val request =
            PredictRequest(
                context = TypingContext(before = before, field = field()),
                composing = ComposingInput(typed, List(typed.length) { null }),
                layout = NO_LAYOUT,
                autocorrectAllowed = false,
            )
        return try {
            // The engine reports a candidate outside the decode's pool as null: that will not
            // change on a retry, which the model must be told apart from "busy".
            engine.featuresFor(request, candidate) ?: RankingFeatures.NOT_DECODABLE
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
