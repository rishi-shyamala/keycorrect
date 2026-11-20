import java.util.Properties

plugins { id("keycorrect.android.application") }

// The swipe pack APK (package dev.keycorrect.swipe, docs/PHASE10.md 10a-2): FUTO Swipe's encoder
// and its licence as uncompressed assets, and libkcswipenet.so (the JNI glue with ExecuTorch built
// in), extracted to disk so the keyboard can load it. It has no code and no permissions. Its
// inputs: the model from tools/data/fetch-swipe-model.sh (git-ignored data/raw/swipenet/) and the
// libraries from `tools/swipenet/build.sh android` (build/swipenet-android/); CI doesn't build it.
// Without those files the debug variant doesn't exist and the release build fails with a clear
// message.
val appVersion =
    Properties().apply { rootProject.file("version.properties").inputStream().use(::load) }

val swipeRaw = rootProject.layout.projectDirectory.dir("data/raw/swipenet")
val swipeLibs = rootProject.layout.projectDirectory.dir("build/swipenet-android")
val abis = listOf("arm64-v8a", "x86_64")
val nativeLib = "libkcswipenet.so"

/** Source file (under data/raw/swipenet) -> path inside the pack's assets. */
val assetFiles =
    mapOf(
        "honorable_sturgeon/model_int8.pte" to "swipenet/model_int8.pte",
        "LICENSE.md" to "swipenet/LICENSE.md",
    )
val requiredFiles =
    assetFiles.keys.map { swipeRaw.file(it).asFile } +
        abis.map { swipeLibs.file("$it/$nativeLib").asFile }

fun swipeFilesPresent() = requiredFiles.all { it.isFile }

// Release signing: the same keycorrect.release.* Gradle properties as :app.
val releaseSigning =
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .associateWith { providers.gradleProperty("keycorrect.release.$it").orNull }
        .takeIf { props -> props.values.all { it != null } }

val stagedAssets = layout.buildDirectory.dir("swipepack/assets")
val stagedJniLibs = layout.buildDirectory.dir("swipepack/jniLibs")

val verifySwipeFiles by tasks.registering {
    group = "verification"
    description = "Fails with instructions when the swipe pack's inputs are missing."
    val missing = requiredFiles.filterNot { it.isFile }
    doLast {
        if (missing.isNotEmpty()) {
            throw GradleException(
                "The swipe pack needs files that are not there yet " +
                    "(${missing.size} missing, e.g. ${missing.first()}).\n" +
                    "Run tools/data/fetch-swipe-model.sh, then tools/swipenet/build.sh android " +
                    "(docs/DEVELOPMENT.md)."
            )
        }
    }
}

val stageSwipeAssets by
    tasks.registering(Sync::class) {
        dependsOn(verifySwipeFiles)
        into(stagedAssets)
        assetFiles.forEach { (src, dest) ->
            from(swipeRaw.file(src)) {
                into(dest.substringBeforeLast('/', ""))
                rename { dest.substringAfterLast('/') }
            }
        }
    }

val stageSwipeJniLibs by
    tasks.registering(Sync::class) {
        dependsOn(verifySwipeFiles)
        into(stagedJniLibs)
        abis.forEach { abi -> from(swipeLibs.file("$abi/$nativeLib")) { into(abi) } }
    }

android {
    namespace = "dev.keycorrect.swipe"

    defaultConfig {
        applicationId = "dev.keycorrect.swipe"
        versionCode = appVersion.getProperty("versionCode").toInt()
        versionName = appVersion.getProperty("versionName") + "-swipe"
        ndk { abiFilters += abis }
    }

    // The keyboard checks the assets with openFd, which only works for stored entries.
    androidResources { noCompress += listOf("pte", "md") }

    // The library is loaded by the keyboard from this package's native library directory, so it
    // must be extracted to disk at install. It is stripped already (tools/swipenet/build.sh).
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/$nativeLib"
        }
    }

    sourceSets {
        getByName("main") {
            assets.directories.add(stagedAssets.get().asFile.path)
            jniLibs.directories.add(stagedJniLibs.get().asFile.path)
        }
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
        release { signingConfigs.findByName("release")?.let { signingConfig = it } }
    }
}

// Without the inputs (CI) only the release variant exists, so that `./gradlew assembleDebug` skips
// this module; building it then fails with the message above. A debug pack (debug key) pairs with
// the debug keyboard; a release pack with the release one.
androidComponents {
    beforeVariants { variant ->
        if (variant.buildType == "debug" && !swipeFilesPresent()) variant.enable = false
    }
}

tasks.configureEach {
    if (name == "preBuild") dependsOn(stageSwipeAssets, stageSwipeJniLibs)
}

// No code, so no Kotlin runtime either (AGP adds the stdlib by default).
configurations
    .matching { it.name.endsWith("RuntimeClasspath") }
    .configureEach {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains", module = "annotations")
    }
