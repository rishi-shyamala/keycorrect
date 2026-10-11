import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("keycorrect.detekt")
            extensions.configure<ApplicationExtension> {
                compileSdk = KeyCorrectBuild.COMPILE_SDK
                ndkVersion = KeyCorrectBuild.NDK_VERSION
                defaultConfig {
                    minSdk = KeyCorrectBuild.MIN_SDK
                    targetSdk = KeyCorrectBuild.TARGET_SDK
                }
                compileOptions {
                    sourceCompatibility = KeyCorrectBuild.JAVA_VERSION
                    targetCompatibility = KeyCorrectBuild.JAVA_VERSION
                }
                testOptions { unitTests.isReturnDefaultValues = true }
            }
            addTestDependencies()
        }
}

internal fun Project.addTestDependencies() {
    dependencies {
        add("testImplementation", libs.findLibrary("junit").get())
        add("testImplementation", libs.findLibrary("truth").get())
    }
}
