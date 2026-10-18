package dev.keycorrect.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dev.keycorrect.AppContainer
import dev.keycorrect.transfer.BackupController

/** Owns the screen's state so it survives rotation (see [BackupController]). */
class BackupViewModel(saved: SavedStateHandle, container: AppContainer) : ViewModel() {
    val controller = BackupController(container.transferService, container.applicationScope, saved)

    override fun onCleared() = controller.clear()
}
