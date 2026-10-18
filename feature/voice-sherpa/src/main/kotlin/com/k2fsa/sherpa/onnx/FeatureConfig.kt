// Copyright (c) Xiaomi Corporation. Licensed under the Apache License, Version 2.0
// (https://www.apache.org/licenses/LICENSE-2.0). From k2-fsa/sherpa-onnx v1.13.8,
// sherpa-onnx/kotlin-api/FeatureConfig.kt.
// Modified for KeyCorrect:
//  - Unchanged apart from this header
// The package name must stay as is: the JNI symbols in libsherpa-onnx-jni.so depend on it.
package com.k2fsa.sherpa.onnx

data class FeatureConfig(
    var sampleRate: Int = 16000,
    var featureDim: Int = 80,
    var dither: Float = 0.0f,
)

fun getFeatureConfig(sampleRate: Int, featureDim: Int): FeatureConfig {
    return FeatureConfig(sampleRate = sampleRate, featureDim = featureDim)
}
