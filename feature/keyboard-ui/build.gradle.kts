plugins {
    id("keycorrect.android.library")
    id("keycorrect.android.compose")
}

android { namespace = "dev.keycorrect.feature.keyboard" }

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:input"))
    api(project(":core:layout"))
    api(project(":core:settings"))
    implementation(libs.compose.foundation)
}
