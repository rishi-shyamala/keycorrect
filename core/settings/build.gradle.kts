plugins { id("keycorrect.android.library") }

android { namespace = "dev.keycorrect.core.settings" }

dependencies {
    api(libs.androidx.datastore.preferences)
    testImplementation(libs.kotlinx.coroutines.test)
}
