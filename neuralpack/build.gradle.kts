import java.util.Properties

plugins { id("keycorrect.android.application") }

// The neural pack APK (package dev.keycorrect.neural, docs/PHASE7.md): llama.cpp plus the JNI glue
// built with the NDK into libkcllama.so, and the default language model as an uncompressed asset.
// It has no code and no permissions. Its inputs are downloaded by tools/data/fetch-neural.sh into
// the git-ignored data/raw/neural/; CI doesn't build it. Without those files the debug variant
// doesn't exist and the release build fails with a clear message.
val appVersion =
    Properties().apply { rootProject.file("version.properties").inputStream().use(::load) }

val neuralRaw = rootProject.layout.projectDirectory.dir("data/raw/neural")
val llamaSrcRelative = "x/llama.cpp-v0.7.0/llama.cpp-0.7.0"
val modelFile = "SmolLM2-135M-Q8_0.gguf"
val modelAsset = "models/default.gguf"
val abis = listOf("arm64-v8a", "x86_64")
val requiredFiles = listOf(modelFile, "$llamaSrcRelative/CMakeLists.txt")

fun neuralFilesPresent() = requiredFiles.all { neuralRaw.file(it).asFile.isFile }

// Release signing: the same keycorrect.release.* Gradle properties as :app.
val releaseSigning =
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .associateWith { providers.gradleProperty("keycorrect.release.$it").orNull }
        .takeIf { props -> props.values.all { it != null } }

val stagedAssets = layout.buildDirectory.dir("neuralpack/assets")

val verifyNeuralFiles by tasks.registering {
    group = "verification"
    description = "Fails with instructions when the downloaded neural files are missing."
    val missing = requiredFiles.filterNot { neuralRaw.file(it).asFile.isFile }
    doLast {
        if (missing.isNotEmpty()) {
            throw GradleException(
                "The neural pack needs files that are not downloaded yet " +
                    "(${missing.size} missing, e.g. ${missing.first()}).\n" +
                    "Run tools/data/fetch-neural.sh first (docs/PHASE7.md)."
            )
        }
    }
}

val stageNeuralAssets by
    tasks.registering(Sync::class) {
        dependsOn(verifyNeuralFiles)
        into(stagedAssets)
        from(neuralRaw.file(modelFile)) {
            into(modelAsset.substringBeforeLast('/'))
            rename { modelAsset.substringAfterLast('/') }
        }
    }

android {
    namespace = "dev.keycorrect.neural"

    defaultConfig {
        applicationId = "dev.keycorrect.neural"
        versionCode = appVersion.getProperty("versionCode").toInt()
        versionName = appVersion.getProperty("versionName") + "-neural"
        ndk { abiFilters += abis }
        externalNativeBuild {
            cmake {
                // Release-optimised native code in every variant: a keyboard needs the speed.
                // Everything is static and nothing is fetched from the network (KleidiAI would
                // download its sources, so it stays off). ARMv8.2-A with dot product and half
                // precision runs on every Snapdragon 8 Gen 2 class phone and on older 8.2-A parts;
                // i8mm is left out because Cortex-A76/A77/A78 class cores lack it.
                arguments +=
                    listOf(
                        "-DLLAMA_SRC=${neuralRaw.dir(llamaSrcRelative).asFile.path}",
                        "-DCMAKE_BUILD_TYPE=Release",
                        "-DANDROID_STL=c++_static",
                        "-DGGML_CPU_KLEIDIAI=OFF",
                        "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16",
                    )
                cFlags += "-O3"
                cppFlags += "-O3"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../core/llama/src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    // The model is mapped in place by the engine, so it stays uncompressed (stored entry).
    androidResources { noCompress += listOf("gguf") }

    // The library is loaded by the keyboard from this package's native library directory, so it
    // must be extracted to disk at install.
    packaging { jniLibs { useLegacyPackaging = true } }

    sourceSets { getByName("main") { assets.directories.add(stagedAssets.get().asFile.path) } }

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
        if (variant.buildType == "debug" && !neuralFilesPresent()) variant.enable = false
    }
}

tasks.configureEach {
    if (name == "preBuild") dependsOn(stageNeuralAssets)
}

// No code, so no Kotlin runtime either (AGP adds the stdlib by default).
configurations
    .matching { it.name.endsWith("RuntimeClasspath") }
    .configureEach {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains", module = "annotations")
    }
