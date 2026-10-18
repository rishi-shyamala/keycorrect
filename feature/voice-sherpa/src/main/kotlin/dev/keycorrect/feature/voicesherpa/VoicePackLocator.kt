package dev.keycorrect.feature.voicesherpa

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import java.io.File

/** Why the voice pack can't be used. */
enum class VoicePackProblem {
    /** `dev.keycorrect.voice` isn't installed (or the keyboard can't see it: see `<queries>`). */
    NotInstalled,

    /** The pack is signed with a different key than the keyboard, so nothing from it is loaded. */
    SignatureMismatch,

    /**
     * The pack is installed but unusable: missing libraries or models, or an unloadable library.
     */
    Broken,
}

/** An installed, verified voice pack whose native libraries are loaded. */
class VoicePack
internal constructor(
    /** The pack's assets, read in place (nothing is copied). */
    val assets: AssetManager,
    val versionName: String?,
) {
    /** Asset paths of the speech model files inside the pack. */
    val models = VoicePackLayout
}

/** Where things are in the pack (written by `:voicepack`). */
object VoicePackLayout {
    const val PACKAGE = "dev.keycorrect.voice"
    const val MOONSHINE_ENCODER = "moonshine/encoder_model.ort"
    const val MOONSHINE_DECODER = "moonshine/decoder_model_merged.ort"
    const val MOONSHINE_TOKENS = "moonshine/tokens.txt"
    const val SILERO_VAD = "silero_vad.onnx"
    internal val ASSETS = listOf(MOONSHINE_ENCODER, MOONSHINE_DECODER, MOONSHINE_TOKENS, SILERO_VAD)
    internal val LIBRARIES = listOf("libonnxruntime.so", "libsherpa-onnx-jni.so")
}

/** Loads a native library by absolute path (a seam for tests). */
internal fun interface NativeLoader {
    fun load(path: String)
}

/** The outcome of [VoicePackLocator.locate]. */
sealed interface VoicePackResult {
    data class Available(val pack: VoicePack) : VoicePackResult

    data class Unavailable(val problem: VoicePackProblem) : VoicePackResult
}

/**
 * Finds the voice pack (`dev.keycorrect.voice`, a separate APK), checks that it is signed by the
 * keyboard's own key, and loads its native libraries from its install directory. The keyboard needs
 * a `<queries><package android:name="dev.keycorrect.voice"/></queries>` entry to see the package.
 *
 * Libraries are loaded once per process, from the first verified install path. If the pack is
 * updated afterwards (a new path), the loaded copy stays and [VoicePackProblem.Broken] is reported
 * until the keyboard's process restarts.
 */
class VoicePackLocator
internal constructor(private val context: Context, private val loader: NativeLoader) {
    constructor(
        context: Context
    ) : this(context.applicationContext, NativeLoader { System.load(it) })

    fun locate(): VoicePackResult =
        try {
            VoicePackResult.Available(open())
        } catch (e: PackException) {
            VoicePackResult.Unavailable(e.problem)
        }

    private class PackException(val problem: VoicePackProblem) : Exception(problem.name)

    private fun fail(problem: VoicePackProblem): Nothing = throw PackException(problem)

    private fun open(): VoicePack {
        val packInfo = packageInfo(VoicePackLayout.PACKAGE) ?: fail(VoicePackProblem.NotInstalled)
        val ownInfo = packageInfo(context.packageName)
        checkPackSigners(signers(packInfo), ownInfo?.let(::signers).orEmpty())?.let {
            fail(it)
        }
        val libDir = packInfo.applicationInfo?.nativeLibraryDir ?: fail(VoicePackProblem.Broken)
        if (!loadLibraries(libDir)) fail(VoicePackProblem.Broken)
        val assets =
            try {
                context.createPackageContext(VoicePackLayout.PACKAGE, 0).assets
            } catch (_: PackageManager.NameNotFoundException) {
                fail(VoicePackProblem.NotInstalled)
            }
        if (!hasAssets(assets)) fail(VoicePackProblem.Broken)
        return VoicePack(assets, packInfo.versionName)
    }

    private fun packageInfo(name: String): PackageInfo? =
        try {
            context.packageManager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    /** The signer digests; for a rotated key, the current signers only (what we vouch for). */
    private fun signers(info: PackageInfo): Set<String>? =
        info.signingInfo?.apkContentsSigners?.mapTo(HashSet()) { certDigest(it.toByteArray()) }

    private fun loadLibraries(libDir: String): Boolean =
        synchronized(lock) {
            val loadedFrom = loadedDir
            if (loadedFrom != null) return loadedFrom == libDir
            val files = VoicePackLayout.LIBRARIES.map { File(libDir, it) }
            if (!files.all { it.isFile }) return false
            return try {
                // libsherpa-onnx-jni.so needs libonnxruntime.so, so that one goes first.
                files.forEach { loader.load(it.absolutePath) }
                loadedDir = libDir
                true
            } catch (_: UnsatisfiedLinkError) {
                false
            }
        }

    private fun hasAssets(assets: AssetManager): Boolean =
        VoicePackLayout.ASSETS.all { path ->
            try {
                assets.openFd(path).use { true }
            } catch (_: java.io.IOException) {
                false
            }
        }

    private companion object {
        val lock = Any()
        var loadedDir: String? = null
    }
}
