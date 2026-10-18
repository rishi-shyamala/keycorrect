import java.util.Properties

plugins { id("keycorrect.android.application") }

// The voice pack APK (package dev.keycorrect.voice, docs/PHASE6.md): the speech models as
// uncompressed assets and the sherpa-onnx native libraries, extracted to disk so the keyboard can
// load them. It has no code and no permissions. Its inputs are downloaded by
// tools/data/fetch-voice.sh into the git-ignored data/raw/voice/; CI doesn't build it. Without
// those files the debug variant doesn't exist and the release build fails with a clear message.
val appVersion =
    Properties().apply { rootProject.file("version.properties").inputStream().use(::load) }

val voiceRaw = rootProject.layout.projectDirectory.dir("data/raw/voice")
val sherpaVersion = "1.13.8"
val moonshineDir =
    "x/sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27/sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27"
val jniDir = "x/sherpa-onnx-v$sherpaVersion-android/jniLibs"
val abis = listOf("arm64-v8a", "x86_64")
val nativeLibs = listOf("libonnxruntime.so", "libsherpa-onnx-jni.so")

/** Source file (under data/raw/voice) -> path inside the pack's assets. */
val assetFiles =
    mapOf(
        "$moonshineDir/encoder_model.ort" to "moonshine/encoder_model.ort",
        "$moonshineDir/decoder_model_merged.ort" to "moonshine/decoder_model_merged.ort",
        "$moonshineDir/tokens.txt" to "moonshine/tokens.txt",
        "silero_vad.onnx" to "silero_vad.onnx",
    )
val requiredFiles = assetFiles.keys + abis.flatMap { abi -> nativeLibs.map { "$jniDir/$abi/$it" } }

fun voiceFilesPresent() = requiredFiles.all { voiceRaw.file(it).asFile.isFile }

// Release signing: the same keycorrect.release.* Gradle properties as :app.
val releaseSigning =
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .associateWith { providers.gradleProperty("keycorrect.release.$it").orNull }
        .takeIf { props -> props.values.all { it != null } }

val stagedAssets = layout.buildDirectory.dir("voicepack/assets")
val stagedJniLibs = layout.buildDirectory.dir("voicepack/jniLibs")

val verifyVoiceFiles by tasks.registering {
    group = "verification"
    description = "Fails with instructions when the downloaded voice files are missing."
    val missing = requiredFiles.filterNot { voiceRaw.file(it).asFile.isFile }
    doLast {
        if (missing.isNotEmpty()) {
            throw GradleException(
                "The voice pack needs files that are not downloaded yet " +
                    "(${missing.size} missing, e.g. ${missing.first()}).\n" +
                    "Run tools/data/fetch-voice.sh first (docs/PHASE6.md)."
            )
        }
    }
}

val stageVoiceAssets by
    tasks.registering(Sync::class) {
        dependsOn(verifyVoiceFiles)
        into(stagedAssets)
        assetFiles.forEach { (src, dest) ->
            from(voiceRaw.file(src)) {
                into(dest.substringBeforeLast('/', ""))
                rename { dest.substringAfterLast('/') }
            }
        }
    }

val stageVoiceJniLibs by
    tasks.registering(Sync::class) {
        dependsOn(verifyVoiceFiles)
        into(stagedJniLibs)
        abis.forEach { abi ->
            nativeLibs.forEach { lib -> from(voiceRaw.file("$jniDir/$abi/$lib")) { into(abi) } }
        }
    }

android {
    namespace = "dev.keycorrect.voice"

    defaultConfig {
        applicationId = "dev.keycorrect.voice"
        versionCode = appVersion.getProperty("versionCode").toInt()
        versionName = appVersion.getProperty("versionName") + "-voice"
        ndk { abiFilters += abis }
    }

    // Models are read in place by sherpa-onnx through the AssetManager, so they stay uncompressed
    // (the keyboard checks with openFd, which only works for stored entries).
    androidResources { noCompress += listOf("ort", "onnx", "txt") }

    // The libraries are loaded by the keyboard from this package's native library directory, so
    // they must be extracted to disk at install.
    packaging { jniLibs { useLegacyPackaging = true } }

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

// Without the downloaded files (CI) only the release variant exists, so that
// `./gradlew assembleDebug` skips this module; building it then fails with the message above.
// A debug pack (debug key) pairs with the debug keyboard; a release pack with the release one.
androidComponents {
    beforeVariants { variant ->
        if (variant.buildType == "debug" && !voiceFilesPresent()) variant.enable = false
    }
}

tasks.configureEach {
    if (name == "preBuild") dependsOn(stageVoiceAssets, stageVoiceJniLibs)
}

// No code, so no Kotlin runtime either (AGP adds the stdlib by default).
configurations
    .matching { it.name.endsWith("RuntimeClasspath") }
    .configureEach {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains", module = "annotations")
    }
