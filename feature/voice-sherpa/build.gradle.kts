plugins { id("keycorrect.android.library") }

android {
    namespace = "dev.keycorrect.feature.voicesherpa"
    // The JNI library looks the vendored sherpa-onnx classes up by name: the app's R8 must keep
    // them.
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
}

dependencies {
    // SttEngine and friends are this module's public API.
    api(project(":core:voice"))
}
