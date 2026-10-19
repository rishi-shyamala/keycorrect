package dev.keycorrect.neural

import android.content.Context
import dev.keycorrect.core.llama.LlamaException
import dev.keycorrect.core.llama.LlamaModel
import dev.keycorrect.core.llama.LlamaSession
import dev.keycorrect.core.packs.NEURAL_PACK_SPEC
import dev.keycorrect.core.packs.PackResult
import dev.keycorrect.core.packs.SignedPackLocator

/**
 * The llama.cpp [ModelRuntime] (`:core:llama`). The native library comes from the neural pack,
 * whatever model is opened: [libraryReady] locates the signature-checked pack, which loads
 * `libkcllama.so` into this process once. Without the pack nothing opens, imported models included.
 */
internal class LlamaRuntime(private val libraryReady: () -> Boolean) : ModelRuntime {
    constructor(context: Context) : this(packLibraryCheck(context))

    override fun openFile(path: String, threads: Int): LoadedModel? =
        if (libraryReady()) open(threads) { LlamaModel.openFile(path) } else null

    override fun openFd(fd: Int, offset: Long, threads: Int): LoadedModel? =
        if (libraryReady()) open(threads) { LlamaModel.openFd(fd, offset) } else null

    private fun open(threads: Int, load: () -> LlamaModel): LoadedModel? {
        val model =
            try {
                load()
            } catch (_: LlamaException) {
                return null
            }
        return try {
            LlamaLoaded(model, model.newSession(nThreads = threadsOrDefault(threads)))
        } catch (_: LlamaException) {
            model.close()
            null
        }
    }

    private class LlamaLoaded(private val model: LlamaModel, private val session: LlamaSession) :
        LoadedModel {
        override fun score(context: String, candidates: Array<String>, timeoutMs: Long) =
            session.score(context, candidates.asList(), timeoutMs)

        override fun generate(prompt: String, maxTokens: Int, timeoutMs: Long) =
            session.generate(prompt, maxTokens, timeoutMs)

        override fun describe(): String = model.description

        override fun close() {
            session.close()
            model.close()
        }
    }

    private companion object {
        fun packLibraryCheck(context: Context): () -> Boolean {
            val locator = SignedPackLocator(context.applicationContext, NEURAL_PACK_SPEC)
            return { locator.locate() is PackResult.Available }
        }

        /** Big cores on a phone; the engine is idle between keystrokes. */
        const val DEFAULT_THREADS = 4

        fun threadsOrDefault(threads: Int) = if (threads > 0) threads else DEFAULT_THREADS
    }
}
