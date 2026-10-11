import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Device microbenchmark module: an Android library whose androidTest APK runs androidx.benchmark.
 * The androidx.benchmark plugin `speed`-compiles the test APK before the run (the library refuses
 * to measure JIT-only code). On build-logic's classpath because it needs AGP.
 */
class AndroidBenchmarkConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("keycorrect.android.library")
            pluginManager.apply("androidx.benchmark")
        }
}
