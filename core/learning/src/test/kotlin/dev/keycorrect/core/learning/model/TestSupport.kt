package dev.keycorrect.core.learning.model

import dev.keycorrect.core.learning.LearningUpdate
import dev.keycorrect.core.learning.RankingFeatures
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

internal const val HOUR_MS = 3_600_000L
internal const val DAY_MS = 24 * HOUR_MS

/** Runs a suspend block that never actually suspends (the in-memory store), without coroutines. */
internal fun <T> runSuspend(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return result!!.getOrThrow()
}

internal fun testConfig(
    base: PersonalModelConfig = PersonalModelConfig(minTotalEvidence = 5.0)
): PersonalModelConfig = base.eager()

internal fun used(word: String, vararg ctx: String, w: Float = 1f) =
    LearningUpdate.WordUsed(ctx.toList(), word, w)

internal fun PersonalModel.applyAt(t: Long, vararg u: LearningUpdate) = apply(u.toList(), t)

internal fun feat(userLm: Float = 0f, lm: Float = -5f, rank: Int = 0) =
    RankingFeatures(
        spatial = -2f,
        lm = lm,
        edits = 0f,
        completionChars = 0,
        userLm = userLm,
        correctionBias = 0f,
        isLiteral = false,
        isUserWord = false,
        rank = rank,
    )
