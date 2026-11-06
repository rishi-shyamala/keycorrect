package dev.keycorrect.feature.voicesherpa

import android.content.Context
import android.content.res.AssetManager
import dev.keycorrect.core.packs.PackProblem
import dev.keycorrect.core.packs.PackResult
import dev.keycorrect.core.packs.PackSpec
import dev.keycorrect.core.packs.SignedPack
import dev.keycorrect.core.packs.SignedPackLocator

/** Why the voice pack can't be used. */
enum class VoicePackProblem {
    /** `dev.keycorrect.voice` isn't installed (or the keyboard can't see it: see `<queries>`). */
    NotInstalled,

    /** The pack is signed with a different key than the keyboard, so nothing from it is loaded. */
    SignatureMismatch,

    /**
     * The pack is installed but its native libraries aren't on disk (wrong ABI, damaged install).
     */
    LibrariesMissing,

    /**
     * A native library is there but couldn't be loaded (the result's detail is the error class).
     */
    LibraryLoadFailed,

    /** The pack's speech model files are missing or compressed (not readable in place). */
    ModelsMissing,

    /** The pack was updated or replaced while it was being checked; the next try checks again. */
    ChangedDuringLoad,

    /**
     * The pack was updated or reinstalled with different native libraries after this process loaded
     * the old ones; a library can't be unloaded, so the keyboard's process has to restart.
     */
    NeedsRestart,
}

/** An installed, verified voice pack whose native libraries are loaded. */
class VoicePack internal constructor(private val pack: SignedPack) {
    val versionName: String?
        get() = pack.versionName

    /** The pack's assets, read in place (nothing is copied). */
    val assets: AssetManager
        get() = pack.assets

    /** Asset paths of the speech model files inside the pack. */
    val models = VoicePackLayout

    internal fun isFor(other: SignedPack) = pack === other
}

/** Where things are in the pack (written by `:voicepack`). */
object VoicePackLayout {
    const val PACKAGE = "dev.keycorrect.voice"
    const val MOONSHINE_ENCODER = "moonshine/encoder_model.ort"
    const val MOONSHINE_DECODER = "moonshine/decoder_model_merged.ort"
    const val MOONSHINE_TOKENS = "moonshine/tokens.txt"
    const val SILERO_VAD = "silero_vad.onnx"
    internal val ASSETS = listOf(MOONSHINE_ENCODER, MOONSHINE_DECODER, MOONSHINE_TOKENS, SILERO_VAD)

    // libsherpa-onnx-jni.so needs libonnxruntime.so, so that one goes first.
    internal val LIBRARIES = listOf("libonnxruntime.so", "libsherpa-onnx-jni.so")
}

/** The outcome of [VoicePackLocator.locate]. */
sealed interface VoicePackResult {
    data class Available(val pack: VoicePack) : VoicePackResult

    /** [detail] is the class name of the error behind [VoicePackProblem.LibraryLoadFailed]. */
    data class Unavailable(val problem: VoicePackProblem, val detail: String? = null) :
        VoicePackResult
}

internal val VOICE_PACK_SPEC =
    PackSpec(VoicePackLayout.PACKAGE, VoicePackLayout.LIBRARIES, VoicePackLayout.ASSETS)

internal fun PackProblem.toVoice(): VoicePackProblem =
    when (this) {
        PackProblem.NotInstalled -> VoicePackProblem.NotInstalled
        PackProblem.SignatureMismatch -> VoicePackProblem.SignatureMismatch
        PackProblem.LibrariesMissing -> VoicePackProblem.LibrariesMissing
        PackProblem.LibraryLoadFailed -> VoicePackProblem.LibraryLoadFailed
        PackProblem.AssetsMissing -> VoicePackProblem.ModelsMissing
        PackProblem.ChangedDuringLoad -> VoicePackProblem.ChangedDuringLoad
        PackProblem.NeedsRestart -> VoicePackProblem.NeedsRestart
    }

/**
 * Finds the voice pack (`dev.keycorrect.voice`, a separate APK), checks that it is signed by the
 * keyboard's own key, and loads its native libraries from its install directory: a thin layer over
 * [SignedPackLocator] (which has the details) for the voice pack's [VoicePackLayout]. The keyboard
 * needs a `<queries><package android:name="dev.keycorrect.voice"/></queries>` entry to see the
 * package.
 *
 * **Threading:** [locate] and [loadedLibrariesStale] read files (hashing ~27 MB the first time and
 * after an update) and call the package manager, so they must never run on the main thread.
 */
class VoicePackLocator(context: Context) {
    private val locator = SignedPackLocator(context, VOICE_PACK_SPEC)

    private var cached: VoicePack? = null

    @Synchronized
    fun locate(): VoicePackResult =
        when (val result = locator.locate()) {
            is PackResult.Available -> VoicePackResult.Available(voicePack(result.pack))
            is PackResult.Unavailable ->
                VoicePackResult.Unavailable(result.problem.toVoice(), result.detail)
        }

    /**
     * Whether libraries are loaded in this process that the installed pack no longer provides (the
     * pack was removed, or replaced with different libraries): the process should restart when it
     * is idle. False when nothing is loaded or the installed libraries are the loaded ones.
     */
    fun loadedLibrariesStale(): Boolean = locator.loadedLibrariesStale()

    /** The same [VoicePack] for the same verified install, as before. */
    private fun voicePack(pack: SignedPack): VoicePack =
        cached?.takeIf { it.isFor(pack) } ?: VoicePack(pack).also { cached = it }
}
