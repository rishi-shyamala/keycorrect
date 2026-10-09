import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Adds kotlinx.serialization (compiler plugin + JSON runtime). */
class KotlinSerializationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
            dependencies {
                add("implementation", libs.findLibrary("kotlinx-serialization-json").get())
            }
        }
}
