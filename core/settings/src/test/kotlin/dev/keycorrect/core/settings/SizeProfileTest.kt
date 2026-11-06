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

private val PHONE_PORTRAIT = SizeProfile(tablet = false, landscape = false)
private val PHONE_LANDSCAPE = SizeProfile(tablet = false, landscape = true)
private val TABLET_LANDSCAPE = SizeProfile(tablet = true, landscape = true)

@OptIn(ExperimentalCoroutinesApi::class)
class SizeProfileTest {
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
    fun detectionUsesSmallestWidthAndOrientation() {
        assertThat(SizeProfile.of(411, landscape = false)).isEqualTo(PHONE_PORTRAIT)
        assertThat(SizeProfile.of(411, landscape = true)).isEqualTo(PHONE_LANDSCAPE)
        assertThat(SizeProfile.of(599, landscape = false).tablet).isFalse()
        assertThat(SizeProfile.of(600, landscape = false).tablet).isTrue()
        assertThat(SizeProfile.of(800, landscape = true)).isEqualTo(TABLET_LANDSCAPE)
        assertThat(SizeProfile.ALL.map { it.id }.toSet()).hasSize(4)
        assertThat(SizeProfile.fromId("tablet_landscape")).isEqualTo(TABLET_LANDSCAPE)
        assertThat(SizeProfile.fromId("watch")).isNull()
    }

    @Test
    fun aProfileWithNothingStoredUsesTheGlobalValues() = runTest {
        repo.update { it.copy(keyHeightDp = 66, numberRow = false) }
        val s = repo.settings.first()
        for (p in SizeProfile.ALL) {
            assertThat(s.sizeFor(p).keyHeightDp).isEqualTo(66)
            assertThat(s.sizeFor(p).numberRow).isFalse()
        }
        assertThat(s.sizes).isEmpty()
    }

    @Test
    fun aProfileKeepsItsOwnValuesAndTheOthersFallBack() = runTest {
        repo.update {
            it.copy(keyHeightDp = 60).updateSizes(PHONE_LANDSCAPE) { s ->
                s.copy(keyHeightDp = 45, numberRow = false)
            }
        }
        val s = repo.settings.first()
        assertThat(s.sizeFor(PHONE_LANDSCAPE).keyHeightDp).isEqualTo(45)
        assertThat(s.sizeFor(PHONE_LANDSCAPE).numberRow).isFalse()
        assertThat(s.sizeFor(PHONE_PORTRAIT).keyHeightDp).isEqualTo(60)
        assertThat(s.sizeFor(PHONE_PORTRAIT).numberRow).isTrue()
        assertThat(s.sizeFor(TABLET_LANDSCAPE).keyHeightDp).isEqualTo(60)
        assertThat(s.keyHeightDp).isEqualTo(60)
    }

    @Test
    fun aPartlyStoredProfileTakesTheRestFromTheGlobalValues() = runTest {
        repo.applyValues(mapOf("key_height_dp" to 62, "key_gap_dp@tablet_portrait" to 2))
        val tablet = repo.settings.first().sizeFor(SizeProfile(tablet = true, landscape = false))
        assertThat(tablet.keyGapDp).isEqualTo(2)
        assertThat(tablet.keyHeightDp).isEqualTo(62)
    }

    @Test
    fun everyProfileIsClamped() = runTest {
        repo.update {
            it.withSizes(PHONE_LANDSCAPE, SizeSettings(1000, Float.NaN, 99f, -5, 100, true))
        }
        val s = repo.settings.first().sizeFor(PHONE_LANDSCAPE)
        assertThat(s.keyHeightDp).isEqualTo(KeyboardSettings.KEY_HEIGHT_DP_RANGE.last)
        assertThat(s.bottomKeyWidth).isEqualTo(KeyboardSettings.DEFAULT_BOTTOM_KEY_WIDTH)
        assertThat(s.modeEnterKeyWidth)
            .isEqualTo(KeyboardSettings.MODE_ENTER_KEY_WIDTH_RANGE.endInclusive)
        assertThat(s.edgePaddingDp).isEqualTo(KeyboardSettings.EDGE_PADDING_DP_RANGE.first)
        assertThat(s.keyGapDp).isEqualTo(KeyboardSettings.KEY_GAP_DP_RANGE.last)
    }

    @Test
    fun storedOutOfRangeValuesAreClampedOnApply() = runTest {
        repo.applyValues(
            mapOf("key_height_dp@tablet_landscape" to 9999, "number_row@tablet_landscape" to "yes")
        )
        val s = repo.settings.first().sizeFor(TABLET_LANDSCAPE)
        assertThat(s.keyHeightDp).isEqualTo(KeyboardSettings.KEY_HEIGHT_DP_RANGE.last)
        assertThat(s.numberRow).isTrue() // wrong type ignored
    }

    @Test
    fun profilesRoundTripThroughExportAndApply() = runTest {
        repo.update {
            it.withSizes(PHONE_LANDSCAPE, SizeSettings(44, 0.5f, 1.0f, 1, 2, false))
                .withSizes(TABLET_LANDSCAPE, SizeSettings(70, 1.0f, 1.2f, 3, 4, true))
        }
        val exported = repo.exportValues()
        assertThat(exported["key_height_dp@phone_landscape"]).isEqualTo(44)
        assertThat(exported["number_row@phone_landscape"]).isEqualTo(false)
        assertThat(exported).doesNotContainKey("key_height_dp@phone_portrait")
        val other =
            SettingsRepository(
                PreferenceDataStoreFactory.create(scope = scope) {
                    File(tmp.root, "o.preferences_pb")
                }
            )
        other.applyValues(exported, replace = true)
        assertThat(other.settings.first()).isEqualTo(repo.settings.first())
        assertThat(other.exportValues()).isEqualTo(exported)
    }

    @Test
    fun keepLocalKeepsThisDevicesProfileValues() = runTest {
        repo.update { it.updateSizes(PHONE_LANDSCAPE) { s -> s.copy(keyHeightDp = 44) } }
        repo.applyValues(
            mapOf("key_height_dp@phone_landscape" to 70, "key_height_dp@tablet_landscape" to 72),
            replace = true,
            keepLocal = setOf("key_height_dp@phone_landscape", "key_height_dp@tablet_landscape"),
        )
        val s = repo.settings.first()
        assertThat(s.sizeFor(PHONE_LANDSCAPE).keyHeightDp).isEqualTo(44)
        assertThat(s.sizes.keys).containsExactly(PHONE_LANDSCAPE)
    }

    @Test
    fun forProfilePutsTheProfilesSizesInTheGlobalFields() {
        val s =
            KeyboardSettings(keyHeightDp = 60)
                .withSizes(PHONE_LANDSCAPE, SizeSettings(44, 0.5f, 1.0f, 1, 2, false))
        assertThat(s.forProfile(PHONE_LANDSCAPE).keyHeightDp).isEqualTo(44)
        assertThat(s.forProfile(PHONE_LANDSCAPE).numberRow).isFalse()
        assertThat(s.forProfile(PHONE_PORTRAIT).keyHeightDp).isEqualTo(60)
    }
}
