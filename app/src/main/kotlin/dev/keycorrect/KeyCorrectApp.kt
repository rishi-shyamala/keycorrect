package dev.keycorrect

import android.app.Application
import android.content.Context
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
class AppContainer(context: Context) {
    /** Outlives any screen: for writes that must complete even if the UI goes away. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settingsRepository = SettingsRepository(SettingsStore.create(context))
    val keyboardComposer = KeyboardComposer(BuiltInLayouts.repository())
}

val Context.appContainer: AppContainer
    get() = (applicationContext as KeyCorrectApp).container
