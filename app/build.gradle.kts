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
    implementation(project(":core:input"))
    implementation(project(":core:layout"))
    implementation(project(":core:settings"))
    implementation(project(":feature:keyboard-ui"))
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.compose.foundation)
}
