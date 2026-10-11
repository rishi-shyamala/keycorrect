plugins { `kotlin-dsl` }

dependencies {
    // implementation (not compileOnly) so these exact versions are on the build classpath;
    // this also upgrades the Kotlin Gradle plugin used by AGP's built-in Kotlin support.
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.compose.compiler.gradle.plugin)
    implementation(libs.kotlin.serialization.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
    implementation(libs.androidx.benchmark.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "keycorrect.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "keycorrect.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("jvmLibrary") {
            id = "keycorrect.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("kotlinSerialization") {
            id = "keycorrect.kotlin.serialization"
            implementationClass = "KotlinSerializationConventionPlugin"
        }
        register("androidBenchmark") {
            id = "keycorrect.android.benchmark"
            implementationClass = "AndroidBenchmarkConventionPlugin"
        }
        register("detekt") {
            id = "keycorrect.detekt"
            implementationClass = "DetektConventionPlugin"
        }
        register("androidCompose") {
            id = "keycorrect.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
    }
}
