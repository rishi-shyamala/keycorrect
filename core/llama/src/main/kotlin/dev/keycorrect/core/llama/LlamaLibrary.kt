package dev.keycorrect.core.llama

/**
 * Loads `libkcllama.so` once per process. [LlamaNative] does not load it itself: the Android app
 * loads it from the neural pack by absolute path (the pack is a separate, signed APK), the desktop
 * tools and tests from the output of `tools/llama/build-host.sh`.
 */
object LlamaLibrary {
    private var path: String? = null

    /** The path the library was loaded from, or null if [load] has not succeeded yet. */
    @get:Synchronized
    val loadedPath: String?
        get() = path

    /** True once [load] has succeeded. */
    val isLoaded: Boolean
        get() = loadedPath != null

    /**
     * Loads the library at the absolute [path] with [System.load]. Loading the same path again does
     * nothing. A different path is refused: a process can load one copy, and a pack that changed on
     * disk needs a restart.
     */
    @Synchronized
    fun load(path: String) {
        val current = this.path
        if (current != null) {
            check(current == path) { "libkcllama is already loaded from $current" }
            return
        }
        System.load(path)
        this.path = path
    }
}
