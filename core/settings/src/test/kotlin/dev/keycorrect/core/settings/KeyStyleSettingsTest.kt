package dev.keycorrect.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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
class KeyStyleSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        scope = CoroutineScope(UnconfinedTestDispatcher())
        store =
            PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "t.preferences_pb") }
        repo = SettingsRepository(store)
    }

    @After fun tearDown() = scope.cancel()

    @Test
    fun defaultsKeepTodaysLook() = runTest {
        val s = repo.settings.first()
        assertThat(s.keyRadius).isEqualTo(KeyboardSettings.DEFAULT_KEY_RADIUS)
        assertThat(s.keyBorder).isFalse()
        assertThat(s.labelScale).isEqualTo(1f)
        assertThat(s.boldLabels).isFalse()
        assertThat(s.pressStyle).isEqualTo(PressStyle.Shade)
    }

    @Test
    fun roundTrips() = runTest {
        repo.update {
            it.copy(
                keyRadius = 0.5f,
                keyBorder = true,
                labelScale = 1.2f,
                boldLabels = true,
                pressStyle = PressStyle.Outline,
            )
        }
        val s = repo.settings.first()
        assertThat(s.keyRadius).isEqualTo(0.5f)
        assertThat(s.keyBorder).isTrue()
        assertThat(s.labelScale).isEqualTo(1.2f)
        assertThat(s.boldLabels).isTrue()
        assertThat(s.pressStyle).isEqualTo(PressStyle.Outline)
    }

    @Test
    fun sanitizeClampsAndFixesNaN() {
        val low = KeyboardSettings(keyRadius = -1f, labelScale = 0.1f).sanitized()
        assertThat(low.keyRadius).isEqualTo(0f)
        assertThat(low.labelScale).isEqualTo(0.8f)
        val high = KeyboardSettings(keyRadius = 9f, labelScale = 5f).sanitized()
        assertThat(high.keyRadius).isEqualTo(0.5f)
        assertThat(high.labelScale).isEqualTo(1.3f)
        val nan = KeyboardSettings(keyRadius = Float.NaN, labelScale = Float.NaN).sanitized()
        assertThat(nan.keyRadius).isEqualTo(KeyboardSettings.DEFAULT_KEY_RADIUS)
        assertThat(nan.labelScale).isEqualTo(1f)
    }

    @Test
    fun unknownPressStyleFallsBack() {
        assertThat(PressStyle.parse("Glow")).isNull()
        assertThat(PressStyle.parse("None")).isEqualTo(PressStyle.None)
    }
}
