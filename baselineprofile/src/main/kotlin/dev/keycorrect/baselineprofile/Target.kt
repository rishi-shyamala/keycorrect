package dev.keycorrect.baselineprofile

import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * The app under test. It is the ".benchmark" build (applicationIdSuffix in :app), never the
 * released dev.keycorrect: the test APK uninstalls it at the end, which would wipe a real
 * installation's data, and it replaces it on install.
 */
internal object Target {
    const val PACKAGE = "dev.keycorrect.benchmark"
    const val RELEASE_PACKAGE = "dev.keycorrect"

    fun requireSafe() {
        require(PACKAGE != RELEASE_PACKAGE) { "Refusing to benchmark the released app" }
    }
}

internal fun shell(cmd: String): String =
    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd).use { fd ->
        FileInputStream(fd.fileDescriptor).bufferedReader().readText()
    }

/**
 * Saves the device's keyboard settings before a test and restores them afterwards, also when the
 * test fails, so a run never leaves the device on the benchmark keyboard.
 */
class ImeGuard : TestRule {
    override fun apply(base: Statement, description: Description) =
        object : Statement() {
            override fun evaluate() {
                Target.requireSafe()
                val default = shell("settings get secure default_input_method").trim()
                val enabled = shell("settings get secure enabled_input_methods").trim()
                try {
                    base.evaluate()
                } finally {
                    if (enabled.isNotEmpty() && enabled != "null") {
                        shell("settings put secure enabled_input_methods $enabled")
                    }
                    if (default.isNotEmpty() && default != "null") {
                        shell("settings put secure default_input_method $default")
                    }
                }
            }
        }
}
