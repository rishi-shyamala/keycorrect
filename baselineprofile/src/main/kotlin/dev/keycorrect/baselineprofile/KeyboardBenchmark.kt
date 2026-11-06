package dev.keycorrect.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Rule
import org.junit.Test

/**
 * IME cold start (host focus until the keyboard window is up) and typing frame timing, each with no
 * AOT compilation and with the baseline profile. Run on demand, see docs/PERF.md.
 */
class KeyboardBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun coldStartNone() = start(CompilationMode.None())

    @Test fun coldStartBaseline() = start(BASELINE)

    @Test fun typingNone() = typing(CompilationMode.None())

    @Test fun typingBaseline() = typing(BASELINE)

    private fun start(mode: CompilationMode) =
        rule.measureRepeated(
            packageName = TARGET,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = mode,
            startupMode = StartupMode.COLD,
            iterations = 5,
            setupBlock = {
                pressHome()
                Keyboard(TARGET).enableAndSelectIme()
            },
        ) {
            // The app process was killed in setup, so focusing the host field cold-starts the IME
            // service; openHost returns once the keyboard window is on screen. The host activity
            // is in the test process, so the measured launch is the app's Settings activity.
            startActivityAndWait()
            Keyboard(TARGET).openHost()
        }

    private fun typing(mode: CompilationMode) =
        rule.measureRepeated(
            packageName = TARGET,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = mode,
            startupMode = StartupMode.WARM,
            iterations = 5,
            setupBlock = {
                pressHome()
                Keyboard(TARGET).enableAndSelectIme()
            },
        ) {
            val kb = Keyboard(TARGET)
            val bounds = kb.openHost()
            kb.type(bounds, "the quick brown fox ")
            device.waitForIdle()
        }

    private companion object {
        const val TARGET = "dev.keycorrect"
        val BASELINE = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require)
    }
}
