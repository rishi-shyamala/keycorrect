package dev.keycorrect.neural

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import androidx.core.content.pm.PackageInfoCompat
import dev.keycorrect.core.packs.NEURAL_PACK_SPEC
import dev.keycorrect.core.packs.NeuralPackLayout
import dev.keycorrect.core.packs.PackProblem
import dev.keycorrect.core.packs.PackResult
import dev.keycorrect.core.packs.SignedPackLocator
import dev.keycorrect.engine.EngineContract
import dev.keycorrect.engine.TextEngineService
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * The built-in language-model engine, in its own process (`:engine`) so a crash or out-of-memory
 * there never takes the keyboard down. It is bound explicitly by the keyboard ([EngineClient]),
 * never exported, and speaks the same contract as a plugin.
 *
 * The keyboard sends [configure] with `modelPath` (an imported model, see [ModelStore]) or
 * `useDefault` (the neural pack's model) and `threads`; this process never reads the settings. The
 * model loads on a background thread; [info] reports the status. The model is released under memory
 * pressure and when the service ends. Rules and state are in [NeuralEngineCore].
 */
class NeuralEngineService : TextEngineService() {
    private val loader = Executors.newSingleThreadExecutor { task ->
        Thread(task, "kc-engine-load").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    private val core by lazy {
        NeuralEngineCore(
            filesRoot = filesDir,
            runtime = { ModelRuntimes.provider() },
            defaultModel = PackModelSource(this),
            executor = loader,
            restartProcess = { Process.killProcess(Process.myPid()) },
            markerDir = File(noBackupFilesDir, "load-markers"),
        )
    }

    override fun info(): Bundle =
        Bundle().apply {
            putString(EngineContract.INFO_ID, ENGINE_ID)
            putString(EngineContract.INFO_NAME, ENGINE_NAME)
            putStringArray(
                EngineContract.INFO_CAPABILITIES,
                arrayOf(EngineContract.CAPABILITY_SCORE, EngineContract.CAPABILITY_GENERATE),
            )
            putStringArray(EngineContract.INFO_LANGUAGES, arrayOf(LANGUAGE))
            putString(EngineContract.INFO_STATUS, core.status)
            core.modelDescription?.let { putString(EngineContract.INFO_MODEL, it) }
        }

    override fun configure(options: Bundle) {
        core.configure(
            modelPath = options.getString(EngineContract.OPTION_MODEL_PATH),
            useDefault = options.getBoolean(EngineContract.OPTION_USE_DEFAULT, false),
            threads = options.getInt(EngineContract.OPTION_THREADS, 0),
        )
    }

    override fun score(context: String, candidates: Array<String>, timeoutMs: Long): FloatArray? =
        core.score(context, candidates, timeoutMs)

    override fun generate(prompt: String, maxTokens: Int, timeoutMs: Long): String? =
        core.generate(prompt, maxTokens, timeoutMs)

    // Only real pressure releases the model: UI_HIDDEN and BACKGROUND arrive all the time, and a
    // reload costs more than the memory is worth. The RUNNING_* levels are deprecated but still
    // what reaches a process that is bound by a visible keyboard.
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) {
            core.release()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        core.release()
    }

    override fun onDestroy() {
        core.release()
        loader.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val ENGINE_ID = "dev.keycorrect.neural"
        const val ENGINE_NAME = "On-device language model"
        const val LANGUAGE = "en"
    }
}

/** The default model, read in place from the signed neural pack. */
internal class PackModelSource(context: Context) : DefaultModelSource {
    private val locator = SignedPackLocator(context.applicationContext, NEURAL_PACK_SPEC)

    private val pm = context.applicationContext.packageManager

    /** Which build of the pack and where its model starts: a new install is a new identity. */
    private fun packIdentity(offset: Long): String =
        try {
            val info = pm.getPackageInfo(NeuralPackLayout.PACKAGE, 0)
            "pack:${info.lastUpdateTime}:${PackageInfoCompat.getLongVersionCode(info)}:$offset"
        } catch (_: PackageManager.NameNotFoundException) {
            "pack:unknown:$offset"
        }

    override fun open(): DefaultModel =
        when (val found = locator.locate()) {
            is PackResult.Unavailable ->
                DefaultModel.Missing(
                    found.problem.name,
                    needsRestart = found.problem == PackProblem.NeedsRestart,
                )
            is PackResult.Available ->
                try {
                    val file = found.pack.openAssetFd(NeuralPackLayout.DEFAULT_MODEL)
                    DefaultModel.Found(
                        file.parcelFileDescriptor.fd,
                        file.startOffset,
                        file,
                        identity = packIdentity(file.startOffset),
                    )
                } catch (_: IOException) {
                    DefaultModel.Missing(PackProblem.AssetsMissing.name)
                }
        }
}
