package dev.keycorrect.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
class SettingsRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        scope = CoroutineScope(UnconfinedTestDispatcher())
        store =
            PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "s.preferences_pb") }
        repo = SettingsRepository(store)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun emptyStoreYieldsDefaults() = runTest {
        assertThat(repo.settings.first()).isEqualTo(KeyboardSettings())
    }

    @Test
    fun defaultsMatchSpec() {
        val d = KeyboardSettings()
        assertThat(d.keyHeightDp).isEqualTo(54)
        assertThat(d.bottomKeyWidth).isEqualTo(0.75f)
        assertThat(d.edgePaddingDp).isEqualTo(4)
        assertThat(d.keyGapDp).isEqualTo(6)
        assertThat(d.numberRow).isTrue()
        assertThat(d.symbolHints).isTrue()
        assertThat(d.modeEnterKeyWidth).isEqualTo(1.15f)
        assertThat(d.keyPreview).isTrue()
        assertThat(d.longPressDelayMs).isEqualTo(300)
        assertThat(d.autoCapitalize).isTrue()
        assertThat(d.hapticsEnabled).isTrue()
        assertThat(d.hapticDurationMs).isEqualTo(0)
        assertThat(d.hapticStrength).isEqualTo(0)
        assertThat(d.autocorrectPulseMs).isEqualTo(20)
        assertThat(d.unknownWordPulseMs).isEqualTo(20)
        assertThat(d.soundEnabled).isFalse()
        assertThat(d.soundVolume).isEqualTo(0.5f)
        assertThat(d.inlineAutofill).isTrue()
        assertThat(d.suggestionsEnabled).isTrue()
        assertThat(d.autocorrectEnabled).isTrue()
        assertThat(d.nextWordPredictions).isTrue()
        assertThat(d.phraseCompletion).isTrue()
        assertThat(d.autoSpaceAfterPick).isTrue()
        assertThat(d.spacebarCursor).isTrue()
        assertThat(d.cursorSpeed).isEqualTo(CursorSpeed.Normal)
        assertThat(d.backspaceSwipe).isTrue()
    }

    @Test
    fun everyFieldRoundTrips() = runTest {
        val custom =
            KeyboardSettings(
                keyHeightDp = 72,
                bottomKeyWidth = 0.8f,
                edgePaddingDp = 1,
                keyGapDp = 0,
                modeEnterKeyWidth = 0.9f,
                numberRow = false,
                symbolHints = false,
                keyPreview = false,
                longPressDelayMs = 450,
                autoCapitalize = false,
                hapticsEnabled = false,
                hapticDurationMs = 25,
                hapticStrength = 128,
                autocorrectPulseMs = 35,
                unknownWordPulseMs = 9,
                soundEnabled = true,
                soundVolume = 0.8f,
                inlineAutofill = false,
                suggestionsEnabled = false,
                autocorrectEnabled = false,
                nextWordPredictions = false,
                phraseCompletion = false,
                autoSpaceAfterPick = false,
                spacebarCursor = false,
                cursorSpeed = CursorSpeed.Fast,
                backspaceSwipe = false,
            )
        repo.update { custom }
        assertThat(repo.settings.first()).isEqualTo(custom)
    }

    @Test
    fun inlineAutofillIsPersistedUnderItsKey() = runTest {
        repo.update { it.copy(inlineAutofill = false) }
        assertThat(repo.settings.first().inlineAutofill).isFalse()
        assertThat(store.data.first()[booleanPreferencesKey("inline_autofill")]).isFalse()
        repo.update { it.copy(inlineAutofill = true) }
        assertThat(repo.settings.first().inlineAutofill).isTrue()
    }

    @Test
    fun cursorSpeedIsPersistedByNameAndUnknownNamesFallBack() = runTest {
        repo.update { it.copy(cursorSpeed = CursorSpeed.Slow) }
        assertThat(store.data.first()[stringPreferencesKey("cursor_speed")]).isEqualTo("Slow")
        store.edit { it[stringPreferencesKey("cursor_speed")] = "Warp" }
        assertThat(repo.settings.first().cursorSpeed).isEqualTo(CursorSpeed.Normal)
    }

    @Test
    fun voiceSettingsDefaultOnAndRoundTrip() = runTest {
        val d = repo.settings.first()
        assertThat(d.voiceEnabled).isTrue()
        assertThat(d.voiceEngine).isEqualTo(VoiceEngineChoice.BuiltIn)
        assertThat(d.voiceAutoPunctuation).isTrue()
        repo.update {
            it.copy(
                voiceEnabled = false,
                voiceEngine = VoiceEngineChoice.PhoneRecognizer,
                voiceAutoPunctuation = false,
            )
        }
        val s = repo.settings.first()
        assertThat(s.voiceEnabled).isFalse()
        assertThat(s.voiceEngine).isEqualTo(VoiceEngineChoice.PhoneRecognizer)
        assertThat(s.voiceAutoPunctuation).isFalse()
        assertThat(store.data.first()[stringPreferencesKey("voice_engine")])
            .isEqualTo("PhoneRecognizer")
        store.edit { it[stringPreferencesKey("voice_engine")] = "Cloud" }
        assertThat(repo.settings.first().voiceEngine).isEqualTo(VoiceEngineChoice.BuiltIn)
    }

    @Test
    fun oldVoiceEngineNamesMigrate() = runTest {
        store.edit { it[stringPreferencesKey("voice_engine")] = "Automatic" }
        assertThat(repo.settings.first().voiceEngine).isEqualTo(VoiceEngineChoice.BuiltIn)
        store.edit { it[stringPreferencesKey("voice_engine")] = "System" }
        assertThat(repo.settings.first().voiceEngine).isEqualTo(VoiceEngineChoice.PhoneRecognizer)
    }

    @Test
    fun layoutSizesClampOnWriteAndRead() = runTest {
        repo.update { it.copy(bottomKeyWidth = 9f, edgePaddingDp = 40, keyGapDp = -3) }
        var s = repo.settings.first()
        assertThat(s.bottomKeyWidth).isEqualTo(1.5f)
        assertThat(s.edgePaddingDp).isEqualTo(4)
        assertThat(s.keyGapDp).isEqualTo(0)
        store.edit {
            it[floatPreferencesKey("bottom_key_width")] = 0.01f
            it[intPreferencesKey("edge_padding_dp")] = -1
            it[intPreferencesKey("key_gap_dp")] = 99
        }
        s = repo.settings.first()
        assertThat(s.bottomKeyWidth).isEqualTo(0.3f)
        assertThat(s.edgePaddingDp).isEqualTo(0)
        assertThat(s.keyGapDp).isEqualTo(6)
        assertThat(KeyboardSettings(bottomKeyWidth = Float.NaN).sanitized().bottomKeyWidth)
            .isEqualTo(0.75f)
    }

    @Test
    fun modeEnterKeyWidthClampsOnWriteAndRead() = runTest {
        repo.update { it.copy(modeEnterKeyWidth = 9f) }
        assertThat(repo.settings.first().modeEnterKeyWidth).isEqualTo(1.5f)
        store.edit { it[floatPreferencesKey("mode_enter_key_width")] = 0.1f }
        assertThat(repo.settings.first().modeEnterKeyWidth).isEqualTo(0.75f)
        assertThat(KeyboardSettings(modeEnterKeyWidth = Float.NaN).sanitized().modeEnterKeyWidth)
            .isEqualTo(1.15f)
    }

    @Test
    fun eachFieldCanBeChangedIndependently() = runTest {
        repo.update { it.copy(numberRow = false) }
        repo.update { it.copy(keyHeightDp = 60) }
        assertThat(repo.settings.first())
            .isEqualTo(KeyboardSettings(numberRow = false, keyHeightDp = 60))
    }

    @Test
    fun updateClampsOnWrite() = runTest {
        repo.update {
            it.copy(
                keyHeightDp = 500,
                longPressDelayMs = 1,
                hapticDurationMs = 9999,
                hapticStrength = -5,
                soundVolume = 3f,
            )
        }
        val s = repo.settings.first()
        assertThat(s.keyHeightDp).isEqualTo(80)
        assertThat(s.longPressDelayMs).isEqualTo(150)
        assertThat(s.hapticDurationMs).isEqualTo(100)
        assertThat(s.hapticStrength).isEqualTo(0)
        assertThat(s.soundVolume).isEqualTo(1f)
        // The persisted values themselves are clamped, not just the read.
        val raw = store.data.first()
        assertThat(raw[intPreferencesKey("key_height_dp")]).isEqualTo(80)
        assertThat(raw[floatPreferencesKey("sound_volume")]).isEqualTo(1f)
    }

    @Test
    fun hapticPulseLengthsAreClampedToFiveToSixtyOnWriteAndRead() = runTest {
        repo.update { it.copy(autocorrectPulseMs = 1, unknownWordPulseMs = 500) }
        val s = repo.settings.first()
        assertThat(s.autocorrectPulseMs).isEqualTo(5)
        assertThat(s.unknownWordPulseMs).isEqualTo(60)
        assertThat(store.data.first()[intPreferencesKey("autocorrect_pulse_ms")]).isEqualTo(5)
        store.edit { it[intPreferencesKey("unknown_word_pulse_ms")] = -3 }
        assertThat(repo.settings.first().unknownWordPulseMs).isEqualTo(5)
    }

    @Test
    fun readClampsCorruptStoredValues() = runTest {
        store.edit {
            it[intPreferencesKey("key_height_dp")] = 5
            it[intPreferencesKey("long_press_delay_ms")] = 99999
            it[intPreferencesKey("haptic_duration_ms")] = -1
            it[intPreferencesKey("haptic_strength")] = 1000
            it[floatPreferencesKey("sound_volume")] = -0.5f
        }
        val s = repo.settings.first()
        assertThat(s.keyHeightDp).isEqualTo(40)
        assertThat(s.longPressDelayMs).isEqualTo(800)
        assertThat(s.hapticDurationMs).isEqualTo(0)
        assertThat(s.hapticStrength).isEqualTo(255)
        assertThat(s.soundVolume).isEqualTo(0f)
    }

    @Test
    fun sanitizedReplacesNanVolume() {
        assertThat(KeyboardSettings(soundVolume = Float.NaN).sanitized().soundVolume)
            .isEqualTo(0.5f)
    }

    @Test
    fun sanitizedLeavesValidValuesAlone() {
        val s = KeyboardSettings(keyHeightDp = 40, longPressDelayMs = 800, hapticStrength = 255)
        assertThat(s.sanitized()).isEqualTo(s)
    }

    @Test
    fun concurrentUpdatesAreAtomic() = runTest {
        val n = 50
        (1..n)
            .map {
                async(UnconfinedTestDispatcher(testScheduler)) {
                    repo.update {
                        it.copy(hapticDurationMs = (it.hapticDurationMs + 1).coerceAtMost(100))
                    }
                }
            }
            .awaitAll()
        assertThat(repo.settings.first().hapticDurationMs).isEqualTo(n)
    }

    @Test
    fun updateTransformSeesStoredValue() = runTest {
        repo.update { it.copy(keyHeightDp = 60) }
        var seen = -1
        repo.update {
            seen = it.keyHeightDp
            it
        }
        assertThat(seen).isEqualTo(60)
    }

    @Test
    fun exportedValuesApplyToAnotherDeviceUnchanged() = runTest {
        repo.update {
            it.copy(keyHeightDp = 63, hapticsEnabled = false, cursorSpeed = CursorSpeed.Fast)
        }
        val exported = repo.exportValues()
        assertThat(exported["key_height_dp"]).isEqualTo(63)
        assertThat(exported["haptics_enabled"]).isEqualTo(false)
        assertThat(exported["cursor_speed"]).isEqualTo("Fast")

        val other =
            SettingsRepository(
                PreferenceDataStoreFactory.create(scope = scope) {
                    File(tmp.root, "o.preferences_pb")
                }
            )
        other.applyValues(exported, replace = true)
        assertThat(other.exportValues()).isEqualTo(exported)
        assertThat(other.settings.first()).isEqualTo(repo.settings.first())
    }

    @Test
    fun applyIgnoresUnknownKeysAndWrongTypesAndSanitizes() = runTest {
        repo.applyValues(
            mapOf(
                "some_future_setting" to true,
                "key_height_dp" to "tall",
                "haptics_enabled" to false,
                "edge_padding_dp" to 100_000,
                "cursor_speed" to "Warp",
            )
        )
        val s = repo.settings.first()
        assertThat(s.keyHeightDp).isEqualTo(KeyboardSettings().keyHeightDp)
        assertThat(s.hapticsEnabled).isFalse()
        assertThat(s.edgePaddingDp).isAtMost(100)
        assertThat(s.cursorSpeed).isEqualTo(KeyboardSettings().cursorSpeed)
        assertThat(repo.exportValues()).doesNotContainKey("some_future_setting")
    }

    @Test
    fun replaceResetsTheRestAndKeepLocalKeepsThisDevicesValue() = runTest {
        repo.update { it.copy(keyHeightDp = 70, soundEnabled = !KeyboardSettings().soundEnabled) }
        repo.applyValues(
            mapOf("key_height_dp" to 50, "haptics_enabled" to false),
            replace = true,
            keepLocal = setOf("key_height_dp"),
        )
        val s = repo.settings.first()
        assertThat(s.keyHeightDp).isEqualTo(70)
        assertThat(s.hapticsEnabled).isFalse()
        assertThat(s.soundEnabled).isEqualTo(KeyboardSettings().soundEnabled)
    }

    @Test
    fun mergeKeepsSettingsNotInTheFile() = runTest {
        repo.update { it.copy(keyHeightDp = 70) }
        repo.applyValues(mapOf("haptics_enabled" to false))
        assertThat(repo.settings.first().keyHeightDp).isEqualTo(70)
    }
}
