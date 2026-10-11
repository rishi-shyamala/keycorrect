import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** Pure Kotlin/JVM module: no Android dependencies, fast plain-JVM unit tests. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            pluginManager.apply("keycorrect.detekt")
            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = KeyCorrectBuild.JAVA_VERSION
                targetCompatibility = KeyCorrectBuild.JAVA_VERSION
            }
            extensions.configure<KotlinJvmProjectExtension> {
                compilerOptions.jvmTarget.set(
                    JvmTarget.fromTarget(KeyCorrectBuild.JAVA_VERSION.toString())
                )
            }
            addTestDependencies()
        }
}
