package dev.keycorrect.core.swipenet

/**
 * The calls the native library offers; [SwipeNetNative] is the real one. It exists so the wrapper
 * can be tested without the library. Handles are native pointers as [Long]; 0 means failure.
 */
interface SwipeNetBackend {
    /**
     * Loads an ExecuTorch program (the bytes of a `.pte`) and checks its outputs with one dry run.
     * [threads] > 0 sizes the process-wide XNNPACK thread pool, once: the first open with a
     * positive count wins for the process and later counts are ignored (resizing would free a pool
     * that open encoders still point to). Returns a handle or 0.
     */
    fun open(model: ByteArray, threads: Int): Long

    /**
     * Runs the encoder: [features] `2 * 64` floats, [keys] `64 * 2` floats, [mask] 64 bytes
     * (non-zero = real key). Writes log emissions, coefficients and lambda, in that order, into
     * [out] ([SwipeEmissions.SIZE] floats). False on any error.
     */
    fun run(
        handle: Long,
        features: FloatArray,
        keys: FloatArray,
        mask: ByteArray,
        out: FloatArray,
    ): Boolean

    fun close(handle: Long)
}

/**
 * The JNI surface of `libkcswipenet.so` (`core/swipenet/src/main/cpp/kc_swipenet_jni.cpp`); see
 * [SwipeNetBackend]. Nothing here tracks lifetimes or threads: use [SwipeEncoder].
 *
 * Loading the library is the caller's job (see [SwipeNetLibrary]): Android loads it from the swipe
 * pack by absolute path, the desktop from a build directory.
 */
object SwipeNetNative : SwipeNetBackend {
    external override fun open(model: ByteArray, threads: Int): Long

    external override fun run(
        handle: Long,
        features: FloatArray,
        keys: FloatArray,
        mask: ByteArray,
        out: FloatArray,
    ): Boolean

    external override fun close(handle: Long)
}

/**
 * Loads `libkcswipenet.so` once per process. Android loads it from the swipe pack through the
 * signature-checked pack locator; the desktop tools and tests from the output of
 * `tools/swipenet/build.sh host`.
 */
object SwipeNetLibrary {
    private var path: String? = null

    /** The path the library was loaded from, or null if [load] has not succeeded yet. */
    @get:Synchronized
    val loadedPath: String?
        get() = path

    /**
     * Loads the library at the absolute [path] with [System.load]. Loading the same path again does
     * nothing; a different path is refused (a process can load one copy).
     */
    @Synchronized
    fun load(path: String) {
        val current = this.path
        if (current != null) {
            check(current == path) { "libkcswipenet is already loaded from $current" }
            return
        }
        System.load(path)
        this.path = path
    }
}
