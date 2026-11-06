@file:Suppress("MatchingDeclarationName")

package dev.keycorrect.settings

import dev.keycorrect.core.learning.fit.FitLimits
import dev.keycorrect.core.learning.fit.FitSizes
import dev.keycorrect.core.learning.model.ProfileTouchStatistics
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.core.settings.SizeProfile
import dev.keycorrect.core.settings.SizeSettings

/**
 * A touch profile key (`Phone|Tablet|HardwareKeyboard` / `port|land` / `<layer>@<height>dp/...`,
 * see `KeyboardController.currentTouchKey`) read back into the Phase 8a size profile it belongs to.
 * [sizeProfile] is null for the hardware-keyboard profile, which has no size profile of its own.
 */
internal data class TouchKeyInfo(val sizeProfile: SizeProfile?, val recordedHeightDp: Int?)

private val HeightPattern = Regex("""@(\d+)dp""")

internal fun parseTouchKey(touchKey: String): TouchKeyInfo {
    val parts = touchKey.split('/', limit = 3)
    val tablet =
        when (parts.getOrNull(0)) {
            "Phone" -> false
            "Tablet" -> true
            else -> null
        }
    val landscape =
        when (parts.getOrNull(1)) {
            "land" -> true
            "port" -> false
            else -> null
        }
    val profile = if (tablet != null && landscape != null) SizeProfile(tablet, landscape) else null
    val height =
        parts.getOrNull(2)?.let { HeightPattern.find(it)?.groupValues?.get(1)?.toIntOrNull() }
    return TouchKeyInfo(profile, height)
}

/**
 * The touch profile to report on: among those that map to [current], the one recorded at the
 * current key height with the most words, else the one with the most words; if none maps, the one
 * with the most words overall.
 */
internal fun pickTouchProfile(
    profiles: List<ProfileTouchStatistics>,
    current: SizeProfile,
    currentHeightDp: Int,
): ProfileTouchStatistics? {
    val mapped = profiles.filter { parseTouchKey(it.touchKey).sizeProfile == current }
    val pool = mapped.ifEmpty { profiles }
    return pool.maxWithOrNull(
        compareBy<ProfileTouchStatistics>(
                { parseTouchKey(it.touchKey).recordedHeightDp == currentHeightDp },
                { it.wordCount },
            )
            .thenBy { it.touchKey }
    )
}

internal fun SizeSettings.toFitSizes() = FitSizes(keyHeightDp, keyGapDp, edgePaddingDp)

internal fun SizeSettings.withFitSizes(f: FitSizes) =
    copy(keyHeightDp = f.keyHeightDp, keyGapDp = f.keyGapDp, edgePaddingDp = f.edgePaddingDp)
        .sanitized()

internal val FitSettingLimits =
    FitLimits(
        KeyboardSettings.KEY_HEIGHT_DP_RANGE,
        KeyboardSettings.KEY_GAP_DP_RANGE,
        KeyboardSettings.EDGE_PADDING_DP_RANGE,
    )

private const val PHONE_PORTRAIT_DP = 360
private const val PHONE_LANDSCAPE_DP = 780
private const val TABLET_PORTRAIT_DP = 800
private const val TABLET_LANDSCAPE_DP = 1280

/** Typical screen width in dp of a profile this device is not on now (the keyboard fills it). */
internal fun typicalWidthDp(p: SizeProfile): Int =
    when {
        !p.tablet && !p.landscape -> PHONE_PORTRAIT_DP
        !p.tablet -> PHONE_LANDSCAPE_DP
        !p.landscape -> TABLET_PORTRAIT_DP
        else -> TABLET_LANDSCAPE_DP
    }
