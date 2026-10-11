package dev.keycorrect

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import dev.keycorrect.core.data.KeyCorrectDatabase
import dev.keycorrect.core.data.ShortcutStore
import dev.keycorrect.core.data.SqlLearningStore
import dev.keycorrect.core.data.SqlShortcutStore
import dev.keycorrect.core.data.SqlUserDictionaryStore
import dev.keycorrect.core.data.UserDictionaryStore
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.settings.SettingsRepository
import dev.keycorrect.core.settings.SettingsStore
import dev.keycorrect.ime.FlushReason
import dev.keycorrect.ime.LearningHost
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher

class KeyCorrectApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) container.onTrimMemory()
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

    /** Wall clock for learning events and the learning thread; one definition for both. */
    val wallClock: () -> Long = System::currentTimeMillis

    /**
     * The learning system (docs/PLAN.md §6), shared by the keyboard and the settings screens. Its
     * own thread restores the saved model in the background as soon as it is first used; until then
     * events queue (bounded). Settings gate it ([LearningHost]).
     */
    private val learningHostLazy = lazy {
        val thread = Executors.newSingleThreadExecutor { task ->
            Thread(task, "keycorrect-learning").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }
        LearningHost(
                openStore = { SqlLearningStore(database) },
                userDictionary = userDictionary,
                scope = CoroutineScope(SupervisorJob() + thread.asCoroutineDispatcher()),
                wallClock = wallClock,
            )
            .also { it.start(settingsRepository.settings) }
    }
    val learningHost: LearningHost by learningHostLazy

    internal fun onTrimMemory() {
        // Do not create (and so restore) the learning system just to save nothing.
        if (learningHostLazy.isInitialized()) learningHost.requestFlush(FlushReason.TrimMemory)
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as KeyCorrectApp).container
