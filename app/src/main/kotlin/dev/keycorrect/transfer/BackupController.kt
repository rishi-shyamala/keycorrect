@file:Suppress("SwallowedException", "TooGenericExceptionCaught") // Failures become states.

package dev.keycorrect.transfer

import androidx.lifecycle.SavedStateHandle
import dev.keycorrect.R
import dev.keycorrect.core.transfer.ImportMode
import dev.keycorrect.core.transfer.TransferBundle
import dev.keycorrect.core.transfer.TransferCodec
import dev.keycorrect.core.transfer.TransferException
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ExportState {
    data object Idle : ExportState

    data object Working : ExportState

    data class Done(val counts: TransferCounts) : ExportState

    data class Failed(val messageRes: Int) : ExportState
}

sealed interface ImportState {
    data object Idle : ImportState

    data object Working : ImportState

    /** An encrypted file waiting for its passphrase. */
    class NeedPassphrase(val bytes: ByteArray, val wrong: Boolean) : ImportState

    /** [alreadyImportedAt] is when this very backup was imported here before, if it was. */
    class Review(
        val bundle: TransferBundle,
        val screenDiffers: Boolean,
        val alreadyImportedAt: Long?,
    ) : ImportState

    data class Done(
        val counts: TransferCounts,
        val skippedTouch: Boolean,
        val settingsFailed: Boolean,
        val reloadFailed: Boolean,
    ) : ImportState

    data class Failed(val messageRes: Int) : ImportState
}

/**
 * The state and work of the Backup screen, kept out of the composables so it survives a rotation
 * (the owner is a ViewModel) and can be tested.
 *
 * Privacy rule for export: the passphrase lives only in memory, in [pendingKey]; what survives in
 * [saved] (and so a process death) is just the fact that an ENCRYPTED export was requested. If the
 * key is gone when the file picker answers, the export is cancelled; it never falls back to a plain
 * file.
 *
 * The file work runs on [scope] (the application scope in the app) and cannot be cancelled once
 * begun, so leaving the screen never leaves a half-written file or a half-applied import.
 */
class BackupController(
    private val service: TransferService,
    private val scope: CoroutineScope,
    private val saved: SavedStateHandle,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _export = MutableStateFlow<ExportState>(ExportState.Idle)
    val export: StateFlow<ExportState> = _export

    private val _import = MutableStateFlow<ImportState>(ImportState.Idle)
    val import: StateFlow<ImportState> = _import

    private var pendingKey: CharArray? = null

    // ------------------------------------------------------------------ export

    /**
     * The user pressed Export: remember whether the file must be encrypted and hold the passphrase
     * until the file picker answers. [passphrase] is taken over (zeroed by this class).
     */
    fun beginExport(passphrase: CharArray) {
        pendingKey?.fill('\u0000')
        val encrypted = passphrase.isNotEmpty()
        pendingKey = if (encrypted) passphrase else null
        saved[KEY_ENCRYPTED] = encrypted
        _export.value = ExportState.Idle
    }

    /** The picker closed without a destination. */
    fun cancelExport() {
        pendingKey?.fill('\u0000')
        pendingKey = null
        saved.remove<Boolean>(KEY_ENCRYPTED)
        _export.value = ExportState.Idle
    }

    /** The picker returned a destination: builds the file and hands the bytes to [write]. */
    fun finishExport(write: suspend (ByteArray) -> Unit) {
        val encrypted = saved.get<Boolean>(KEY_ENCRYPTED) == true
        val key = pendingKey
        pendingKey = null
        saved.remove<Boolean>(KEY_ENCRYPTED)
        if (encrypted && key == null) {
            // The activity or process was recreated while the picker was open: the passphrase is
            // gone, and a plain file must never be written in its place.
            _export.value = ExportState.Failed(R.string.backup_export_passphrase_lost)
            return
        }
        _export.value = ExportState.Working
        scope.launch {
            _export.value =
                withContext(NonCancellable + compute) {
                    try {
                        val bundle = service.buildBundle()
                        val bytes = service.encode(bundle, key)
                        check(TransferCodec.isEncrypted(bytes) == encrypted) { "encryption" }
                        withContext(io) { write(bytes) }
                        ExportState.Done(TransferCounts.of(bundle))
                    } catch (e: IllegalStateException) {
                        ExportState.Failed(R.string.backup_export_unavailable)
                    } catch (e: OutOfMemoryError) {
                        ExportState.Failed(R.string.backup_export_failed)
                    } catch (e: Exception) {
                        ExportState.Failed(R.string.backup_export_failed)
                    } finally {
                        key?.fill('\u0000')
                    }
                }
        }
    }

    // ------------------------------------------------------------------ import

    /**
     * A backup file was picked: [open] gives its stream, [sizeHint] the size the system reports.
     */
    fun pickFile(sizeHint: Long?, open: () -> InputStream?) {
        _import.value = ImportState.Working
        scope.launch {
            val bytes =
                withContext(io) {
                    try {
                        open()?.use { TransferCodec.readFile(it, sizeHint) }
                    } catch (e: TransferException.TooLarge) {
                        TOO_LARGE
                    } catch (e: IOException) {
                        null
                    } catch (e: Exception) {
                        null
                    }
                }
            _import.value =
                when {
                    bytes == null -> ImportState.Failed(R.string.backup_error_read)
                    bytes === TOO_LARGE -> ImportState.Failed(R.string.backup_error_too_large)
                    else -> decode(bytes, null)
                }
        }
    }

    /** The passphrase for the encrypted file in [bytes]; [passphrase] is zeroed on every path. */
    fun submitPassphrase(bytes: ByteArray, passphrase: CharArray) {
        _import.value = ImportState.Working
        scope.launch { _import.value = decode(bytes, passphrase) }
    }

    private suspend fun decode(bytes: ByteArray, passphrase: CharArray?): ImportState =
        try {
            if (TransferCodec.isEncrypted(bytes) && passphrase == null) {
                ImportState.NeedPassphrase(bytes, wrong = false)
            } else {
                val bundle =
                    withContext(compute) { TransferCodec.read(bytes) { passphrase?.copyOf() } }
                ImportState.Review(
                    bundle,
                    service.screenDiffers(bundle),
                    service.previouslyImportedAt(bundle),
                )
            }
        } catch (e: TransferException.WrongPassphrase) {
            ImportState.NeedPassphrase(bytes, wrong = true)
        } catch (e: TransferException) {
            ImportState.Failed(errorMessage(e))
        } catch (e: OutOfMemoryError) {
            ImportState.Failed(R.string.backup_error_too_large)
        } catch (e: Exception) {
            ImportState.Failed(R.string.backup_error_corrupt)
        } finally {
            passphrase?.fill('\u0000')
        }

    fun cancelImport() {
        _import.value = ImportState.Idle
    }

    /**
     * Applies the reviewed backup. Merging a backup that was already imported needs [mergeAnyway];
     * it is refused otherwise (the screen keeps the button off).
     */
    fun apply(
        review: ImportState.Review,
        mode: ImportMode,
        importTouchAnyway: Boolean,
        mergeAnyway: Boolean,
    ) {
        if (mode == ImportMode.Merge && review.alreadyImportedAt != null && !mergeAnyway) return
        _import.value = ImportState.Working
        scope.launch {
            _import.value =
                withContext(NonCancellable) {
                    try {
                        val plan = service.plan(review.bundle, mode, importTouchAnyway)
                        val r = service.import(review.bundle, plan)
                        ImportState.Done(
                            r.applied,
                            r.skippedTouch,
                            r.settingsFailed,
                            r.reloadFailed,
                        )
                    } catch (e: Exception) {
                        ImportState.Failed(R.string.backup_import_failed)
                    } catch (e: OutOfMemoryError) {
                        ImportState.Failed(R.string.backup_import_failed)
                    }
                }
        }
    }

    /** Drops the passphrase (the screen is gone for good). */
    fun clear() {
        pendingKey?.fill('\u0000')
        pendingKey = null
    }

    private fun errorMessage(e: TransferException): Int =
        when (e) {
            is TransferException.NotABackup -> R.string.backup_error_not_a_backup
            is TransferException.NewerFormat -> R.string.backup_error_newer
            is TransferException.TooLarge -> R.string.backup_error_too_large
            else -> R.string.backup_error_corrupt
        }

    private companion object {
        const val KEY_ENCRYPTED = "export_encrypted"
        val TOO_LARGE = ByteArray(0)
    }
}
