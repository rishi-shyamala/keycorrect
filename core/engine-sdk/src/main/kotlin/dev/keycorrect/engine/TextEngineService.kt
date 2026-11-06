package dev.keycorrect.engine

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log

/** What an engine does, behind the guards of [GuardedBinder]. */
internal interface EngineHandlers {
    fun info(): Bundle

    fun configure(options: Bundle)

    fun score(context: String, candidates: Array<String>, timeoutMs: Long): FloatArray?

    fun generate(prompt: String, maxTokens: Int, timeoutMs: Long): String?
}

/**
 * The binder of an engine service. It never lets a problem of the engine reach the keyboard: every
 * exception becomes a null answer, a call that runs past its (limited) timeout is abandoned, and
 * input that is too large is refused. The timeouts are at most [EngineContract.MAX_TIMEOUT_MS].
 * Nothing the keyboard sends is ever logged.
 */
internal class GuardedBinder(
    private val handlers: EngineHandlers,
    private val runner: BoundedRunner,
) : ITextEngine.Stub() {
    override fun apiVersion(): Int = EngineContract.API_VERSION

    override fun info(): Bundle? =
        guarded("info", EngineContract.MAX_TIMEOUT_MS) { handlers.info() }

    override fun configure(options: Bundle?) {
        if (options == null) return
        guarded("configure", EngineContract.MAX_TIMEOUT_MS) { handlers.configure(options) }
    }

    @Suppress("ReturnCount") // Guard clauses, one per kind of bad input.
    override fun score(
        context: String?,
        candidates: Array<String?>?,
        timeoutMs: Long,
    ): FloatArray? {
        if (context == null || candidates == null) return null
        if (candidates.isEmpty() || candidates.size > EngineContract.MAX_CANDIDATES) return null
        val texts = Array(candidates.size) { candidates[it] ?: return null }
        if (tooLong(context) || texts.any(::tooLong)) return null
        val limit = EngineContract.clampTimeout(timeoutMs)
        val scores = guarded("score", limit) { handlers.score(context, texts, limit) }
        return scores?.takeIf { it.size == texts.size && it.none(Float::isNaN) }
    }

    override fun generate(prompt: String?, maxTokens: Int, timeoutMs: Long): String? {
        if (prompt == null || tooLong(prompt) || maxTokens < 1) return null
        val limit = EngineContract.clampTimeout(timeoutMs)
        val tokens = maxTokens.coerceAtMost(EngineContract.MAX_GENERATE_TOKENS)
        return guarded("generate", limit) { handlers.generate(prompt, tokens, limit) }
    }

    private fun tooLong(text: String) = text.length > EngineContract.MAX_TEXT_CHARS

    /** Runs [block] for at most [limitMs] (plus a short grace); null for any failure. */
    @Suppress("TooGenericExceptionCaught") // Whatever the engine throws, the answer is null.
    private fun <T : Any> guarded(what: String, limitMs: Long, block: () -> T?): T? =
        runner.run(limitMs + GRACE_MS) {
            try {
                block()
            } catch (e: Exception) {
                // The class only: a message could contain the text that was being processed.
                Log.w(TAG, "$what failed: ${e.javaClass.simpleName}")
                null
            }
        }

    private companion object {
        const val TAG = "KeyCorrectEngine"

        /** Time for the engine to notice its own timeout and answer before the call is dropped. */
        const val GRACE_MS = 100L
    }
}

/**
 * The base of a text-engine service: the built-in neural engine and every plugin extend it, so the
 * guards of [GuardedBinder] apply to all of them. A plugin declares a subclass in its manifest with
 * an intent filter for [EngineContract.ACTION].
 *
 * The four calls may run on any thread, several at once; an engine that can't serve concurrent
 * calls serializes them itself. A subclass returns null from [score] or [generate] when it has no
 * answer, and may throw: the caller sees null either way. Never log the text of a call.
 */
abstract class TextEngineService : Service() {
    /** Who the engine is and its status; see [EngineContract] for the keys. */
    protected abstract fun info(): Bundle

    /** Takes engine-specific options; returns quickly (load a model in the background). */
    protected abstract fun configure(options: Bundle)

    /**
     * Log probabilities of [candidates] after [context], within [timeoutMs] (at most
     * [EngineContract.MAX_TIMEOUT_MS]); null when there is no answer. The result must have one
     * value per candidate.
     */
    protected abstract fun score(
        context: String,
        candidates: Array<String>,
        timeoutMs: Long,
    ): FloatArray?

    /** A short continuation of [prompt] within [timeoutMs]; null when there is no answer. */
    protected abstract fun generate(prompt: String, maxTokens: Int, timeoutMs: Long): String?

    private val runner = BoundedRunner("kc-engine-call", MAX_CONCURRENT_CALLS)

    private val binder =
        GuardedBinder(
            object : EngineHandlers {
                override fun info() = this@TextEngineService.info()

                override fun configure(options: Bundle) = this@TextEngineService.configure(options)

                override fun score(context: String, candidates: Array<String>, timeoutMs: Long) =
                    this@TextEngineService.score(context, candidates, timeoutMs)

                override fun generate(prompt: String, maxTokens: Int, timeoutMs: Long) =
                    this@TextEngineService.generate(prompt, maxTokens, timeoutMs)
            },
            runner,
        )

    final override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        runner.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val MAX_CONCURRENT_CALLS = 4
    }
}
