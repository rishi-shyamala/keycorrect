package dev.keycorrect.transfer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.data.EmojiHistoryDump
import dev.keycorrect.core.data.EmojiRecentRow
import dev.keycorrect.core.data.EmojiVariantRow
import dev.keycorrect.core.data.InMemoryUserDictionaryStore
import dev.keycorrect.core.data.PersonalData
import dev.keycorrect.core.data.PersonalDataWriter
import dev.keycorrect.core.data.Shortcut
import dev.keycorrect.core.data.ShortcutMode
import dev.keycorrect.core.data.UserWord
import dev.keycorrect.core.learning.model.BlockRow
import dev.keycorrect.core.learning.model.CorrectionRow
import dev.keycorrect.core.learning.model.InMemoryLearningStore
import dev.keycorrect.core.learning.model.JournalEntry
import dev.keycorrect.core.learning.model.NgramRow
import dev.keycorrect.core.learning.model.PersistedLearning
import dev.keycorrect.core.learning.model.RankerWeightRow
import dev.keycorrect.core.learning.model.SentenceRow
import dev.keycorrect.core.learning.model.TouchProfileRow
import dev.keycorrect.core.learning.model.TouchRow
import dev.keycorrect.core.learning.model.UserWordRow
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.core.settings.SettingsRepository
import dev.keycorrect.core.transfer.DeviceProfile
import dev.keycorrect.core.transfer.ImportMode
import dev.keycorrect.core.transfer.ImportPlan
import dev.keycorrect.core.transfer.TransferBundle
import dev.keycorrect.core.transfer.TransferCodec
import dev.keycorrect.core.transfer.TransferManifest
import dev.keycorrect.ime.LearningHost
import java.io.File
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val NOW = 1_700_000_000_000L
private val PHONE = DeviceProfile("Pixel 8", 1080, 2400, 420, 411, 54, true)
private val TABLET = DeviceProfile("Tab S9", 1600, 2560, 274, 800, 60, false)

private fun manifest(device: DeviceProfile = PHONE) = TransferManifest(1, "0.5.0", NOW, device)

/** A device with something in every kind of data; [clock] is its learning active clock. */
private fun sourceState(clock: Long = 10_000, settings: Map<String, Any>? = null): DeviceState =
    DeviceState(
        settings =
            settings
                ?: mapOf(
                    "key_height_dp" to 60,
                    "bottom_key_width" to 0.8f,
                    "haptics_enabled" to false,
                    "cursor_speed" to "Fast",
                ),
        words = listOf(UserWord("Rishi", 200, null, 5, 9), UserWord("kcbd", 128, "en", 6, 7)),
        shortcuts = listOf(Shortcut("omw", "on my way", ShortcutMode.AUTO_EXPAND, 1, 2)),
        emoji =
            EmojiHistoryDump(
                listOf(EmojiRecentRow("😀", "😀", 100, 4), EmojiRecentRow("👍🏽", "👍", 90, 2)),
                listOf(EmojiVariantRow("👍", "👍🏽", 91)),
            ),
        learning =
            PersistedLearning(
                activeClockMillis = clock,
                lastWallMillis = NOW,
                rankerUpdates = 12,
                ngrams = listOf(NgramRow(2, "of the", 3.5, clock - 4_000)),
                corrections = listOf(CorrectionRow("teh", "the", "", 2.0, clock - 1_000)),
                blocks = listOf(BlockRow("badword", 3)),
                userWords = listOf(UserWordRow("kcbd", "kcbd", 1, 7, removed = false)),
                touch =
                    listOf(TouchRow("qwerty/p/en", 97, 30, 25.0, 1.0, 2.0, 3.0, 4.0, clock - 500)),
                touchProfiles = listOf(TouchProfileRow("qwerty/p/en", 40)),
                rankerWeights = listOf(RankerWeightRow(-1, 0, 0.1), RankerWeightRow(-1, 1, -0.2)),
                sentences = listOf(SentenceRow(1, "hello world", NOW - 1000)),
            ),
    )

private fun emptyState(clock: Long = 0) =
    DeviceState(
        settings = emptyMap(),
        words = emptyList(),
        shortcuts = emptyList(),
        emoji = EmojiHistoryDump(),
        learning = PersistedLearning(activeClockMillis = clock, lastWallMillis = NOW),
    )

private fun bundleOf(state: DeviceState, device: DeviceProfile = PHONE): TransferBundle =
    TransferMapper.toBundle(state, manifest(device))

private fun plan(mode: ImportMode, device: DeviceProfile = PHONE, anyway: Boolean = false) =
    ImportPlan.create(device, PHONE, mode, anyway)

/** The exported sections, ignoring the manifest and the settings the file does not carry. */
private fun sections(state: DeviceState) =
    bundleOf(state).let {
        listOf(it.dictionary, it.learning, it.touch)
    }

private fun ImportOutcome.asState(current: DeviceState) =
    DeviceState(settings, words, shortcuts, emoji, learning)

@OptIn(ExperimentalCoroutinesApi::class)
class TransferTest {
    @get:Rule val tmp = TemporaryFolder()

    // ---------------------------------------------------------------- mapping

    @Test
    fun deviceStateSurvivesTheFileAndBack() {
        val source = sourceState()
        val bytes = TransferCodec.write(bundleOf(source), "pw".toCharArray(), iterations = 310_000)
        val read = TransferCodec.read(bytes) { "pw".toCharArray() }
        // The destination has the same active clock, so nothing is rebased.
        val dest = emptyState(clock = source.learning.activeClockMillis)
        val outcome = ImportApplier.apply(dest, read, plan(ImportMode.Replace))

        assertThat(sections(outcome.asState(dest))).isEqualTo(sections(source))
        assertThat(outcome.settings).isEqualTo(source.settings)
        assertThat(outcome.replaceSettings).isTrue()
    }

    @Test
    fun incognitoIsNeverTransferred() {
        val s = sourceState(settings = mapOf("incognito" to true, "key_height_dp" to 50))
        assertThat(bundleOf(s).settings.values.keys).containsExactly("key_height_dp")
    }

    @Test
    fun invalidEntriesAreDroppedNotFatal() {
        val b = bundleOf(sourceState())
        val bad =
            b.copy(
                dictionary =
                    b.dictionary.copy(
                        words = b.dictionary.words + b.dictionary.words[0].copy(word = "two words"),
                        shortcuts =
                            b.dictionary.shortcuts + b.dictionary.shortcuts[0].copy(trigger = " "),
                    )
            )
        val o = ImportApplier.apply(emptyState(), bad, plan(ImportMode.Replace))
        assertThat(o.words.map { it.word }).containsExactly("Rishi", "kcbd")
        assertThat(o.shortcuts.map { it.trigger }).containsExactly("omw")
    }

    // ---------------------------------------------------------------- clock rebase

    @Test
    fun agesAreKeptWhenTheClockDiffers() {
        val source = sourceState(clock = 10_000) // ngram 4_000 old, correction 1_000, touch 500
        val dest = emptyState(clock = 50_000)
        val o = ImportApplier.apply(dest, bundleOf(source), plan(ImportMode.Replace))

        assertThat(o.learning.activeClockMillis).isEqualTo(50_000)
        assertThat(o.learning.ngrams.single().lastActiveMillis).isEqualTo(46_000)
        assertThat(o.learning.corrections.single().lastActiveMillis).isEqualTo(49_000)
        assertThat(o.learning.touch.single().lastActiveMillis).isEqualTo(49_500)
        // Wall-clock times are not rebased.
        assertThat(o.learning.sentences.single().timeMillis).isEqualTo(NOW - 1000)
        assertThat(o.learning.blocks.single().createdAtMillis).isEqualTo(3)
    }

    @Test
    fun mergeRebasesBeforeAddingCounts() {
        val dest = sourceState(clock = 90_000)
        val o =
            ImportApplier.apply(dest, bundleOf(sourceState(clock = 10_000)), plan(ImportMode.Merge))
        val g = o.learning.ngrams.single()
        assertThat(g.weight).isEqualTo(7.0)
        // The local entry was last used at 86_000, the imported one 4_000 before 90_000: the same.
        assertThat(g.lastActiveMillis).isEqualTo(86_000)
    }

    // ---------------------------------------------------------------- merge vs replace

    private fun local(): DeviceState =
        sourceState(
                clock = 10_000,
                settings = mapOf("key_height_dp" to 48, "sound_enabled" to true),
            )
            .let {
                DeviceState(
                    it.settings,
                    listOf(UserWord("local", 100, null, 1, 1), UserWord("Rishi", 100, null, 2, 3)),
                    listOf(Shortcut("brb", "be right back", ShortcutMode.SUGGEST, 1, 1)),
                    EmojiHistoryDump(listOf(EmojiRecentRow("🎉", "🎉", 200, 1))),
                    it.learning.copy(ngrams = listOf(NgramRow(2, "of the", 1.0, 9_000))),
                )
            }

    @Test
    fun mergeAddsAndKeepsThisDevicesData() {
        val o = ImportApplier.apply(local(), bundleOf(sourceState()), plan(ImportMode.Merge))

        assertThat(o.words.map { it.word }).containsExactly("Rishi", "kcbd", "local")
        assertThat(o.words.single { it.word == "Rishi" }.frequency)
            .isEqualTo(255) // 100 + 200, capped
        assertThat(o.shortcuts.map { it.trigger }).containsExactly("brb", "omw")
        assertThat(o.emoji.recents.map { it.emoji }).containsExactly("🎉", "😀", "👍🏽")
        assertThat(o.learning.ngrams.single().weight).isEqualTo(4.5)
        assertThat(o.replaceSettings).isFalse()
    }

    @Test
    fun replaceTakesTheFilesDataOnly() {
        val o = ImportApplier.apply(local(), bundleOf(sourceState()), plan(ImportMode.Replace))

        assertThat(o.words.map { it.word }).containsExactly("Rishi", "kcbd")
        assertThat(o.shortcuts.map { it.trigger }).containsExactly("omw")
        assertThat(o.emoji.recents.map { it.emoji }).containsExactly("😀", "👍🏽")
        assertThat(o.learning.ngrams.single().weight).isEqualTo(3.5)
        assertThat(o.replaceSettings).isTrue()
    }

    // ---------------------------------------------------------------- different screen

    @Test
    fun aDifferentScreenSkipsTouchAndScreenTiedSettings() {
        val file = bundleOf(sourceState(), TABLET)
        val dest = local()
        val o = ImportApplier.apply(dest, file, plan(ImportMode.Replace, TABLET))

        assertThat(o.learning.touch).isEqualTo(dest.learning.touch)
        assertThat(o.learning.touchProfiles).isEqualTo(dest.learning.touchProfiles)
        assertThat(o.settings.keys).containsNoneIn(setOf("key_height_dp", "bottom_key_width"))
        assertThat(o.settings).containsKey("haptics_enabled")
        assertThat(o.keepLocalSettings).containsAtLeast("key_height_dp", "key_gap_dp")
        assertThat(o.counts.touchKeys).isEqualTo(0)
        // The rest still came across.
        assertThat(o.words.map { it.word }).containsExactly("Rishi", "kcbd")
    }

    @Test
    fun importAnywayBringsTouchAndSizesIn() {
        val file = bundleOf(sourceState(), TABLET)
        val o = ImportApplier.apply(local(), file, plan(ImportMode.Replace, TABLET, anyway = true))

        assertThat(o.learning.touch.single().char).isEqualTo(97)
        assertThat(o.settings["key_height_dp"]).isEqualTo(60)
        assertThat(o.keepLocalSettings).isEmpty()
    }

    @Test
    fun aSimilarScreenIsTheSameScreen() {
        val nearly = PHONE.copy(screenWidthPx = 1090, densityDpi = 425)
        assertThat(plan(ImportMode.Merge, nearly).screenDiffers).isFalse()
    }

    // ---------------------------------------------------------------- the service

    private class FakeStores(val learningStore: InMemoryLearningStore) :
        TransferStores, PersonalDataWriter {
        var words = emptyList<UserWord>()
        var shortcuts = emptyList<Shortcut>()
        var emoji = EmojiHistoryDump()
        var failOnReplace = false
        var reloads = 0

        override suspend fun words() = words

        override suspend fun shortcuts() = shortcuts

        override suspend fun emoji() = emoji

        override suspend fun reload() {
            reloads++
        }

        override suspend fun replaceAll(data: PersonalData) {
            if (failOnReplace) throw IOException("disk full")
            words = data.words
            shortcuts = data.shortcuts
            emoji = data.emoji
            learningStore.replaceAll(data.learning, data.journal)
        }
    }

    private class Device(
        val scope: TestScope,
        val repo: SettingsRepository,
        private val initial: PersistedLearning,
        val profile: DeviceProfile = PHONE,
    ) {
        val learningStore = InMemoryLearningStore()
        val stores = FakeStores(learningStore)
        val host =
            LearningHost(
                openStore = { learningStore },
                userDictionary = InMemoryUserDictionaryStore(),
                scope = scope.backgroundScope,
                wallClock = { NOW + scope.testScheduler.currentTime },
            )
        val service = TransferService(repo, stores, host, stores, { profile }, "0.5.0", { NOW })

        suspend fun start() {
            if (initial != PersistedLearning()) learningStore.save(initial)
            host.start(MutableStateFlow(KeyboardSettings()))
            scope.testScheduler.advanceUntilIdle()
            host.awaitIdle()
        }
    }

    private fun TestScope.device(
        initial: PersistedLearning = PersistedLearning(),
        profile: DeviceProfile = PHONE,
        name: String = "s",
    ): Device {
        val store: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = backgroundScope) {
                File(tmp.root, "$name.preferences_pb")
            }
        return Device(this, SettingsRepository(store), initial, profile)
    }

    @Test
    fun exportOnOneDeviceImportOnAnother() = runTest {
        val a = device(sourceState().learning, name = "a")
        a.stores.words = sourceState().words
        a.stores.shortcuts = sourceState().shortcuts
        a.stores.emoji = sourceState().emoji
        a.repo.update { it.copy(keyHeightDp = 60, hapticsEnabled = false) }
        a.start()
        val bytes = a.service.encode(a.service.buildBundle(), null)

        val b = device(name = "b")
        b.start()
        val bundle = TransferCodec.read(bytes)
        val result = b.service.import(bundle, b.service.plan(bundle, ImportMode.Replace, false))

        assertThat(result.skippedTouch).isFalse()
        assertThat(b.stores.words.map { it.word }).containsExactly("Rishi", "kcbd")
        assertThat(b.stores.shortcuts.map { it.trigger }).containsExactly("omw")
        assertThat(b.stores.emoji.recents).hasSize(2)
        assertThat(b.stores.reloads).isEqualTo(1)
        val settings = b.repo.exportValues()
        assertThat(settings["key_height_dp"]).isEqualTo(60)
        assertThat(settings["haptics_enabled"]).isEqualTo(false)
        // The live model and the store agree, and the model has the imported learning.
        val live = b.host.exportState()
        assertThat(live.ngrams.single().weight).isEqualTo(3.5)
        assertThat(live.userWords.map { it.word }).containsExactly("kcbd")
        assertThat(live.blocks.map { it.word }).containsExactly("badword")
        assertThat(b.learningStore.state).isEqualTo(live)
    }

    @Test
    fun aFailedImportChangesNothing() = runTest {
        val d = device(local().learning, name = "f")
        d.stores.words = local().words
        d.stores.shortcuts = local().shortcuts
        d.stores.emoji = local().emoji
        d.repo.update { it.copy(keyHeightDp = 48, soundEnabled = true) }
        d.start()
        val settingsBefore = d.repo.exportValues()
        val modelBefore = d.host.exportState()
        val storedBefore = d.learningStore.state
        d.stores.failOnReplace = true

        val bundle = bundleOf(sourceState())
        val failure = runCatching {
            d.service.import(bundle, d.service.plan(bundle, ImportMode.Replace, false))
        }
            .exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(d.host.exportState()).isEqualTo(modelBefore)
        assertThat(d.learningStore.state).isEqualTo(storedBefore)
        assertThat(d.repo.exportValues()).isEqualTo(settingsBefore)
        assertThat(d.stores.words).isEqualTo(local().words)
        assertThat(d.stores.reloads).isEqualTo(0)
    }

    @Test
    fun theServiceSkipsScreenTiedDataOnADifferentScreen() = runTest {
        val d = device(local().learning, name = "t")
        d.repo.update { it.copy(keyHeightDp = 48) }
        d.start()
        val bundle = bundleOf(sourceState(), TABLET)
        val result = d.service.import(bundle, d.service.plan(bundle, ImportMode.Merge, false))

        assertThat(result.skippedTouch).isTrue()
        assertThat(d.repo.exportValues()["key_height_dp"]).isEqualTo(48)
        assertThat(d.repo.exportValues()["haptics_enabled"]).isEqualTo(false)
        assertThat(d.host.exportState().touch).isEqualTo(local().learning.touch)
    }

    @Test
    fun aPlainFileIsNotEncryptedAndAPassphraseOneIs() = runTest {
        val d = device(name = "p")
        d.start()
        val bundle = d.service.buildBundle()
        assertThat(TransferCodec.isEncrypted(d.service.encode(bundle, null))).isFalse()
        assertThat(TransferCodec.isEncrypted(d.service.encode(bundle, "x".toCharArray()))).isTrue()
    }

    @Suppress("unused") private fun journal(): List<JournalEntry> = emptyList()
}
