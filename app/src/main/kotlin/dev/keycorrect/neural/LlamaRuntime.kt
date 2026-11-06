package dev.keycorrect.neural

import android.content.Context
import dev.keycorrect.core.llama.LlamaException
import dev.keycorrect.core.llama.LlamaModel
import dev.keycorrect.core.llama.LlamaSession
import dev.keycorrect.core.packs.NEURAL_PACK_SPEC
import dev.keycorrect.core.packs.PackResult
import dev.keycorrect.core.packs.SignedPackLocator
import java.io.File
import java.io.IOException

/**
 * The llama.cpp [ModelRuntime] (`:core:llama`). The native library comes from the neural pack,
 * whatever model is opened: [libraryReady] locates the signature-checked pack, which loads
 * `libkcllama.so` into this process once. Without the pack nothing opens, imported models included.
 */
internal class LlamaRuntime(
    private val libraryReady: () -> Boolean,
    cpuSupported: () -> Boolean = ::deviceCpuSupported,
) : ModelRuntime {
    constructor(context: Context) : this(packLibraryCheck(context))

    private val cpuOk by lazy(cpuSupported)

    /** The library needs ARMv8.2-A dot product and fp16; without them it dies (SIGILL). */
    override fun problem(): String? = if (cpuOk) null else EngineFailure.UNSUPPORTED_CPU

    // The CPU is checked before the library is located, so an unsupported phone never loads it.
    override fun openFile(path: String, threads: Int): LoadedModel? =
        if (cpuOk && libraryReady()) open(threads) { LlamaModel.openFile(path) } else null

    override fun openFd(fd: Int, offset: Long, threads: Int): LoadedModel? =
        if (cpuOk && libraryReady()) open(threads) { LlamaModel.openFd(fd, offset) } else null

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

/** Whether this phone's processor can run the neural pack's library (see [cpuSupports]). */
internal fun deviceCpuSupported(): Boolean =
    cpuSupports(
        abi = android.os.Build.SUPPORTED_ABIS.firstOrNull(),
        cpuInfo =
            try {
                File("/proc/cpuinfo").readText()
            } catch (_: IOException) {
                null
            },
    )

private const val ARM64_ABI = "arm64-v8a"
private const val X86_64_ABI = "x86_64"
private val WHITESPACE = Regex("\\s+")

/**
 * The pack's `libkcllama.so` uses the ARMv8.2-A dot-product and half-precision instructions
 * (`asimddp`; `fphp` or `asimdhp`), which a CPU without them answers with SIGILL. On arm64 every
 * core listed in [cpuInfo] (the text of /proc/cpuinfo) must have them; an unreadable list counts as
 * unsupported. x86_64 (emulators) is accepted; no other ABI has a library.
 */
internal fun cpuSupports(abi: String?, cpuInfo: String?): Boolean =
    when (abi) {
        X86_64_ABI -> true
        ARM64_ABI -> {
            val features =
                cpuInfo
                    ?.lineSequence()
                    ?.filter { it.trimStart().startsWith("Features", ignoreCase = true) }
                    ?.map { it.substringAfter(':').trim().split(WHITESPACE).toSet() }
                    ?.toList()
                    .orEmpty()
            features.isNotEmpty() &&
                features.all { "asimddp" in it && ("fphp" in it || "asimdhp" in it) }
        }
        else -> false
    }
