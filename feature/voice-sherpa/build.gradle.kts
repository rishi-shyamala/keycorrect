plugins { id("keycorrect.android.library") }

android { namespace = "dev.keycorrect.feature.voicesherpa" }

dependencies {
    // SttEngine and friends are this module's public API.
    api(project(":core:voice"))
}
