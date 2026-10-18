package dev.keycorrect.transfer

import dev.keycorrect.core.data.EmojiHistoryDump
import dev.keycorrect.core.data.PersonalData
import dev.keycorrect.core.data.PersonalDataWriter
import dev.keycorrect.core.data.Shortcut
import dev.keycorrect.core.data.UserWord
import dev.keycorrect.core.learning.model.PersistedLearning
import dev.keycorrect.core.settings.SettingsRepository
import dev.keycorrect.core.transfer.DeviceProfile
import dev.keycorrect.core.transfer.FORMAT_VERSION
import dev.keycorrect.core.transfer.ImportMode
import dev.keycorrect.core.transfer.ImportPlan
import dev.keycorrect.core.transfer.TransferBundle
import dev.keycorrect.core.transfer.TransferCodec
import dev.keycorrect.core.transfer.TransferManifest
import dev.keycorrect.ime.LearningHost

/** The stores the backup reads (and reloads after a restore); the app wires the SQL ones. */
interface TransferStores {
    suspend fun words(): List<UserWord>

    suspend fun shortcuts(): List<Shortcut>

    suspend fun emoji(): EmojiHistoryDump

    /**
     * Refreshes in-memory views (dictionary and shortcut snapshots, emoji recents) after a restore.
     */
    suspend fun reload()
}

/** What an import did. */
data class ImportResult(val applied: TransferCounts, val skippedTouch: Boolean)

/**
 * Settings & learned-data export / import (docs/TRANSFER.md). Export reads every store into a
 * [TransferBundle]; import applies one under an [ImportPlan] as a single unit: the encrypted
 * database tables change in ONE transaction ([PersonalDataWriter]), the live learning model is
 * replaced on the learning thread only after that committed ([LearningHost.replaceState]), and the
 * settings (written first, restored if the database write fails) follow. On any failure nothing has
 * changed. Nothing but counts and error classes is ever logged.
 */
@Suppress("LongParameterList") // The app's stores, injected for tests.
class TransferService(
    private val settings: SettingsRepository,
    private val stores: TransferStores,
    private val learning: LearningHost,
    private val writer: PersonalDataWriter,
    private val currentDevice: suspend () -> DeviceProfile,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The device's data as a bundle (counts for the UI come from [TransferCounts.of]). */
    suspend fun buildBundle(): TransferBundle {
        val state = readState(learning.exportState())
        val manifest =
            TransferManifest(
                formatVersion = FORMAT_VERSION,
                appVersion = appVersion,
                createdAtMillis = clock(),
                device = currentDevice(),
            )
        return TransferMapper.toBundle(state, manifest)
    }

    /** Serialises [bundle]; [passphrase] (zeroed by the codec) makes it an encrypted file. */
    fun encode(bundle: TransferBundle, passphrase: CharArray?): ByteArray =
        TransferCodec.write(bundle, passphrase)

    /** The plan for [bundle] on this device, for the screen's warning and the apply step. */
    suspend fun plan(
        bundle: TransferBundle,
        mode: ImportMode,
        importTouchAnyway: Boolean,
    ): ImportPlan = ImportPlan.create(bundle.manifest, currentDevice(), mode, importTouchAnyway)

    /** True when [bundle] comes from a differently sized screen than this one. */
    suspend fun screenDiffers(bundle: TransferBundle): Boolean =
        plan(bundle, ImportMode.Merge, false).screenDiffers

    suspend fun import(bundle: TransferBundle, plan: ImportPlan): ImportResult {
        val priorSettings = settings.exportValues()
        val words = stores.words()
        val shortcuts = stores.shortcuts()
        val emoji = stores.emoji()
        var outcome: ImportOutcome? = null
        learning.replaceState(
            keepJournal = plan.mode == ImportMode.Merge,
            prepare = { current ->
                ImportApplier.apply(
                        DeviceState(priorSettings, words, shortcuts, emoji, current),
                        bundle,
                        plan,
                    )
                    .also { outcome = it }
                    .learning
            },
            commit = { state, journal ->
                val o = checkNotNull(outcome)
                settings.applyValues(o.settings, o.replaceSettings, o.keepLocalSettings)
                try {
                    writer.replaceAll(PersonalData(o.words, o.shortcuts, o.emoji, state, journal))
                } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                    // The database rolled back; put the settings back too (best effort).
                    runCatching { settings.applyValues(priorSettings, replace = true) }
                    throw t
                }
            },
        )
        stores.reload()
        return ImportResult(checkNotNull(outcome).counts, skippedTouch = !plan.includesScreenTied)
    }

    private suspend fun readState(learningState: PersistedLearning) =
        DeviceState(
            settings.exportValues(),
            stores.words(),
            stores.shortcuts(),
            stores.emoji(),
            learningState,
        )
}
