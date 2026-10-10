plugins { id("keycorrect.android.library") }

android {
    namespace = "dev.keycorrect.core.data"
    defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
}

dependencies {
    // Public API exposes Flow (stores) and SupportSQLiteDatabase (KeyCorrectDatabase.transaction).
    api(libs.kotlinx.coroutines.android)
    api(libs.androidx.sqlite)
    api(project(":core:learning"))
    implementation(libs.sqlcipher.android)

    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.truth)
}
