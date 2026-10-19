package dev.keycorrect.neural

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import dev.keycorrect.engine.EngineConnection
import dev.keycorrect.engine.EngineContract
import dev.keycorrect.engine.TextEngineLink
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Which model the engine is asked to load. */
internal sealed interface ModelChoice {
    /** The neural pack's default model. */
    data object PackDefault : ModelChoice

    /** An imported model (a file of [ModelStore]). */
    data class Imported(val path: String) : ModelChoice
}

/** The engine as [EngineClient] uses it (a seam for tests). */
internal interface NeuralLink : AutoCloseable {
    /** Tells the engine which model to load; false when it isn't connected. */
    fun configure(choice: ModelChoice, threads: Int): Boolean

    /** The engine's status ([EngineContract.INFO_STATUS]), or null when it doesn't answer. */
    fun status(): String?

    /** The status with the model description; by default only the status. */
    fun info(): EngineInfo? = status()?.let { EngineInfo(it, null) }

    fun score(context: String, candidates: List<String>, timeoutMs: Long): FloatArray?

    fun generate(prompt: String, maxTokens: Int, timeoutMs: Long): String?
}

/** [NeuralLink] over a [TextEngineLink]: the options and info as bundles. */
internal class BundleLink(private val link: TextEngineLink) : NeuralLink {
    override fun configure(choice: ModelChoice, threads: Int): Boolean =
        link.configure(
            Bundle().apply {
                when (choice) {
                    ModelChoice.PackDefault -> putBoolean(EngineContract.OPTION_USE_DEFAULT, true)
                    is ModelChoice.Imported ->
                        putString(EngineContract.OPTION_MODEL_PATH, choice.path)
                }
                if (threads > 0) putInt(EngineContract.OPTION_THREADS, threads)
            }
        )

    override fun status(): String? = link.info()?.getString(EngineContract.INFO_STATUS)

    override fun info(): EngineInfo? =
        link.info()?.let {
            EngineInfo(
                it.getString(EngineContract.INFO_STATUS),
                it.getString(EngineContract.INFO_MODEL),
            )
        }

    override fun score(context: String, candidates: List<String>, timeoutMs: Long) =
        link.score(context, candidates, timeoutMs)

    override fun generate(prompt: String, maxTokens: Int, timeoutMs: Long) =
        link.generate(prompt, maxTokens, timeoutMs)

    override fun close() = link.close()
}

/**
 * What the keyboard process uses to reach the language model: a connection to the built-in
 * [NeuralEngineService] or an approved plugin that answers [score] and [generate]. The built-in
 * engine is configured each time it connects; a plugin ([configures] false) is not sent the
 * built-in options (model path, threads), which mean nothing to it.
 *
 * [modelId] gives the current `neuralModel` setting: empty = the neural pack's default, otherwise
 * the id of an imported model ([ModelStore]); an id whose file is gone falls back to the default.
 * Call [reconfigure] when the setting changes.
 *
 * The engine releases its model under memory pressure and then reports "no model" or an error.
 * Whenever a call gets no answer, this checks that status (at most every [HEAL_INTERVAL_MS], off
 * the calling thread) and configures the engine again if it has no model.
 *
 * **Threads:** [score] and [generate] wait for the engine, so call them from a background thread;
 * they return null at once when it isn't connected. Everything else is safe from any thread.
 */
class EngineClient
internal constructor(
    private val modelId: () -> String,
    private val pathOf: (String) -> File?,
    private val threads: Int,
    private val clock: () -> Long,
    private val executor: Executor,
    private val configures: Boolean = true,
    makeLink: (onConnected: () -> Unit) -> NeuralLink,
) : AutoCloseable {
    /** Connects to the built-in engine. [modelStore] resolves the imported model ids. */
    constructor(
        context: Context,
        modelStore: ModelStore,
        modelId: () -> String,
        threads: Int = 0,
        component: ComponentName = ComponentName(context, NeuralEngineService::class.java),
        configures: Boolean = true,
    ) : this(
        modelId,
        modelStore::pathOf,
        threads,
        System::currentTimeMillis,
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "kc-engine-client").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        },
        configures,
        { onConnected -> BundleLink(EngineConnection(context, component, onConnected)) },
    )

    private val link: NeuralLink = makeLink { executor.execute(::sendConfigure) }
    private val healLock = Any()
    private var lastHealMs = -HEAL_INTERVAL_MS

    /**
     * Sends the current model choice to the engine; call when the `neuralModel` setting changes.
     */
    fun reconfigure() {
        executor.execute(::sendConfigure)
    }

    /**
     * For each of [candidates], the sum of log probabilities of its tokens after [context]; null
     * when the engine isn't connected, has no model yet, or doesn't answer within [timeoutMs].
     */
    fun score(context: String, candidates: List<String>, timeoutMs: Long): FloatArray? =
        link.score(context, candidates, timeoutMs).also { if (it == null) healIfDue() }

    /** A short continuation of [prompt], or null (see [score]). */
    fun generate(prompt: String, maxTokens: Int, timeoutMs: Long): String? =
        link.generate(prompt, maxTokens, timeoutMs).also { if (it == null) healIfDue() }

    /** The engine's status, or null when it isn't connected; blocks like [score]. */
    fun status(): String? = link.status()

    /** The engine's status and model description, or null; blocks like [score]. */
    fun info(): EngineInfo? = link.info()

    override fun close() {
        link.close()
    }

    internal fun choice(): ModelChoice {
        val id = modelId()
        val file = if (id.isEmpty()) null else pathOf(id)
        return if (file != null) ModelChoice.Imported(file.absolutePath)
        else ModelChoice.PackDefault
    }

    private fun sendConfigure() {
        if (!configures) return
        link.configure(choice(), threads)
    }

    private fun healIfDue() {
        val now = clock()
        synchronized(healLock) {
            if (now - lastHealMs < HEAL_INTERVAL_MS) return
            lastHealMs = now
        }
        executor.execute {
            val status = link.status() ?: return@execute
            if (status == EngineContract.STATUS_NO_MODEL || EngineContract.isError(status)) {
                sendConfigure()
            }
        }
    }

    internal companion object {
        /** The shortest time between two checks of an engine that gives no answers. */
        const val HEAL_INTERVAL_MS = 30_000L
    }
}
