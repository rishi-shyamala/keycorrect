package dev.keycorrect.ime

import android.content.Context
import android.view.WindowManager
import dev.keycorrect.core.settings.SizeProfile

/**
 * The size profile of the display, shared by the keyboard, the settings screen and the transfer
 * manifest. It uses the display-level bounds, not a window's configuration, so split screen and
 * freeform windows do not change the profile.
 */
object DisplaySizeProfile {
    fun of(widthPx: Int, heightPx: Int, densityDpi: Int): SizeProfile {
        val density = densityDpi.coerceAtLeast(1) / 160f
        val smallestWidthDp = (minOf(widthPx, heightPx) / density).toInt()
        return SizeProfile.of(smallestWidthDp, landscape = widthPx > heightPx)
    }

    fun of(context: Context): SizeProfile {
        val app = context.applicationContext
        val bounds = app.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return of(bounds.width(), bounds.height(), app.resources.displayMetrics.densityDpi)
    }
}
