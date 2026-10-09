plugins {
    id("keycorrect.android.library")
    id("keycorrect.android.compose")
}

android { namespace = "dev.keycorrect.feature.keyboard" }

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:input"))
    implementation(libs.compose.foundation)
}
