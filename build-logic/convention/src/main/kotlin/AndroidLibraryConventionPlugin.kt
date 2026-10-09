import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("com.android.library")
            extensions.configure<LibraryExtension> {
                compileSdk = KeyCorrectBuild.COMPILE_SDK
                ndkVersion = KeyCorrectBuild.NDK_VERSION
                defaultConfig { minSdk = KeyCorrectBuild.MIN_SDK }
                compileOptions {
                    sourceCompatibility = KeyCorrectBuild.JAVA_VERSION
                    targetCompatibility = KeyCorrectBuild.JAVA_VERSION
                }
                testOptions { unitTests.isReturnDefaultValues = true }
            }
            addTestDependencies()
        }
}
