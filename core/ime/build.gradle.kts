plugins {
    id("keycorrect.android.library")
    id("keycorrect.android.compose")
}

android { namespace = "dev.keycorrect.core.ime" }

dependencies {
    api(libs.androidx.lifecycle.runtime)
    api(libs.androidx.lifecycle.viewmodel)
    api(libs.androidx.savedstate)
}
