package dev.keycorrect.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Reads and writes [KeyboardSettings] in a Preferences [DataStore]. */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {
    /** Current settings, always sanitized; falls back to defaults if the store is unreadable. */
    val settings: Flow<KeyboardSettings> =
        dataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .map { it.toSettings().sanitized() }
            .distinctUntilChanged()

    /** Atomically applies [transform] to the stored settings and writes the sanitized result. */
    suspend fun update(transform: (KeyboardSettings) -> KeyboardSettings) {
        dataStore.edit { prefs ->
            prefs.write(transform(prefs.toSettings().sanitized()).sanitized())
        }
    }

    private fun Preferences.toSettings(): KeyboardSettings {
        val d = KeyboardSettings()
        return KeyboardSettings(
            keyHeightDp = this[KEY_HEIGHT_DP] ?: d.keyHeightDp,
            numberRow = this[NUMBER_ROW] ?: d.numberRow,
            keyPreview = this[KEY_PREVIEW] ?: d.keyPreview,
            longPressDelayMs = this[LONG_PRESS_DELAY_MS] ?: d.longPressDelayMs,
            autoCapitalize = this[AUTO_CAPITALIZE] ?: d.autoCapitalize,
            hapticsEnabled = this[HAPTICS_ENABLED] ?: d.hapticsEnabled,
            hapticDurationMs = this[HAPTIC_DURATION_MS] ?: d.hapticDurationMs,
            hapticStrength = this[HAPTIC_STRENGTH] ?: d.hapticStrength,
            soundEnabled = this[SOUND_ENABLED] ?: d.soundEnabled,
            soundVolume = this[SOUND_VOLUME] ?: d.soundVolume,
            inlineAutofill = this[INLINE_AUTOFILL] ?: d.inlineAutofill,
        )
    }

    private fun MutablePreferences.write(s: KeyboardSettings) {
        this[KEY_HEIGHT_DP] = s.keyHeightDp
        this[NUMBER_ROW] = s.numberRow
        this[KEY_PREVIEW] = s.keyPreview
        this[LONG_PRESS_DELAY_MS] = s.longPressDelayMs
        this[AUTO_CAPITALIZE] = s.autoCapitalize
        this[HAPTICS_ENABLED] = s.hapticsEnabled
        this[HAPTIC_DURATION_MS] = s.hapticDurationMs
        this[HAPTIC_STRENGTH] = s.hapticStrength
        this[SOUND_ENABLED] = s.soundEnabled
        this[SOUND_VOLUME] = s.soundVolume
        this[INLINE_AUTOFILL] = s.inlineAutofill
    }

    private companion object {
        val KEY_HEIGHT_DP = intPreferencesKey("key_height_dp")
        val NUMBER_ROW = booleanPreferencesKey("number_row")
        val KEY_PREVIEW = booleanPreferencesKey("key_preview")
        val LONG_PRESS_DELAY_MS = intPreferencesKey("long_press_delay_ms")
        val AUTO_CAPITALIZE = booleanPreferencesKey("auto_capitalize")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val HAPTIC_DURATION_MS = intPreferencesKey("haptic_duration_ms")
        val HAPTIC_STRENGTH = intPreferencesKey("haptic_strength")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val SOUND_VOLUME = floatPreferencesKey("sound_volume")
        val INLINE_AUTOFILL = booleanPreferencesKey("inline_autofill")
    }
}
