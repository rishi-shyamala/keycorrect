package dev.keycorrect.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ThemeSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var repo: SettingsRepository
    private val colors = List(ThemeColors.COUNT) { 0xFF102030.toInt() + it * 0x010101 }

    @Before
    fun setUp() {
        scope = CoroutineScope(UnconfinedTestDispatcher())
        store =
            PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "t.preferences_pb") }
        repo = SettingsRepository(store)
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun defaultsToWallpaperWithNoCustomColors() = runTest {
        val s = repo.settings.first()
        assertThat(s.themeSource).isEqualTo(ThemeSource.Wallpaper)
        assertThat(s.customLightColors).isEmpty()
        assertThat(s.customDarkColors).isEmpty()
    }

    @Test
    fun roundTrips() = runTest {
        repo.update {
            it.copy(
                themeSource = ThemeSource.Custom,
                customLightColors = colors,
                customDarkColors = colors.reversed(),
            )
        }
        val s = repo.settings.first()
        assertThat(s.themeSource).isEqualTo(ThemeSource.Custom)
        assertThat(s.customLightColors).isEqualTo(colors)
        assertThat(s.customDarkColors).isEqualTo(colors.reversed())
    }

    @Test
    fun storedCompactly() = runTest {
        repo.update { it.copy(customLightColors = colors) }
        val text = repo.exportValues()["custom_light_colors"] as String
        assertThat(text).hasLength(ThemeColors.COUNT * 7 - 1)
    }

    @Test
    fun exportAndApplyCarryTheTheme() = runTest {
        repo.update { it.copy(themeSource = ThemeSource.Samsung, customDarkColors = colors) }
        val exported = repo.exportValues()
        repo.update { KeyboardSettings() }
        repo.applyValues(exported, replace = true)
        val s = repo.settings.first()
        assertThat(s.themeSource).isEqualTo(ThemeSource.Samsung)
        assertThat(s.customDarkColors).isEqualTo(colors)
    }

    @Test
    fun badStoredValuesFallBack() = runTest {
        store.edit {
            it[stringPreferencesKey("theme_source")] = "Neon"
            it[stringPreferencesKey("custom_light_colors")] = "zz,1,2"
            it[stringPreferencesKey("custom_dark_colors")] = "1".repeat(500)
        }
        val s = repo.settings.first()
        assertThat(s.themeSource).isEqualTo(ThemeSource.Wallpaper)
        assertThat(s.customLightColors).isEmpty()
        assertThat(s.customDarkColors).isEmpty()
    }

    @Test
    fun sanitizingForcesOpaqueAndRightLength() {
        val s = KeyboardSettings(customLightColors = List(8) { 0x00123456 }).sanitized()
        assertThat(s.customLightColors).containsExactlyElementsIn(List(8) { 0xFF123456.toInt() })
        assertThat(KeyboardSettings(customDarkColors = listOf(1, 2, 3)).sanitized().customDarkColors)
            .isEmpty()
    }

    @Test
    fun codecRejectsMalformedText() {
        assertThat(ThemeColors.decode(null)).isEmpty()
        assertThat(ThemeColors.decode("")).isEmpty()
        assertThat(ThemeColors.decode("ffffff,ffffff")).isEmpty()
        assertThat(ThemeColors.decode(List(8) { "12345g" }.joinToString(","))).isEmpty()
        assertThat(ThemeColors.decode(ThemeColors.encode(colors))).isEqualTo(colors)
    }
}
