plugins {
    id("keycorrect.android.application")
    id("keycorrect.android.compose")
}

android {
    namespace = "dev.keycorrect"

    buildFeatures { buildConfig = true }

    // The compiled dictionary is memory-mapped straight out of the APK, so it must be stored
    // uncompressed.
    androidResources { noCompress += "kcd" }

    defaultConfig {
        applicationId = "dev.keycorrect"
        versionCode = 1
        versionName = "0.0.1"
        // Target devices are arm64; x86_64 kept for emulators. Drops ~5 MB of unused SQLCipher
        // native code. 32-bit-only devices (armeabi-v7a, x86) are intentionally unsupported.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        debug {
            // Lets a debug build sit alongside a release build on the same device.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:ime"))
    implementation(project(":core:data"))
    implementation(project(":core:decoder"))
    implementation(project(":core:engine-api"))
    implementation(project(":core:input"))
    implementation(project(":core:layout"))
    implementation(project(":core:learning"))
    implementation(project(":core:settings"))
    implementation(project(":feature:keyboard-ui"))
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.compose.foundation)

    testImplementation(libs.kotlinx.coroutines.test)
}
