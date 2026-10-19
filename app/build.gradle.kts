import java.util.Properties

plugins {
    id("keycorrect.android.application")
    id("keycorrect.android.compose")
}

// The version lives in version.properties; every build appends the commit it was built from, so
// Settings → About shows exactly which build is installed.
val appVersion =
    Properties().apply { rootProject.file("version.properties").inputStream().use(::load) }

/** Output of a git command, or null if git is missing, the command fails or this isn't a repo. */
fun git(vararg args: String): String? = runCatching {
    providers
        .exec {
            commandLine("git", *args)
            isIgnoreExitValue = true
        }
        .standardOutput
        .asText
        .get()
        .trim()
}
    .getOrNull()

// "<short sha>", plus "-dirty" when tracked files have uncommitted changes; empty without git.
val gitCommit: String =
    git("rev-parse", "--short", "HEAD").orEmpty().let { sha ->
        val dirty =
            sha.isNotEmpty() &&
                git("status", "--porcelain", "--untracked-files=no")?.isNotEmpty() == true
        if (dirty) "$sha-dirty" else sha
    }

// Release signing comes from Gradle properties outside the repo (~/.gradle/gradle.properties,
// docs/INSTALL.md). It is configured only when all four exist; otherwise the release APK is built
// unsigned (as on CI).
val releaseSigning =
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .associateWith { providers.gradleProperty("keycorrect.release.$it").orNull }
        .takeIf { props -> props.values.all { it != null } }

android {
    namespace = "dev.keycorrect"

    buildFeatures { buildConfig = true }

    // The compiled dictionary is memory-mapped straight out of the APK, so it must be stored
    // uncompressed.
    androidResources { noCompress += "kcd" }

    defaultConfig {
        applicationId = "dev.keycorrect"
        versionCode = appVersion.getProperty("versionCode").toInt()
        versionName =
            appVersion.getProperty("versionName") +
                (if (gitCommit.isNotEmpty()) "+$gitCommit" else "")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Target devices are arm64; x86_64 kept for emulators. Drops ~5 MB of unused SQLCipher
        // native code. 32-bit-only devices (armeabi-v7a, x86) are intentionally unsupported.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        releaseSigning?.let { props ->
            create("release") {
                storeFile = file(props.getValue("storeFile")!!)
                storePassword = props.getValue("storePassword")
                keyAlias = props.getValue("keyAlias")
                keyPassword = props.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Lets a debug build sit alongside a release build on the same device.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
    implementation(project(":core:clipboard"))
    implementation(project(":core:data"))
    implementation(project(":core:emoji"))
    implementation(project(":core:decoder"))
    implementation(project(":core:engine-api"))
    implementation(project(":core:engine-sdk"))
    implementation(project(":core:packs"))
    implementation(project(":core:input"))
    implementation(project(":core:layout"))
    implementation(project(":core:learning"))
    implementation(project(":core:settings"))
    implementation(project(":core:transfer"))
    implementation(project(":core:voice"))
    implementation(project(":feature:voice-sherpa"))
    implementation(project(":feature:keyboard-ui"))
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.compose.foundation)

    testImplementation(libs.kotlinx.coroutines.test)

    // Security test (PHASE4 PR C exit criterion): run on a device with
    // :app:connectedDebugAndroidTest.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.truth)
}
