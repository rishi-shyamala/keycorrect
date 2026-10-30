plugins {
    id("keycorrect.android.library")
    id("keycorrect.android.compose")
}

android {
    namespace = "dev.keycorrect.feature.keyboard"
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    // KeystrokeBenchmarkTest runs only when profiling (tools/profile/profile.sh):
    // -Pkeycorrect.bench=true, plus -Pkeycorrect.jfr=<file> to record it with Flight Recorder.
    testOptions.unitTests.all { test ->
        val bench = providers.gradleProperty("keycorrect.bench").orNull
        if (bench != null) test.systemProperty("keycorrect.bench", bench)
        providers.gradleProperty("keycorrect.jfr").orNull?.let {
            test.jvmArgs("-XX:StartFlightRecording=filename=$it,settings=profile")
        }
        // LearningCurveTest (slow, key-by-key learning curves) runs only with
        // -Pkeycorrect.learncurve=true; the neural arm also needs the host build of the native
        // library and a GGUF (found in the main checkout; override with
        // -Pkeycorrect.learncurve.lib / .model).
        providers.gradleProperty("keycorrect.learncurve").orNull?.let {
            test.systemProperty("keycorrect.learncurve", it)
            providers.gradleProperty("keycorrect.learncurve.lib").orNull?.let { lib ->
                test.systemProperty("keycorrect.learncurve.lib", lib)
            }
            providers.gradleProperty("keycorrect.learncurve.model").orNull?.let { model ->
                test.systemProperty("keycorrect.learncurve.model", model)
            }
            test.maxHeapSize = "2g"
        }
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:input"))
    api(project(":core:engine-api"))
    api(project(":core:layout"))
    // Public API: KeyboardController takes a LearningSink and a DeviceClass.
    api(project(":core:learning"))
    api(project(":core:settings"))
    // Public API: KeyboardController takes an EmojiEnvironment, which exposes the catalog.
    api(project(":core:emoji"))
    // Public API: KeyboardController takes a VoiceEnvironment, which hands out SttEngines.
    api(project(":core:voice"))
    implementation(libs.compose.foundation)

    // The real ClassicEngine drives the end-to-end controller test; the app module wires it up.
    testImplementation(project(":core:decoder"))
    // The learning-curve test's optional neural arm (reranker over a host llama session).
    testImplementation(project(":core:llama"))

    // On-device UI tests (what the strip actually draws).
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.compose.ui.test.manifest)
}
