package dev.keycorrect.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File

/**
 * Factory for the keyboard settings [DataStore].
 *
 * The file lives in device-protected storage so settings are readable during direct boot, before
 * the first unlock (PLAN §15). Consequently **only non-sensitive preferences may be stored here**:
 * never personal details, learned text or keys.
 *
 * DataStore requires exactly one instance per file in a process; callers must create it once (for
 * example in `Application`) and share it.
 */
object SettingsStore {
    private const val FILE_NAME = "keyboard_settings"

    fun create(context: Context): DataStore<Preferences> {
        val storage = context.createDeviceProtectedStorageContext()
        return PreferenceDataStoreFactory.create {
            File(storage.filesDir, "datastore/$FILE_NAME.preferences_pb")
        }
    }
}
