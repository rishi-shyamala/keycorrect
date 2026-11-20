package dev.keycorrect.core.swipenet

import java.io.Closeable
import java.io.File

/**
 * The encoder's three outputs for one gesture, as the model returns them (row-major, batch 1).
 *
 * @property logEmissions [STEPS] x [CLASSES] log-probabilities over the layout's key slots plus the
 *   blank ([BLANK]); slots past the layout's keys are padding.
 * @property coefficients [STEPS] x [COEFFICIENTS] spectral coefficients.
 * @property lambda [STEPS] values of the intention gate.
 */
class SwipeEmissions(
    val logEmissions: FloatArray,
    val coefficients: FloatArray,
    val lambda: FloatArray,
) {
    /** The log-probability of class [cls] at step [step]. */
    fun logProb(step: Int, cls: Int): Float = logEmissions[step * CLASSES + cls]

    companion object {
        const val STEPS = 32
        const val CLASSES = SwipeLayout.MAX_KEYS + 1
        const val BLANK = SwipeLayout.MAX_KEYS
        const val COEFFICIENTS = 64

        /** Floats the native call writes: the three outputs back to back. */
        const val SIZE = STEPS * CLASSES + STEPS * COEFFICIENTS + STEPS

        internal fun split(out: FloatArray): SwipeEmissions {
            val e = STEPS * CLASSES
            val c = e + STEPS * COEFFICIENTS
            return SwipeEmissions(
                out.copyOfRange(0, e),
                out.copyOfRange(e, c),
                out.copyOfRange(c, SIZE),
            )
        }
    }
}

/** The encoder could not be opened. */
class SwipeNetException(message: String) : Exception(message)

/**
 * FUTO Swipe's encoder, loaded from a `.pte` (docs/PHASE10.md, 10a-2). [emissions] may be called
 * from any thread; calls are serialised. The native library must be loaded first
 * ([SwipeNetLibrary], or the swipe pack's locator on Android).
 */
class SwipeEncoder
private constructor(private val backend: SwipeNetBackend, private var handle: Long) : Closeable {
    /**
     * Runs the encoder on [features] (from [SwipeResampler] or [SwipeLayout.features]) against the
     * [layout]'s keys. Null when the encoder is closed or the native call fails.
     */
    fun emissions(features: FloatArray, layout: SwipeLayout): SwipeEmissions? =
        emissions(features, layout.keys, layout.mask)

    /**
     * The same with the raw `layout_keys` (`64 * 2` floats) and `layout_mask` (64 bytes) inputs.
     */
    @Synchronized
    fun emissions(features: FloatArray, keys: FloatArray, mask: ByteArray): SwipeEmissions? {
        require(features.size == 2 * SwipeResampler.POINTS) { "features: ${features.size}" }
        require(keys.size == 2 * SwipeLayout.MAX_KEYS) { "keys: ${keys.size}" }
        require(mask.size == SwipeLayout.MAX_KEYS) { "mask: ${mask.size}" }
        if (handle == 0L) return null
        val out = FloatArray(SwipeEmissions.SIZE)
        return if (backend.run(handle, features, keys, mask, out)) SwipeEmissions.split(out)
        else null
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) backend.close(handle)
        handle = 0L
    }

    companion object {
        /**
         * Opens the encoder from the bytes of its `.pte` (the native side keeps its own copy).
         * [threads] sizes XNNPACK's process-wide pool; the encoder is small, so one is the default.
         * The pool is sized once per process: the first open's count wins, later counts are
         * ignored.
         */
        @Throws(SwipeNetException::class)
        fun open(
            model: ByteArray,
            threads: Int = 1,
            backend: SwipeNetBackend = SwipeNetNative,
        ): SwipeEncoder {
            val handle = backend.open(model, threads)
            if (handle == 0L) throw SwipeNetException("the encoder could not be loaded")
            return SwipeEncoder(backend, handle)
        }

        /**
         * The desktop path (tools/eval, tests): loads the host build of the library from [library]
         * (`tools/swipenet/build.sh host`) and opens the model file at [model].
         */
        @Throws(SwipeNetException::class)
        fun openDesktop(library: String, model: File, threads: Int = 1): SwipeEncoder {
            SwipeNetLibrary.load(library)
            return open(model.readBytes(), threads)
        }
    }
}
