package dev.keycorrect.core.packs

import android.content.Context
import dev.keycorrect.core.swipenet.SwipeEncoder
import dev.keycorrect.core.swipenet.SwipeNetException
import java.io.IOException

/** Where things are in the swipe pack (written by `:swipepack`). */
object SwipePackLayout {
    const val PACKAGE = "dev.keycorrect.swipe"
    const val MODEL = "swipenet/model_int8.pte"

    /** The FUTO Model Weights License, which must ship with the weights. */
    const val LICENSE = "swipenet/LICENSE.md"
    val LIBRARIES = listOf("libkcswipenet.so")
    val ASSETS = listOf(MODEL, LICENSE)
}

/** The swipe pack as [SignedPackLocator] sees it. */
val SWIPE_PACK_SPEC =
    PackSpec(SwipePackLayout.PACKAGE, SwipePackLayout.LIBRARIES, SwipePackLayout.ASSETS)

/** The outcome of [SwipePackLoader.open]. */
sealed interface SwipePackResult {
    data class Available(val encoder: SwipeEncoder, val versionName: String?) : SwipePackResult

    /**
     * The pack can't be used: [problem] from the locator, or null when the pack is fine but the
     * encoder didn't load (an unreadable or incompatible model).
     */
    data class Unavailable(val problem: PackProblem?, val detail: String? = null) : SwipePackResult
}

/**
 * Opens FUTO Swipe's encoder from the swipe pack (`dev.keycorrect.swipe`): [SignedPackLocator]
 * checks that the pack is signed by the keyboard's own key and loads `libkcswipenet.so` from it;
 * the model is then read from the pack's assets (about 1 MB) into the native side. The keyboard
 * needs a `<queries><package android:name="dev.keycorrect.swipe"/></queries>` entry to see it.
 *
 * **Threading:** [open] reads files and calls the package manager; never on the main thread.
 */
class SwipePackLoader(context: Context) {
    private val locator = SignedPackLocator(context, SWIPE_PACK_SPEC)

    /** A new encoder (the caller closes it), or why there is none. */
    fun open(threads: Int = 1): SwipePackResult =
        when (val result = locator.locate()) {
            is PackResult.Unavailable -> SwipePackResult.Unavailable(result.problem, result.detail)
            is PackResult.Available ->
                try {
                    val model =
                        result.pack.openAssetFd(SwipePackLayout.MODEL).use { fd ->
                            fd.createInputStream().use { it.readBytes() }
                        }
                    SwipePackResult.Available(
                        SwipeEncoder.open(model, threads),
                        result.pack.versionName,
                    )
                } catch (e: IOException) {
                    SwipePackResult.Unavailable(PackProblem.AssetsMissing, e.javaClass.simpleName)
                } catch (e: SwipeNetException) {
                    SwipePackResult.Unavailable(null, e.javaClass.simpleName)
                }
        }

    /** See [SignedPackLocator.loadedLibrariesStale]. */
    fun loadedLibrariesStale(): Boolean = locator.loadedLibrariesStale()
}
