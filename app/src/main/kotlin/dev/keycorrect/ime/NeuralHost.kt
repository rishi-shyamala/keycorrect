package dev.keycorrect.ime

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
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

/**
 * The keyboard's side of the language-model engine (docs/PHASE7.md): keeps an [EngineClient] bound
 * only while it can be useful, and scores for the [NeuralReranker].
 *
 * Which engine: the built-in one (in the `:engine` process), or the plugin the user chose, but a
 * plugin only while it is approved for its current signing certificate (re-checked on every [apply]
 * and [onWindowShown]); otherwise the built-in engine is used. The built-in engine is bound when
 * reranking is on with a non-zero LM strength and there is a model to run (the neural pack is
 * installed, or an imported model is chosen); a plugin needs neither. After the keyboard has been
 * hidden for [IDLE_UNBIND_MS] the engine is unbound so its process can go away. [apply] and the
 * window callbacks run on the main thread; [score] on the reranker's thread.
 */
internal class NeuralHost(
    private val context: Context,
    private val modelStore: ModelStore,
    private val plugins: EnginePlugins = EnginePlugins(AndroidPluginSource(context)),
) : CandidateScorer, PhraseGenerator {
    @Volatile private var client: EngineClient? = null
    @Volatile private var settings = KeyboardSettings()
    private var target: EngineTarget = EngineTarget.None
    private val handler = Handler(Looper.getMainLooper())
    private val idleUnbind = Runnable { unbind() }

    /** Follows a settings change: binds, unbinds, switches engine, or sends the new model. */
    fun apply(next: KeyboardSettings) {
        val modelChanged = next.neuralModel != settings.neuralModel
        settings = next
        val wanted = chooseEngine(next)
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

    /** The keyboard is shown: stay bound (or bind again, e.g. after the pack was installed). */
    fun onWindowShown() {
        handler.removeCallbacks(idleUnbind)
        apply(settings)
    }

    fun onWindowHidden() {
        handler.removeCallbacks(idleUnbind)
        handler.postDelayed(idleUnbind, IDLE_UNBIND_MS)
    }

    fun close() {
        handler.removeCallbacks(idleUnbind)
        unbind()
    }

    override fun complete(context: String, top: String, timeoutMs: Long): String? =
        client?.generate("$context $top".trimStart(), PHRASE_TOKENS, timeoutMs)

    override fun score(context: String, candidates: List<String>, timeoutMs: Long): FloatArray? =
        client?.score(context, candidates, timeoutMs)

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
                    )
                }
        }

    private fun unbind() {
        client?.close()
        client = null
        target = EngineTarget.None
    }

    private fun chooseEngine(s: KeyboardSettings): EngineTarget =
        chooseEngine(
            neuralRerank = s.neuralRerank,
            lmStrength = s.lmStrength,
            neuralEngine = s.neuralEngine,
            builtinAvailable = {
                s.neuralModel.isNotEmpty() || neuralPackInstalled(context.packageManager)
            },
            pluginApproved = { plugins.isApproved(it, s.approvedEngines) },
        )

    private companion object {
        /** Tokens generated for a phrase: about three words. */
        const val PHRASE_TOKENS = 8

        const val IDLE_UNBIND_MS = 2 * 60 * 1000L
    }
}
