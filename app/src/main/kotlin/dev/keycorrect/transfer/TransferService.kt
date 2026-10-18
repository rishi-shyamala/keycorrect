@file:Suppress("SwallowedException", "TooGenericExceptionCaught") // Failures become states.

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
import dev.keycorrect.core.transfer.backupId
import dev.keycorrect.ime.LearningHost
import kotlin.coroutines.cancellation.CancellationException

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

/**
 * What an import did. The data is in when this is returned; [settingsFailed] and [reloadFailed]
 * report the two steps after the database commit that can fail without undoing it.
 */
data class ImportResult(
    val applied: TransferCounts,
    val skippedTouch: Boolean,
    /** The settings could not be written (the rest of the import is complete). */
    val settingsFailed: Boolean = false,
    /** The in-memory views could not be refreshed: restarting the keyboard finishes the job. */
    val reloadFailed: Boolean = false,
)

/** Remembers which backups were imported here, so a second Merge of one can be flagged. */
interface ImportHistory {
    /** When (wall-clock millis) the backup with [id] was last imported here, or null. */
    fun importedAt(id: String): Long?

    fun record(id: String, atMillis: Long)

    object None : ImportHistory {
        override fun importedAt(id: String): Long? = null

        override fun record(id: String, atMillis: Long) = Unit
    }
}

/**
 * Settings & learned-data export / import (docs/TRANSFER.md). Export reads every store into a
 * [TransferBundle]; import applies one under an [ImportPlan] as a single unit: the encrypted
 * database tables change in ONE transaction ([PersonalDataWriter]), the live learning model is
 * replaced on the learning thread only after that committed ([LearningHost.replaceState]), and the
 * settings are written last. If the database write fails nothing has changed; once it committed the
 * import counts as done, and a failure of a later step is reported in [ImportResult], never as a
 * failed import. Nothing but counts and error classes is ever logged.
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
    private val history: ImportHistory = ImportHistory.None,
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

    /** When this backup was last imported here, or null if it never was. */
    fun previouslyImportedAt(bundle: TransferBundle): Long? =
        history.importedAt(bundle.manifest.backupId())

    suspend fun import(bundle: TransferBundle, plan: ImportPlan): ImportResult {
        val priorSettings = settings.exportValues()
        var outcome: ImportOutcome? = null
        var settingsFailed = false
        learning.replaceState(
            keepJournal = plan.mode == ImportMode.Merge,
            prepare = { current ->
                // Read on the learning thread, in the same step as the commit below.
                val state =
                    DeviceState(
                        priorSettings,
                        stores.words(),
                        stores.shortcuts(),
                        stores.emoji(),
                        current,
                    )
                ImportApplier.apply(state, bundle, plan).also { outcome = it }.learning
            },
            commit = { state, journal ->
                val o = checkNotNull(outcome)
                // Throws with the database unchanged; nothing else has been touched yet.
                writer.replaceAll(PersonalData(o.words, o.shortcuts, o.emoji, state, journal))
                // Committed: the import is done. The settings follow and cannot undo it.
                try {
                    settings.applyValues(o.settings, o.replaceSettings, o.keepLocalSettings)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    settingsFailed = true
                }
            },
        )
        val reloadFailed =
            try {
                stores.reload()
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                true
            }
        history.record(bundle.manifest.backupId(), clock())
        return ImportResult(
            checkNotNull(outcome).counts,
            skippedTouch = !plan.includesScreenTied,
            settingsFailed = settingsFailed,
            reloadFailed = reloadFailed,
        )
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
