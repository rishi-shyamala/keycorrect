package dev.keycorrect.ime

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import dev.keycorrect.core.packs.NeuralPackLayout
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.neural.EngineClient
import dev.keycorrect.neural.ModelStore

/**
 * The keyboard's side of the built-in language model (docs/PHASE7.md): keeps an [EngineClient]
 * bound to the `:engine` process only while it can be useful, and scores for the [NeuralReranker].
 *
 * The engine is bound when reranking is on with a non-zero LM strength and there is a model to run
 * (the neural pack is installed, or an imported model is chosen); otherwise, or after the keyboard
 * has been hidden for [IDLE_UNBIND_MS], it is unbound so the engine process and its model can go
 * away. [apply] and the window callbacks run on the main thread; [score] on the reranker's thread.
 */
internal class NeuralHost(
    private val context: Context,
    private val modelStore: ModelStore,
) : CandidateScorer {
    @Volatile private var client: EngineClient? = null
    @Volatile private var settings = KeyboardSettings()
    private val handler = Handler(Looper.getMainLooper())
    private val idleUnbind = Runnable { unbind() }

    /** Follows a settings change: binds, unbinds, or sends the new model choice. */
    fun apply(next: KeyboardSettings) {
        val modelChanged = next.neuralModel != settings.neuralModel
        settings = next
        val current = client
        when {
            !wanted(next) -> unbind()
            current == null ->
                client = EngineClient(context, modelStore, modelId = { settings.neuralModel })
            modelChanged -> current.reconfigure()
        }
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

    override fun score(context: String, candidates: List<String>, timeoutMs: Long): FloatArray? =
        client?.score(context, candidates, timeoutMs)

    private fun unbind() {
        client?.close()
        client = null
    }

    private fun wanted(s: KeyboardSettings): Boolean =
        s.neuralRerank && s.lmStrength > 0f && (s.neuralModel.isNotEmpty() || packInstalled())

    private fun packInstalled(): Boolean =
        try {
            context.packageManager.getPackageInfo(NeuralPackLayout.PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    private companion object {
        const val IDLE_UNBIND_MS = 2 * 60 * 1000L
    }
}
