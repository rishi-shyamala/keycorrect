package dev.keycorrect

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Paint
import dev.keycorrect.core.data.ClipboardRepository
import dev.keycorrect.core.data.EmojiHistoryStore
import dev.keycorrect.core.data.EncryptedBlobStore
import dev.keycorrect.core.data.KeyCorrectDatabase
import dev.keycorrect.core.data.KeystoreBlobCipher
import dev.keycorrect.core.data.ShortcutStore
import dev.keycorrect.core.data.SqlClipboardStore
import dev.keycorrect.core.data.SqlEmojiHistoryStore
import dev.keycorrect.core.data.SqlLearningStore
import dev.keycorrect.core.data.SqlPersonalDataWriter
import dev.keycorrect.core.data.SqlShortcutStore
import dev.keycorrect.core.data.SqlUserDictionaryStore
import dev.keycorrect.core.data.UserDictionaryStore
import dev.keycorrect.core.emoji.EmojiCatalog
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.learning.model.ForgetResult
import dev.keycorrect.core.settings.SettingsRepository
import dev.keycorrect.core.settings.SettingsStore
import dev.keycorrect.ime.AppClipboardEnvironment
import dev.keycorrect.ime.AppEmojiEnvironment
import dev.keycorrect.ime.FlushReason
import dev.keycorrect.ime.GatedEmojiHistoryStore
import dev.keycorrect.ime.LearningHost
import dev.keycorrect.transfer.AppTransferStores
import dev.keycorrect.transfer.CurrentDevice
import dev.keycorrect.transfer.TransferService
import dev.keycorrect.voice.VoiceHost
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch

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

    private val emojiHistoryStore: EmojiHistoryStore by lazy { SqlEmojiHistoryStore(database) }

    // Same gate as learning: recording needs learning on, incognito off, and known settings.
    @Volatile private var emojiRecording = false
    private val emojiGateStarted = lazy {
        applicationScope.launch {
            settingsRepository.settings.collect {
                emojiRecording = it.learningEnabled && !it.incognito
            }
        }
    }

    /**
     * Emoji recents and variant choices (encrypted). Records nothing in incognito or with learning
     * off ([GatedEmojiHistoryStore]); cleared by "Forget recent learning" and "Reset all learning"
     * ([resetAllLearning], [forgetLearningSince]) and by erasing personal data.
     */
    val emojiHistory: EmojiHistoryStore by lazy {
        emojiGateStarted.value
        GatedEmojiHistoryStore(emojiHistoryStore) { emojiRecording }
    }

    /**
     * The emoji catalog (loaded once per process, on first use of the keyboard), recents and
     * variant choices, shared by every keyboard instance. Start it with
     * [AppEmojiEnvironment.start].
     */
    val emojiEnvironment: AppEmojiEnvironment by lazy {
        AppEmojiEnvironment(
            scope = applicationScope,
            history = { emojiHistory },
            loadCatalog = {
                context.assets.open(EMOJI_ASSET).use { EmojiCatalog.read(it.buffered()) }
            },
            hasGlyph = Paint()::hasGlyph,
            recording = { emojiRecording },
        )
    }

    /**
     * Clipboard image bytes, sealed with a Keystore key, in no-backup storage. Streamed out only by
     * the non-exported [dev.keycorrect.ime.ClipImageProvider].
     */
    val clipboardBlobs: EncryptedBlobStore by lazy {
        EncryptedBlobStore(File(context.noBackupFilesDir, CLIPS_DIR), KeystoreBlobCipher())
    }

    /** Deletes every clipboard image file (erase personal data), under the repository's lock. */
    suspend fun deleteClipboardBlobs() = clipboardRepository.deleteAllBlobs()

    /** Clipboard rows (encrypted database) and image blobs, kept consistent. */
    val clipboardRepository: ClipboardRepository by lazy {
        ClipboardRepository(SqlClipboardStore(database), clipboardBlobs)
    }

    /**
     * The clipboard history the keyboard shows, shared by every keyboard instance. Start it with
     * [AppClipboardEnvironment.start]. Not part of "Reset all learning" (a clipboard history is not
     * learning; the settings clear it explicitly); erased with the personal data.
     */
    val clipboardEnvironment: AppClipboardEnvironment by lazy {
        AppClipboardEnvironment(applicationScope, { clipboardRepository }).also { env ->
            applicationScope.launch { settingsRepository.settings.collect(env::applySettings) }
        }
    }

    /** Settings & learned-data export / import (docs/TRANSFER.md). */
    val transferService: TransferService by lazy {
        val learningStore = SqlLearningStore(database)
        TransferService(
            settings = settingsRepository,
            stores =
                AppTransferStores(userDictionary, shortcuts, emojiHistoryStore) {
                    emojiEnvironment.reloadHistory()
                },
            learning = learningHost,
            writer = SqlPersonalDataWriter(database, learningStore),
            currentDevice = { CurrentDevice.profile(context, settingsRepository) },
            appVersion = BuildConfig.VERSION_NAME,
            clock = wallClock,
        )
    }

    /** Voice typing: permission, microphone and recognizer choice (voice pack, else system). */
    val voiceHost: VoiceHost by lazy { VoiceHost(context) }

    /** "Reset all learning": the learning data plus emoji recents and variant choices. */
    suspend fun resetAllLearning() {
        // Learning first: this also recovers an unavailable store, which a broken database must
        // not block.
        learningHost.resetAll()
        runCatching { emojiHistoryStore.clear() }
        emojiEnvironment.reloadHistory()
    }

    /** "Forget recent learning": the learning from the last [minutes] and the emoji picks too. */
    suspend fun forgetLearningSince(minutes: Int): ForgetResult {
        val result = learningHost.forgetSince(minutes)
        runCatching {
            emojiHistoryStore.clearSince(
                wallClock() - TimeUnit.MINUTES.toMillis(minutes.coerceAtLeast(0).toLong())
            )
        }
        emojiEnvironment.reloadHistory()
        return result
    }

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

private const val EMOJI_ASSET = "emoji/emoji_en.kce"
private const val CLIPS_DIR = "clips"

val Context.appContainer: AppContainer
    get() = (applicationContext as KeyCorrectApp).container
