package dev.keycorrect.ime

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.keycorrect.BuildConfig
import dev.keycorrect.core.decoder.rerank.CandidateScorer
import dev.keycorrect.core.decoder.rerank.PhraseGenerator
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.neural.AndroidPluginSource
import dev.keycorrect.neural.EngineClient
import dev.keycorrect.neural.EnginePlugins
import dev.keycorrect.neural.EngineTarget
import dev.keycorrect.neural.ModelStore
import dev.keycorrect.neural.chooseEngine
import dev.keycorrect.neural.neuralPackInstalled
import dev.keycorrect.neural.pluginVerifier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The keyboard's side of the language-model engine (docs/PHASE7.md): keeps an [EngineClient] bound
 * only while it can be useful, and scores for the [NeuralReranker].
 *
 * Which engine: the built-in one (in the `:engine` process), or the plugin the user chose, but a
 * plugin only while it is approved for its current signing certificate; otherwise the built-in
 * engine is used. The approval is checked when the engine is chosen and again by the client each
 * time the plugin connects, before anything is sent to it; a plugin that fails that check is
 * dropped for the built-in engine until the settings change or the keyboard is shown again. The
 * built-in engine is bound when reranking is on with a non-zero LM strength and there is a model to
 * run (the neural pack is installed, or an imported model is chosen); a plugin needs neither. After
 * the keyboard has been hidden for [IDLE_UNBIND_MS] the engine is unbound so its process can go
 * away.
 *
 * [apply] and the window callbacks run on the main thread, as does all binding; the package manager
 * lookups that choose the engine run on a background thread, and only when a setting that matters
 * changed or the keyboard is shown. [score] runs on the reranker's thread.
 */
@Suppress("TooManyFunctions") // The window callbacks, the plan, and its steps.
internal class NeuralHost(
    private val context: Context,
    private val modelStore: ModelStore,
    private val plugins: EnginePlugins = EnginePlugins(AndroidPluginSource(context)),
) : CandidateScorer, PhraseGenerator {
    @Volatile private var client: EngineClient? = null
    @Volatile private var settings = KeyboardSettings()
    private var target: EngineTarget = EngineTarget.None

    /** The model setting the current client was last told about. */
    private var sentModel = settings.neuralModel

    /** A plugin that failed its check on connecting; not used until that can change. */
    private var rejectedPlugin: String? = null

    /** Bumped for each choice; a lookup that comes back late for an older one is dropped. */
    private var planNumber = 0
    private val handler = Handler(Looper.getMainLooper())
    private val idleUnbind = Runnable { unbind() }
    private val lookups: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "kc-neural-host").apply { isDaemon = true }
    }

    /** Follows a settings change: binds, unbinds, switches engine, or sends the new model. */
    fun apply(next: KeyboardSettings) {
        val previous = settings
        settings = next
        if (sameEngineChoice(previous, next)) return // nothing that matters here; no lookups
        rejectedPlugin = null
        plan()
    }

    /** The keyboard is shown: stay bound (or bind again, e.g. after the pack was installed). */
    fun onWindowShown() {
        handler.removeCallbacks(idleUnbind)
        rejectedPlugin = null
        // The engine may have released its model under memory pressure while the keyboard was away.
        client?.reviveIfReleased()
        plan()
    }

    fun onWindowHidden() {
        handler.removeCallbacks(idleUnbind)
        handler.postDelayed(idleUnbind, IDLE_UNBIND_MS)
    }

    fun close() {
        handler.removeCallbacks(idleUnbind)
        planNumber++
        unbind()
        lookups.shutdownNow()
    }

    override fun complete(context: String, top: String, timeoutMs: Long): String? {
        traceCall("generate")
        return client?.generate("$context $top".trimStart(), PHRASE_TOKENS, timeoutMs)
    }

    override fun score(context: String, candidates: List<String>, timeoutMs: Long): FloatArray? {
        traceCall("score")
        return client?.score(context, candidates, timeoutMs)
    }

    /** Debug builds only: counts engine calls for on-device privacy checks. Never any text. */
    private fun traceCall(kind: String) {
        if (BuildConfig.DEBUG) Log.d(TRACE_TAG, "$kind #${calls.incrementAndGet()}")
    }

    private val calls = java.util.concurrent.atomic.AtomicInteger()

    /** Chooses the engine for the current settings and then enacts it on the main thread. */
    private fun plan() {
        val s = settings
        val number = ++planNumber
        if (!s.neuralRerank || s.lmStrength <= 0f) {
            enact(EngineTarget.None, s)
            return
        }
        val blocked = rejectedPlugin
        try {
            lookups.execute {
                val wanted = chooseEngine(s, blocked)
                handler.post { if (number == planNumber) enact(wanted, s) }
            }
        } catch (_: RejectedExecutionException) {
            // Closed.
        }
    }

    private fun enact(wanted: EngineTarget, s: KeyboardSettings) {
        val modelChanged = s.neuralModel != sentModel
        sentModel = s.neuralModel
        val current = client
        when {
            wanted == EngineTarget.None -> unbind()
            current == null || wanted != target -> {
                unbind()
                client = connect(wanted)
            }
            modelChanged && wanted == EngineTarget.Builtin -> current.reconfigure()
        }
        if (client != null) target = wanted
    }

    private fun connect(wanted: EngineTarget): EngineClient? =
        when (wanted) {
            EngineTarget.None -> null
            EngineTarget.Builtin ->
                EngineClient(context, modelStore, modelId = { settings.neuralModel })
            is EngineTarget.Plugin ->
                ComponentName.unflattenFromString(wanted.component)?.let {
                    EngineClient(
                        context,
                        modelStore,
                        modelId = { settings.neuralModel },
                        component = it,
                        configures = false,
                        verify =
                            pluginVerifier(plugins, wanted.component) {
                                settings.approvedEngines
                            },
                        onRejected = { handler.post { pluginRejected(wanted.component) } },
                    )
                }
        }

    /** The plugin failed its check on connecting: use the built-in engine instead. */
    private fun pluginRejected(component: String) {
        if (target != EngineTarget.Plugin(component)) return
        rejectedPlugin = component
        unbind()
        plan()
    }

    private fun unbind() {
        client?.close()
        client = null
        target = EngineTarget.None
    }

    /** [chooseEngine] for [s], without [rejected]; looks things up, so not for the main thread. */
    private fun chooseEngine(s: KeyboardSettings, rejected: String?): EngineTarget =
        chooseEngine(
            neuralRerank = s.neuralRerank,
            lmStrength = s.lmStrength,
            neuralEngine = s.neuralEngine,
            builtinAvailable = {
                s.neuralModel.isNotEmpty() || neuralPackInstalled(context.packageManager)
            },
            pluginApproved = { it != rejected && plugins.isApproved(it, s.approvedEngines) },
        )

    /** Whether nothing that decides which engine to bind differs between [a] and [b]. */
    private fun sameEngineChoice(a: KeyboardSettings, b: KeyboardSettings) =
        a.neuralRerank == b.neuralRerank &&
            (a.lmStrength > 0f) == (b.lmStrength > 0f) &&
            a.neuralEngine == b.neuralEngine &&
            a.neuralModel == b.neuralModel &&
            a.approvedEngines == b.approvedEngines

    private companion object {
        /** Tokens generated for a phrase: about three words. */
        const val PHRASE_TOKENS = 8

        const val IDLE_UNBIND_MS = 2 * 60 * 1000L
        const val TRACE_TAG = "KCEngine"
    }
}
