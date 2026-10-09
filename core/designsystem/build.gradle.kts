plugins {
    id("keycorrect.android.library")
    id("keycorrect.android.compose")
}

android { namespace = "dev.keycorrect.core.designsystem" }

dependencies { api(libs.compose.material3) }
