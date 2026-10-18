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
class VoicePack internal constructor(private val source: PackAssets, val versionName: String?) {
    /** The pack's assets, read in place (nothing is copied). */
    val assets: AssetManager
        get() = source.manager

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

/** What identifies one install of the pack: it changes with every update. */
internal data class PackStamp(val versionCode: Long, val lastUpdateTime: Long)

/** What the package manager says about a package; [signers] only when asked for. */
internal class PackFacts(
    val stamp: PackStamp,
    val versionName: String?,
    val signers: Set<String>?,
    val nativeLibraryDir: String?,
)

/** The pack's assets; [manager] is only touched by the engine (tests never read it). */
internal interface PackAssets {
    val manager: AssetManager

    /** Whether every model file is there and stored uncompressed (readable in place). */
    fun hasModels(): Boolean
}

/** The Android lookups behind [VoicePackLocator] (a seam for tests). */
internal interface PackSource {
    /**
     * The pack's facts, or null when it isn't installed. Signers are costly: ask only when needed.
     */
    fun pack(withSigners: Boolean): PackFacts?

    /** The keyboard's own signer digests (empty when unreadable). */
    fun ownSigners(): Set<String>

    /** The pack's assets, or null when it isn't installed. */
    fun assets(): PackAssets?
}

private class AndroidPackSource(private val context: Context) : PackSource {
    override fun pack(withSigners: Boolean): PackFacts? =
        info(VoicePackLayout.PACKAGE, withSigners)?.let {
            PackFacts(
                PackStamp(it.longVersionCode, it.lastUpdateTime),
                it.versionName,
                if (withSigners) signers(it) else null,
                it.applicationInfo?.nativeLibraryDir,
            )
        }

    override fun ownSigners(): Set<String> =
        info(context.packageName, withSigners = true)?.let(::signers).orEmpty()

    override fun assets(): PackAssets? =
        try {
            val assets = context.createPackageContext(VoicePackLayout.PACKAGE, 0).assets
            object : PackAssets {
                override val manager = assets

                override fun hasModels() =
                    VoicePackLayout.ASSETS.all { path ->
                        try {
                            assets.openFd(path).use { true }
                        } catch (_: java.io.IOException) {
                            false
                        }
                    }
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    private fun info(name: String, withSigners: Boolean): PackageInfo? =
        try {
            context.packageManager.getPackageInfo(
                name,
                if (withSigners) PackageManager.GET_SIGNING_CERTIFICATES else 0,
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    /** The signer digests; for a rotated key, the current signers only (what we vouch for). */
    private fun signers(info: PackageInfo): Set<String>? =
        info.signingInfo?.apkContentsSigners?.mapTo(HashSet()) { certDigest(it.toByteArray()) }
}

/**
 * Finds the voice pack (`dev.keycorrect.voice`, a separate APK), checks that it is signed by the
 * keyboard's own key, and loads its native libraries from its install directory. The keyboard needs
 * a `<queries><package android:name="dev.keycorrect.voice"/></queries>` entry to see the package.
 *
 * The pack's assets are taken first, then the signature is checked, the libraries are loaded, and
 * the pack's version and update time are read again: a pack replaced meanwhile is refused. An
 * [Available][VoicePackResult.Available] result is kept per install (version code and update time),
 * so later calls only look the stamp up. Libraries are loaded once per process, from the first
 * verified install path. If the pack is updated afterwards (a new path), the loaded copy stays and
 * [VoicePackProblem.Broken] is reported until the keyboard's process restarts.
 */
class VoicePackLocator
internal constructor(
    private val source: PackSource,
    private val loader: NativeLoader,
) {
    constructor(
        context: Context
    ) : this(AndroidPackSource(context.applicationContext), NativeLoader { System.load(it) })

    private var cached: Pair<PackStamp, VoicePack>? = null

    @Synchronized
    fun locate(): VoicePackResult =
        try {
            VoicePackResult.Available(cachedPack() ?: open())
        } catch (e: PackException) {
            cached = null
            VoicePackResult.Unavailable(e.problem)
        }

    private class PackException(val problem: VoicePackProblem) : Exception(problem.name)

    private fun fail(problem: VoicePackProblem): Nothing = throw PackException(problem)

    private fun cachedPack(): VoicePack? {
        val (stamp, pack) = cached ?: return null
        val now = source.pack(withSigners = false) ?: fail(VoicePackProblem.NotInstalled)
        return if (now.stamp == stamp) pack else null
    }

    private fun open(): VoicePack {
        val before = source.pack(withSigners = true) ?: fail(VoicePackProblem.NotInstalled)
        // The assets before the signature check, so both refer to the install that was checked
        // (the stamp is compared again at the end).
        val assets = source.assets() ?: fail(VoicePackProblem.NotInstalled)
        checkPackSigners(before.signers, source.ownSigners())?.let { fail(it) }
        val libDir = before.nativeLibraryDir ?: fail(VoicePackProblem.Broken)
        if (!loadLibraries(libDir)) fail(VoicePackProblem.Broken)
        if (!assets.hasModels()) fail(VoicePackProblem.Broken)
        val after = source.pack(withSigners = false) ?: fail(VoicePackProblem.NotInstalled)
        if (after.stamp != before.stamp) fail(VoicePackProblem.Broken)
        return VoicePack(assets, before.versionName).also { cached = before.stamp to it }
    }

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

    internal companion object {
        private val lock = Any()
        private var loadedDir: String? = null

        /** Forgets the loaded libraries, for tests that load through a fake loader. */
        internal fun resetLoadedForTest() = synchronized(lock) { loadedDir = null }
    }
}
