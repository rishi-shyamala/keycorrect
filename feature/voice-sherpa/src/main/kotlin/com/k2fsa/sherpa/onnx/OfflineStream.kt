// Copyright (c) Xiaomi Corporation. Licensed under the Apache License, Version 2.0
// (https://www.apache.org/licenses/LICENSE-2.0). From k2-fsa/sherpa-onnx v1.13.8,
// sherpa-onnx/kotlin-api/OfflineStream.kt.
// Modified for KeyCorrect:
//  - System.loadLibrary calls removed (VoicePackLocator loads the libraries)
// The package name must stay as is: the JNI symbols in libsherpa-onnx-jni.so depend on it.
package com.k2fsa.sherpa.onnx

class OfflineStream(var ptr: Long) {
    init {
        require(ptr != 0L) { "Failed to create native OfflineStream" }
    }

    fun acceptWaveform(samples: FloatArray, sampleRate: Int) =
        acceptWaveform(ptr, samples, sampleRate)

    fun setOption(key: String, value: String) = setOption(ptr, key, value)

    fun getOption(key: String): String = getOption(ptr, key)

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    fun use(block: (OfflineStream) -> Unit) {
        try {
            block(this)
        } finally {
            release()
        }
    }

    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)

    private external fun setOption(ptr: Long, key: String, value: String)

    private external fun getOption(ptr: Long, key: String): String

    private external fun delete(ptr: Long)
}
