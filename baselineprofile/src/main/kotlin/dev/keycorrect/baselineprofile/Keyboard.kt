package dev.keycorrect.baselineprofile

import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.FileInputStream

/** Drives the real KeyCorrect IME from a test: enable/select it, focus a field, tap keys. */
internal class Keyboard(targetPackage: String) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val imeId = "$targetPackage/.ime.KeyCorrectImeService"

    private fun shell(cmd: String): String =
        instrumentation.uiAutomation.executeShellCommand(cmd).use { fd ->
            FileInputStream(fd.fileDescriptor).bufferedReader().readText()
        }

    fun enableAndSelectIme() {
        shell("ime enable $imeId")
        shell("ime set $imeId")
    }

    /** Opens [HostActivity], focuses its field and returns the keyboard window bounds. */
    fun openHost(): Rect {
        val context = instrumentation.context
        context.startActivity(
            Intent(context, HostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        device.wait(Until.hasObject(By.desc(HostActivity.FIELD_DESCRIPTION)), 10_000)
        device.findObject(By.desc(HostActivity.FIELD_DESCRIPTION))?.click()
        return awaitBounds()
    }

    /** Bounds of the input-method window on screen. */
    fun awaitBounds(timeoutMs: Long = 10_000): Rect {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            imeBounds()?.let {
                return it
            }
            Thread.sleep(100)
        }
        error("Keyboard window did not appear; is $imeId the selected IME?")
    }

    private fun imeBounds(): Rect? =
        instrumentation.uiAutomation.windows
            .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { Rect().also(it::getBoundsInScreen) }
            ?.takeIf { it.height() > 100 }

    /** Taps a point at fractional position ([fx], [fy]) of the keyboard window. */
    fun tap(bounds: Rect, fx: Float, fy: Float) {
        device.click(
            bounds.left + (bounds.width() * fx).toInt(),
            bounds.top + (bounds.height() * fy).toInt(),
        )
    }

    /**
     * Types [text] by tapping letter keys. Approximates QWERTY geometry in the lower part of the
     * window (suggestion strip above, then the rows); precision is irrelevant, the point is to
     * exercise the touch, decode and commit paths.
     */
    fun type(bounds: Rect, text: String) {
        for (c in text) {
            if (c == ' ') {
                tap(bounds, 0.54f, 0.78f)
                continue
            }
            val r = ROWS.indexOfFirst { c in it }
            if (r < 0) continue
            val row = ROWS[r]
            val inset = (10 - row.length) / 20f
            tap(bounds, inset + (row.indexOf(c) + 0.5f) / 10f, 0.33f + 0.15f * r)
        }
    }

    fun swipe(bounds: Rect) {
        val x0 = bounds.left + (bounds.width() * 0.15f).toInt()
        val x1 = bounds.left + (bounds.width() * 0.85f).toInt()
        val y0 = bounds.top + (bounds.height() * 0.33f).toInt()
        val y1 = bounds.top + (bounds.height() * 0.63f).toInt()
        device.swipe(x0, y0, x1, y1, 30)
    }

    /** Bottom-left utility key opens the emoji panel in the Samsung-style layout. */
    fun openEmoji(bounds: Rect) {
        tap(bounds, 0.16f, 0.78f)
    }

    private companion object {
        val ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    }
}
