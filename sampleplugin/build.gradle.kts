import java.util.Properties

plugins { id("keycorrect.android.application") }

// A sample text-engine plugin (package dev.keycorrect.sampleplugin, docs/PLUGINS.md): a toy bigram
// scorer built on :core:engine-sdk. It needs no downloaded data, so CI builds it with
// `assembleDebug`. It has no permissions (not even INTERNET) and no launcher activity.
val appVersion =
    Properties().apply { rootProject.file("version.properties").inputStream().use(::load) }

// Release signing: the same keycorrect.release.* Gradle properties as :app and the packs. When they
// are missing (CI), the release APK is simply left unsigned.
val releaseSigning =
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .associateWith { providers.gradleProperty("keycorrect.release.$it").orNull }
        .takeIf { props -> props.values.all { it != null } }

android {
    namespace = "dev.keycorrect.sampleplugin"

    defaultConfig {
        applicationId = "dev.keycorrect.sampleplugin"
        versionCode = appVersion.getProperty("versionCode").toInt()
        versionName = appVersion.getProperty("versionName") + "-sample"
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

dependencies { implementation(project(":core:engine-sdk")) }
