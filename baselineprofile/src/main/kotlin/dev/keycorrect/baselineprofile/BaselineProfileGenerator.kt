package dev.keycorrect.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import org.junit.Rule
import org.junit.Test

/** Collects the startup and first-keystroke paths into the app's baseline profile. */
class BaselineProfileGenerator {
    @get:Rule(order = 0) val ime = ImeGuard()

    @get:Rule(order = 1) val rule = BaselineProfileRule()

    @Test
    fun generate() =
        rule.collect(packageName = TARGET, includeInStartupProfile = true) {
            // Settings cold start.
            pressHome()
            startActivityAndWait()

            // Keyboard: select the IME, focus the host field, type, swipe, emoji.
            val kb = Keyboard(TARGET)
            kb.enableAndSelectIme()
            val bounds = kb.openHost()
            kb.type(bounds, "the quick brown fox jumps over the lazy dog ")
            kb.swipe(bounds)
            kb.openEmoji(bounds)
            device.waitForIdle()
        }

    private companion object {
        const val TARGET = Target.PACKAGE
    }
}
