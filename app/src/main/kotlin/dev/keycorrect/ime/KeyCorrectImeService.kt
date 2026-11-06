package dev.keycorrect.ime

import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import dev.keycorrect.appContainer
import dev.keycorrect.core.decoder.rerank.NeuralReranker
import dev.keycorrect.core.designsystem.KeyboardLook
import dev.keycorrect.core.ime.ComposeInputMethodService
import dev.keycorrect.core.input.EditorSession
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.core.settings.SizeProfile
import dev.keycorrect.feature.keyboard.ConfigRestartTracker
import dev.keycorrect.feature.keyboard.EditorIdentity
import dev.keycorrect.feature.keyboard.InlineAutofillController
import dev.keycorrect.feature.keyboard.KeyFeedback
import dev.keycorrect.feature.keyboard.KeyboardController
import dev.keycorrect.feature.keyboard.KeyboardScreen
import dev.keycorrect.feature.keyboard.physicalKeyFor
import dev.keycorrect.feature.keyboard.prediction.PredictionEnvironment
import dev.keycorrect.settings.SettingsActivity
import dev.keycorrect.voice.PackChangeWatcher
import dev.keycorrect.voice.PackRestartPolicy
import dev.keycorrect.voice.Restarter
import dev.keycorrect.voice.killProcess
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The system-facing keyboard service. */
class KeyCorrectImeService : ComposeInputMethodService() {
    private val settingsFlow = MutableStateFlow(KeyboardSettings())

    /** The settings as stored (with every size profile); [settingsFlow] has the current one's. */
    private var storedSettings = KeyboardSettings()
    private var sizeProfile = SizeProfile(tablet = false, landscape = false)

    /**
     * The process-wide learning system; it gates itself on the settings as well as the keyboard.
     */
    private val learningHost
        get() = appContainer.learningHost

    private val rerankExecutorLazy = lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "kc-rerank").apply { isDaemon = true }
        }
    }

    private val neuralHostLazy = lazy { NeuralHost(this, appContainer.modelStore) }
    private val neuralHost by neuralHostLazy

    private val predictionHostLazy = lazy {
        PredictionHost(
            assets,
            appContainer.userDictionary,
            appContainer.shortcuts,
            appContainer.applicationScope,
            Dispatchers.IO,
            learning = learningHost,
            touchKey = { controller.currentTouchKey },
            reranker =
                NeuralReranker(
                    neuralHost,
                    rerankExecutorLazy.value,
                    lmStrength = { settingsFlow.value.lmStrength },
                    phraseGenerator = neuralHost,
                    phrasesEnabled = { settingsFlow.value.neuralPhrases },
                ),
        )
    }
    private val predictionHost by predictionHostLazy

    private val controller: KeyboardController by lazy {
        KeyboardController(
            EditorSession(
                connection = { currentInputConnection },
                editorInfo = { currentInputEditorInfo },
            ),
            appContainer.keyboardComposer,
            PredictionEnvironment.create(this, predictionHost),
            learning = learningHost,
            wallClock = appContainer.wallClock,
            wordFeedback = { kind -> feedback.onWordEvent(kind, settingsFlow.value) },
            emoji = appContainer.emojiEnvironment,
            clipboard = appContainer.clipboardEnvironment,
            voiceEnvironment = appContainer.voiceHost,
        )
    }

    private val feedback by lazy { KeyFeedback(this) }

    /** Takes what is copied while we are the keyboard into the clipboard history. */
    private val clipboardCapture by lazy {
        ClipboardCapture(this, appContainer.clipboardEnvironment, appContainer.applicationScope)
    }

    private val imagePaster by lazy {
        ClipImagePaster(this) { PasteTarget(currentInputConnection, currentInputEditorInfo) }
    }

    private val autofill by lazy { InlineAutofillController(this, mainExecutor) }

    /** The field the last [onStartInput] was for; see [EditorIdentity.continues]. */
    private var inputIdentity: EditorIdentity? = null

    /**
     * An updated or removed voice pack can't replace the native libraries this process loaded, so
     * the process is restarted (the system starts the keyboard again when it is next shown) once
     * the keyboard is hidden and nothing is being dictated. See [PackRestartPolicy].
     */
    private val packRestart =
        PackRestartPolicy(
            restarter = Restarter { restartAfterFlush() },
            dictating = { controller.voice.dictating },
        )

    private val packWatcher by lazy {
        PackChangeWatcher(this, packRestart) { appContainer.voiceHost.voicePackLibrariesStale() }
    }

    /** Saves the learned data first, then ends the process if the keyboard is still hidden. */
    private fun restartAfterFlush() {
        appContainer.applicationScope.launch(Dispatchers.Main) {
            withTimeoutOrNull(RESTART_FLUSH_MS) { learningHost.flush() }
            if (!isInputViewShown && !controller.voice.dictating) {
                killProcess.restart()
            } else {
                packRestart.requestRestart() // shown again meanwhile: wait for the next hide
            }
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // The wallpaper colors may have changed while the keyboard was hidden.
        colorRefresh++
        neuralHost.onWindowShown()
    }

    override fun onWindowHidden() {
        handledKeys.clear()
        super.onWindowHidden()
        neuralHost.onWindowHidden()
        packRestart.onKeyboardHidden()
    }

    override fun onCreate() {
        super.onCreate()
        updateDevice(resources.configuration)
        packWatcher.register()
        appContainer.voiceHost.restarter = {
            // "Tap to restart": hide the keyboard; the restart happens when its window is hidden.
            packRestart.requestRestart()
            requestHideSelf(0)
        }
        // Dictionary and user data load in the background; suggestions appear once they're ready.
        predictionHost.start()
        clipboardCapture.register()
        appContainer.clipboardEnvironment.imagePaster = imagePaster
        lifecycleScope.launch {
            appContainer.settingsRepository.settings.collect { stored ->
                val physicalChanged =
                    stored.physicalKeyboardSuggestions != storedSettings.physicalKeyboardSuggestions
                storedSettings = stored
                val sized = stored.forProfile(sizeProfile)
                settingsFlow.value = sized
                if (!sized.inlineAutofill) autofill.clear()
                controller.updateSettings(sized)
                predictionHost.applySettings(sized)
                neuralHost.apply(sized)
                appContainer.voiceHost.applySettings(sized)
                if (physicalChanged) updateInputViewShown()
            }
        }
    }

    /**
     * Rotation and other configuration changes restart the input view of the same field; those
     * restarts keep the layer (see [ConfigRestartTracker]). Every other start of the input view
     * opens on the letters layer.
     */
    private val configRestarts = ConfigRestartTracker(SystemClock::uptimeMillis)

    override fun onConfigurationChanged(newConfig: Configuration) {
        configRestarts.onConfigurationChanged(isInputViewShown)
        super.onConfigurationChanged(newConfig)
        updateDevice(newConfig)
        updateInputViewShown()
    }

    /**
     * With a physical keyboard attached the system hides the input view (unless the user turned on
     * "show on-screen keyboard"). We show it as the slim suggestion bar instead, unless the user
     * turned that off; the framework default then applies.
     */
    override fun onEvaluateInputViewShown(): Boolean =
        if (
            storedSettings.physicalKeyboardSuggestions &&
                DeviceProfile.from(resources.configuration).hardwareKeyboard
        ) {
            true
        } else {
            super.onEvaluateInputViewShown()
        }

    /** The keys whose press we handled, so their release is not passed on to the app alone. */
    private val handledKeys = HashSet<Int>()

    /**
     * A physical key reaches the same prediction pipeline as the on-screen keys; the controller
     * decides what is ours (printable keys, space, backspace) and what is the app's (shortcuts,
     * navigation, function keys, everything in sensitive fields), which falls through untouched.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val handled =
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                backHandled()
            } else {
                isInputViewShown && handlePhysical(event)
            }
        if (handled) handledKeys += keyCode
        // With only the slim bar up, Back belongs to the app (the framework would hide the bar).
        val toApp = keyCode == KeyEvent.KEYCODE_BACK && !handled && backToApp()
        return handled || (!toApp && super.onKeyDown(keyCode, event))
    }

    /** Back with a physical keyboard: an open panel closes first. */
    private fun backHandled() =
        isInputViewShown && controller.physicalMode && controller.onHardwareBack()

    private fun backToApp() = isInputViewShown && controller.backGoesToApp

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        when {
            handledKeys.remove(keyCode) -> true
            keyCode == KeyEvent.KEYCODE_BACK && backToApp() -> false
            else -> super.onKeyUp(keyCode, event)
        }

    /** The system setting "show on-screen keyboard" with a physical keyboard attached. */
    private fun systemShowsKeyboardWithHardware(): Boolean =
        Settings.Secure.getInt(contentResolver, SHOW_IME_WITH_HARD_KEYBOARD, 0) != 0

    private fun handlePhysical(event: KeyEvent): Boolean =
        controller.onPhysicalKey(
            physicalKeyFor(
                event.keyCode,
                event.unicodeChar,
                ctrl = event.isCtrlPressed,
                alt = event.metaState and KeyEvent.META_ALT_LEFT_ON != 0,
                meta = event.isMetaPressed,
            )
        )

    /** Tells the keyboard what we run on; its input profile feeds both events and the decoder. */
    private fun updateDevice(config: Configuration) {
        val profile = DeviceProfile.from(config)
        val display = DisplaySizeProfile.of(this)
        if (display != sizeProfile) {
            // Rotation, folding or moving to a tablet: the keyboard takes that profile's sizes.
            sizeProfile = display
            val sized = storedSettings.forProfile(sizeProfile)
            settingsFlow.value = sized
            controller.updateSettings(sized)
        }
        controller.fullKeyboardByDefault = systemShowsKeyboardWithHardware()
        controller.updateDevice(profile.tablet, profile.landscape, profile.hardwareKeyboard)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            learningHost.requestFlush(FlushReason.TrimMemory)
        }
    }

    /** Bumped each time the keyboard is shown, so the dynamic (wallpaper) colors are read again. */
    private var colorRefresh by mutableIntStateOf(0)

    override fun onDestroy() {
        // The microphone and the recognizer are closed with the service.
        controller.voice.stopNow()
        packWatcher.unregister()
        appContainer.voiceHost.restarter = null
        clipboardCapture.unregister()
        with(appContainer.clipboardEnvironment) {
            if (imagePaster === this@KeyCorrectImeService.imagePaster) imagePaster = null
        }
        learningHost.requestFlush(FlushReason.TrimMemory)
        if (predictionHostLazy.isInitialized()) predictionHost.close()
        if (neuralHostLazy.isInitialized()) neuralHost.close()
        if (rerankExecutorLazy.isInitialized()) rerankExecutorLazy.value.shutdownNow()
        super.onDestroy()
    }

    @Composable
    override fun KeyboardContent() {
        val settings by settingsFlow.collectAsState()
        KeyboardLook(settings, refresh = colorRefresh) {
            NavigationBarIcons()
            KeyboardScreen(
                controller,
                settings,
                feedback,
                autofill = autofill,
                onOpenSettings = ::openSettings,
            )
        }
    }

    /**
     * The system's chevron and keyboard-switcher buttons below the keys are drawn light or dark by
     * the window's appearance flag; follow the keyboard background so they stay visible.
     */
    @Composable
    private fun NavigationBarIcons() {
        val light = MaterialTheme.colorScheme.surfaceContainer.luminance() > LIGHT_BACKGROUND
        SideEffect {
            window.window?.let { w ->
                WindowCompat.getInsetsController(w, w.decorView).isAppearanceLightNavigationBars =
                    light
            }
        }
    }

    /** The shortcut row's gear: our settings app, in its own task (a service has no task). */
    private fun openSettings() {
        startActivity(
            Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Suggestions belong to the focused field, not to the keyboard being visible: they are dropped
     * when the field changes ([onStartInput] for a different field, [onFinishInput]) and survive
     * hiding and re-showing the keyboard on the same field. "Same field" is the controller's
     * definition: a restart with an unchanged [EditorIdentity], not the `restarting` flag alone.
     */
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        handledKeys.clear()
        val identity = attribute?.let(EditorIdentity::from)
        val sameField = identity != null && identity.continues(inputIdentity, restarting)
        inputIdentity = identity
        if (!sameField) autofill.clear()
    }

    override fun onFinishInput() {
        handledKeys.clear()
        controller.onFinishInput()
        autofill.clear()
        super.onFinishInput()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        handledKeys.clear()
        controller.onFinishInput()
        configRestarts.reset()
        learningHost.requestFlush(FlushReason.KeyboardHidden)
        super.onFinishInputView(finishingInput)
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        if (shouldRecordField(editorInfo.inputType, editorInfo.packageName, packageName)) {
            appContainer.lastField = describeField(editorInfo)
        }
        controller.fullKeyboardByDefault = systemShowsKeyboardWithHardware()
        // The controller first: super resumes the lifecycle, which lets Compose draw, and the
        // first frame should show this field's state (layer, shift, strip), not the last one's.
        controller.onStartInput(
            FieldProfile.from(editorInfo),
            settingsFlow.value,
            // The controller keeps the layer for a restart of the same field only: a rotation.
            restarting = configRestarts.isConfigRestart(),
            identity = EditorIdentity.from(editorInfo),
            initialSelStart = editorInfo.initialSelStart,
            initialSelEnd = editorInfo.initialSelEnd,
        )
        super.onStartInputView(editorInfo, restarting)
        // Loads the emoji catalog in the background, once per process.
        appContainer.emojiEnvironment.start()
        // Loads the clipboard history in the background and applies its retention rules.
        appContainer.clipboardEnvironment.start()
    }

    /** Asks the autofill service for chips styled like the keyboard; null opts out (menu). */
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? =
        if (settingsFlow.value.inlineAutofill) {
            InlineSuggestionsRequestFactory.create(this, uiExtras)
        } else {
            null
        }

    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean =
        if (settingsFlow.value.inlineAutofill) autofill.onResponse(response) else false

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        controller.onSelectionChanged(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
    }

    private companion object {
        /** How long to wait for the learned data to be saved before restarting the process. */
        const val RESTART_FLUSH_MS = 2_000L

        /** `Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD`, a hidden constant. */
        const val SHOW_IME_WITH_HARD_KEYBOARD = "show_ime_with_hard_keyboard"
    }
}

/** What [KeyCorrectApp]'s Settings shows about a field: its app and declared type, never text. */
private fun describeField(info: EditorInfo): String {
    val type = info.inputType
    val noSuggestions = type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
    val note = if (noSuggestions) ", asks for no suggestions" else ""
    val hex = "inputType 0x${type.toString(16)}, imeOptions 0x${info.imeOptions.toString(16)}"
    return "${info.packageName}: ${FieldProfile.from(info).kind}$note ($hex)"
}

/**
 * Whether a started field is worth recording as the "last text field". Opening this app's own
 * Settings starts the input view with an empty EditorInfo (no input type, our package), which would
 * overwrite the real field the user was diagnosing; a real field of this app, or a no-type field of
 * another app, is still recorded.
 */
internal fun shouldRecordField(inputType: Int, fieldPackage: String?, ownPackage: String): Boolean =
    !(inputType == InputType.TYPE_NULL && fieldPackage == ownPackage)

private const val LIGHT_BACKGROUND = 0.5f
