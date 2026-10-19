package dev.keycorrect.feature.keyboard.prediction

import dev.keycorrect.core.engineapi.GestureRequest
import dev.keycorrect.core.engineapi.KeyLayoutModel
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.PredictResult

/**
 * Synchronous prediction entry point. Called on the decoder thread for every keystroke and, when
 * the decoder is idle and no result exists at a separator, on the main thread. [stale] turns true
 * once a newer input event has superseded [request]; the predictor should then return early with
 * any result (the caller drops it).
 */
fun interface Predictor {
    fun predict(request: PredictRequest, stale: () -> Boolean): PredictResult

    /**
     * Optionally improves a finished classic [result] for [request], on the decoder thread right
     * after [result] was published (so a separator never waits for it); only called when
     * [PredictRequest.engineAllowed] is set. Returns a replacement result for the same request, or
     * null to keep [result]. Must give up quickly (a few tens of ms at most) and return null once
     * [stale] turns true. The default does nothing.
     */
    fun refine(request: PredictRequest, result: PredictResult, stale: () -> Boolean): PredictResult? =
        null

    /**
     * Decodes a swipe (docs/PHASE5.md), always on the decoder thread (previews and the final decode
     * on lift, which the main thread only waits for briefly). [stale] works as in [predict]. The
     * default knows no gestures: an empty result, so nothing is committed.
     */
    fun predictGesture(request: GestureRequest, stale: () -> Boolean): PredictResult =
        PredictResult(emptyList(), null, null, sequence = request.sequence)

    /** Builds what a first swipe on [layout] needs ahead of time. Background thread; idempotent. */
    fun warmUpGestures(layout: KeyLayoutModel) = Unit
}

/** A text-expansion shortcut as the keyboard needs it. */
data class ShortcutRule(val expansion: String, val autoExpand: Boolean)

/**
 * What prediction needs from the rest of the app, so this module never sees SQLite, assets or
 * threads. The app implements it ([predictor] arrives asynchronously, once the dictionary is
 * loaded); tests fake it.
 */
interface PredictionBackend {
    /**
     * The engine, or null while it isn't available (loading, missing or broken dictionary). It may
     * be set from another thread, so implementations back it with snapshot state
     * (`mutableStateOf`): the keyboard reads it while composing (gesture typing is on only with an
     * engine) and must recompose when the engine arrives.
     */
    val predictor: Predictor?

    /** The shortcut whose trigger matches [trigger] (case-insensitive), or null. */
    fun shortcutFor(trigger: String): ShortcutRule?

    /** True if [word] is in the base dictionary or the user's dictionary. */
    fun isKnownWord(word: String): Boolean

    /** Adds [word] to the user dictionary. Must not block the caller. */
    fun addWord(word: String)

    /** No prediction at all: every key is committed directly. */
    object None : PredictionBackend {
        override val predictor: Predictor? = null

        override fun shortcutFor(trigger: String): ShortcutRule? = null

        override fun isKnownWord(word: String): Boolean = true

        override fun addWord(word: String) = Unit
    }
}
