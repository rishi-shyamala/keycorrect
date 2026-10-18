package dev.keycorrect.core.transfer

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What to do with data already on the device (decision T-3). */
enum class ImportMode {
    /** Add counts, union words and shortcuts, keep this device's data. */
    Merge,

    /** Wipe this device's imported kinds of data first, then take the file's. */
    Replace,
}

/** The kinds of data in a backup. */
enum class Section {
    Settings,
    Dictionary,
    Learning,
    Touch,
}

/** Compares two screens (decision T-4). */
object ScreenComparison {
    /** Relative difference allowed in each measure before the screens count as different. */
    const val DEFAULT_TOLERANCE = 0.03

    /**
     * True when the screens differ. Width and height are compared orientation-independently (short
     * side with short side), so a phone read in landscape matches itself in portrait.
     */
    fun differs(
        a: DeviceProfile,
        b: DeviceProfile,
        tolerance: Double = DEFAULT_TOLERANCE,
    ): Boolean {
        val aShort = min(a.screenWidthPx, a.screenHeightPx)
        val aLong = max(a.screenWidthPx, a.screenHeightPx)
        val bShort = min(b.screenWidthPx, b.screenHeightPx)
        val bLong = max(b.screenWidthPx, b.screenHeightPx)
        return !close(aShort, bShort, tolerance) ||
            !close(aLong, bLong, tolerance) ||
            !close(a.densityDpi, b.densityDpi, tolerance) ||
            !close(a.smallestWidthDp, b.smallestWidthDp, tolerance)
    }

    private fun close(x: Int, y: Int, tolerance: Double): Boolean =
        abs(x - y) <= tolerance * max(abs(x), abs(y))
}

/**
 * What an import will do, decided from the file's source device and this one. Pure: the app shows
 * [screenDiffers] as a warning, lets the user tick [importTouchAnyway], applies the plan and
 * reports the result.
 */
data class ImportPlan(
    val mode: ImportMode,
    val screenDiffers: Boolean,
    /** The user's "import anyway" choice; ignored when the screens match. */
    val importTouchAnyway: Boolean = false,
) {
    /** Touch data and screen-tied sizes are brought in (they match, or the user insisted). */
    val includesScreenTied: Boolean
        get() = !screenDiffers || importTouchAnyway

    /** The sections the import applies, in apply order. */
    val sections: List<Section>
        get() = buildList {
            add(Section.Settings)
            add(Section.Dictionary)
            add(Section.Learning)
            if (includesScreenTied) add(Section.Touch)
        }

    /** The sections whose local data is erased before applying (Replace only). */
    val sectionsToWipe: List<Section>
        get() = if (mode == ImportMode.Replace) sections else emptyList()

    /** The sections of the file that are left out and why: always the touch section here. */
    val skipped: List<Section>
        get() = Section.entries - sections.toSet()

    /**
     * The settings to write: all of [section] when [includesScreenTied], otherwise without the
     * screen-tied keys, which keep this device's values (also under Replace).
     */
    fun settingsToApply(section: SettingsSection): Map<String, SettingValue> =
        if (includesScreenTied) section.values
        else section.values.filterKeys { it !in SettingsSection.SCREEN_TIED_KEYS }

    /** The setting keys left out of the import. */
    fun skippedSettingKeys(section: SettingsSection): Set<String> =
        section.values.keys - settingsToApply(section).keys

    companion object {
        fun create(
            source: DeviceProfile,
            current: DeviceProfile,
            mode: ImportMode,
            importTouchAnyway: Boolean = false,
            tolerance: Double = ScreenComparison.DEFAULT_TOLERANCE,
        ): ImportPlan =
            ImportPlan(
                mode,
                ScreenComparison.differs(source, current, tolerance),
                importTouchAnyway,
            )

        fun create(
            manifest: TransferManifest,
            current: DeviceProfile,
            mode: ImportMode,
            importTouchAnyway: Boolean = false,
        ): ImportPlan = create(manifest.device, current, mode, importTouchAnyway)
    }
}

/**
 * A short, stable id of one backup (its creation time, source device and app version), so the app
 * can tell that a file was already imported. Not secret and not reversible; it identifies no
 * content.
 */
fun TransferManifest.backupId(): String {
    val text =
        listOf(
                createdAtMillis,
                device.model,
                device.screenWidthPx,
                device.screenHeightPx,
                device.densityDpi,
                appVersion,
            )
            .joinToString("|")
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
    return digest.take(BACKUP_ID_BYTES).joinToString("") {
        "%02x".format(java.util.Locale.ROOT, it)
    }
}

private const val BACKUP_ID_BYTES = 12
