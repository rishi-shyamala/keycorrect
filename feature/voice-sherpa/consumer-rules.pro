# The vendored sherpa-onnx Kotlin API (package com.k2fsa.sherpa.onnx) is looked up by name from
# libsherpa-onnx-jni.so (FindClass / GetFieldID / GetMethodID), so R8 must neither strip nor rename
# it, its fields or its native methods.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
