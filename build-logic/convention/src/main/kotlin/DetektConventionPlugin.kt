import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Detekt on one module, with type resolution (the `detektMain`-style tasks), so rules such as
 * UnusedPrivateFunction work. Shared config in `config/detekt/detekt.yml`; known findings live in
 * the module's `detekt-baseline-*.xml` and anything new fails the build. Regenerate a baseline only
 * when deliberately accepting findings.
 */
class DetektConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("dev.detekt")
            extensions.configure<DetektExtension> {
                buildUponDefaultConfig.set(true)
                config.setFrom(rootProject.file("config/detekt/detekt.yml"))
                baseline.set(file("detekt-baseline.xml"))
                parallel.set(true)
                // Release has the same sources as debug; analysing both only duplicates baselines.
                ignoredBuildTypes.set(listOf("release"))
            }
        }
}
