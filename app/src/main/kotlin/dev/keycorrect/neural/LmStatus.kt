package dev.keycorrect.neural

import dev.keycorrect.engine.EngineContract

/** What the engine says about itself, for the settings screen. */
internal data class EngineInfo(val status: String?, val model: String?)

/** The language-model status line, before it is turned into words. */
internal sealed interface LmStatus {
    data class Ready(val model: String?) : LmStatus

    data object Loading : LmStatus

    data object NoModel : LmStatus

    /** The engine failed; [reason] is a short code from the engine, never user text. */
    data class Error(val reason: String) : LmStatus

    /** The engine isn't connected or doesn't answer. */
    data object NoAnswer : LmStatus
}

/** Reads an engine's [info] into a [LmStatus]. */
internal fun lmStatusOf(info: EngineInfo?): LmStatus {
    val status = info?.status
    return when {
        status == null -> LmStatus.NoAnswer
        status == EngineContract.STATUS_READY ->
            LmStatus.Ready(info.model?.takeIf { it.isNotBlank() })
        status == EngineContract.STATUS_LOADING -> LmStatus.Loading
        status == EngineContract.STATUS_NO_MODEL -> LmStatus.NoModel
        EngineContract.isError(status) ->
            LmStatus.Error(status.removePrefix(EngineContract.STATUS_ERROR_PREFIX))
        else -> LmStatus.NoAnswer
    }
}

/**
 * Asks [info] until the engine gives a settled answer: right after connecting, an engine can still
 * say "no model" before it has been configured, so that answer counts only from poll [settlePolls]
 * on; "loading" and no answer are asked again, up to [maxPolls] times, with [pause] between.
 * Returns the last answer when none settles.
 */
internal suspend fun awaitStatus(
    info: () -> EngineInfo?,
    pause: suspend () -> Unit,
    maxPolls: Int = 15,
    settlePolls: Int = 2,
): LmStatus {
    var last: LmStatus = LmStatus.NoAnswer
    for (poll in 0 until maxPolls) {
        last = lmStatusOf(info())
        val settled =
            when (last) {
                LmStatus.Loading,
                LmStatus.NoAnswer -> false
                LmStatus.NoModel -> poll >= settlePolls
                is LmStatus.Ready,
                is LmStatus.Error -> true
            }
        if (settled) return last
        pause()
    }
    return last
}
