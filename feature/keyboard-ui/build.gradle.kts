plugins {
    id("keycorrect.android.library")
    id("keycorrect.android.compose")
}

android { namespace = "dev.keycorrect.feature.keyboard" }

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:input"))
    api(project(":core:engine-api"))
    api(project(":core:layout"))
    // Public API: KeyboardController takes a LearningSink and a DeviceClass.
    api(project(":core:learning"))
    api(project(":core:settings"))
    implementation(libs.compose.foundation)

    // The real ClassicEngine drives the end-to-end controller test; the app module wires it up.
    testImplementation(project(":core:decoder"))
}
