plugins { id("keycorrect.android.benchmark") }

// Device microbenchmarks (androidx.benchmark): the hot paths measured under ART on the target
// hardware, which the desktop HotSpot profiling in tools/profile cannot represent. Everything lives
// in androidTest; run it with `./gradlew :benchmark:connectedReleaseAndroidTest` (see
// docs/HANDOFF.md). Microbenchmarks refuse to run in a debuggable APK, hence testBuildType.
android {
    namespace = "dev.keycorrect.benchmark"

    defaultConfig {
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
    }

    // The test APK is built from the release type (non-debuggable). AGP signs test APKs with the
    // debug key, so no signing config is needed.
    testBuildType = "release"

    // The shipped dictionary is stored uncompressed so it can be memory-mapped from the APK, as in
    // the app.
    androidResources { noCompress += "kcd" }

    sourceSets { getByName("androidTest") { assets.directories.add("../app/src/main/assets") } }
}

dependencies {
    androidTestImplementation(project(":core:decoder"))
    androidTestImplementation(project(":core:dictionary"))
    androidTestImplementation(project(":core:learning"))
    androidTestImplementation(project(":core:layout"))
    androidTestImplementation(project(":core:input"))
    androidTestImplementation(project(":core:engine-api"))
    androidTestImplementation(project(":feature:keyboard-ui"))
    androidTestImplementation(project(":core:emoji"))
    androidTestImplementation(libs.androidx.benchmark.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}
