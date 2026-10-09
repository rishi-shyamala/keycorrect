package dev.keycorrect

import android.app.Application
import android.content.Context
import dev.keycorrect.core.data.KeyCorrectDatabase
import dev.keycorrect.core.data.ShortcutStore
import dev.keycorrect.core.data.SqlShortcutStore
import dev.keycorrect.core.data.SqlUserDictionaryStore
import dev.keycorrect.core.data.UserDictionaryStore
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.settings.SettingsRepository
import dev.keycorrect.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class KeyCorrectApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Process-wide singletons, created once in [KeyCorrectApp]. Manual DI keeps startup cheap. */
class AppContainer(private val context: Context) {
    /** Outlives any screen: for writes that must complete even if the UI goes away. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settingsRepository = SettingsRepository(SettingsStore.create(context))
    val keyboardComposer = KeyboardComposer(BuiltInLayouts.repository())

    /**
     * Encrypted user data in credential-encrypted storage. Opened lazily on first use (the IME is
     * not direct-boot aware, so the user has unlocked by the time anything touches it).
     */
    val database: KeyCorrectDatabase by lazy { KeyCorrectDatabase.create(context) }
    val userDictionary: UserDictionaryStore by lazy { SqlUserDictionaryStore(database) }
    val shortcuts: ShortcutStore by lazy { SqlShortcutStore(database) }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as KeyCorrectApp).container
