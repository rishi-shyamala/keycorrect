plugins { id("keycorrect.baselineprofile.producer") }

// Baseline profile generator and startup/typing Macrobenchmarks for :app (docs/PERF.md). Both run
// instrumented against the release-like build of the app and need a device, so they are on demand
// only: `./gradlew :app:generateBaselineProfile` and
// `:baselineprofile:connectedBenchmarkAndroidTest`. Normal builds (assembleDebug, lint, tests)
// only compile this module.
android {
    namespace = "dev.keycorrect.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
}

// Use whatever device is attached (ANDROID_SERIAL picks it); no managed device.
baselineProfile { useConnectedDevices = true }

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
