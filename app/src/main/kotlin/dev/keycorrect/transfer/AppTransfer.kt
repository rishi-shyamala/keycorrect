package dev.keycorrect.transfer

import android.content.Context
import android.os.Build
import android.view.WindowManager
import dev.keycorrect.core.data.EmojiHistoryDump
import dev.keycorrect.core.data.EmojiHistoryStore
import dev.keycorrect.core.data.Shortcut
import dev.keycorrect.core.data.ShortcutStore
import dev.keycorrect.core.data.UserDictionaryStore
import dev.keycorrect.core.data.UserWord
import dev.keycorrect.core.settings.SettingsRepository
import dev.keycorrect.core.transfer.DeviceProfile
import kotlinx.coroutines.flow.first

/** [TransferStores] on the app's stores. */
class AppTransferStores(
    private val dictionary: UserDictionaryStore,
    private val shortcuts: ShortcutStore,
    private val emoji: EmojiHistoryStore,
    private val reloadEmoji: suspend () -> Unit,
) : TransferStores {
    override suspend fun words(): List<UserWord> = dictionary.all().first()

    override suspend fun shortcuts(): List<Shortcut> = shortcuts.all().first()

    override suspend fun emoji(): EmojiHistoryDump = emoji.dump()

    override suspend fun reload() {
        dictionary.load()
        shortcuts.load()
        reloadEmoji()
    }
}

/** The screen and keyboard geometry of this device, for the manifest and the screen comparison. */
internal object CurrentDevice {
    suspend fun profile(context: Context, settings: SettingsRepository): DeviceProfile {
        val keyboard = settings.settings.first()
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val resources = context.resources
        return DeviceProfile(
            model =
                listOf(Build.MANUFACTURER, Build.MODEL)
                    .filter { it.isNotBlank() }
                    .joinToString(" "),
            screenWidthPx = bounds.width(),
            screenHeightPx = bounds.height(),
            densityDpi = resources.displayMetrics.densityDpi,
            smallestWidthDp = resources.configuration.smallestScreenWidthDp,
            keyHeightDp = keyboard.keyHeightDp,
            numberRow = keyboard.numberRow,
        )
    }
}
