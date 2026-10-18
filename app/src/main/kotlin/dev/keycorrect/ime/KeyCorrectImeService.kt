package dev.keycorrect.ime

import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import dev.keycorrect.appContainer
import dev.keycorrect.core.designsystem.KeyCorrectTheme
import dev.keycorrect.core.ime.ComposeInputMethodService
import dev.keycorrect.core.input.EditorSession
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.feature.keyboard.ConfigRestartTracker
import dev.keycorrect.feature.keyboard.EditorIdentity
import dev.keycorrect.feature.keyboard.InlineAutofillController
import dev.keycorrect.feature.keyboard.KeyFeedback
import dev.keycorrect.feature.keyboard.KeyboardController
import dev.keycorrect.feature.keyboard.KeyboardScreen
import dev.keycorrect.feature.keyboard.prediction.PredictionEnvironment
import dev.keycorrect.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** The system-facing keyboard service. */
class KeyCorrectImeService : ComposeInputMethodService() {
    private val settingsFlow = MutableStateFlow(KeyboardSettings())

    /**
     * The process-wide learning system; it gates itself on the settings as well as the keyboard.
     */
    private val learningHost
        get() = appContainer.learningHost

    private val predictionHostLazy = lazy {
        PredictionHost(
            assets,
            appContainer.userDictionary,
            appContainer.shortcuts,
            appContainer.applicationScope,
            Dispatchers.IO,
            learning = learningHost,
            touchKey = { controller.currentTouchKey },
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

    override fun onCreate() {
        super.onCreate()
        updateDevice(resources.configuration)
        // Dictionary and user data load in the background; suggestions appear once they're ready.
        predictionHost.start()
        clipboardCapture.register()
        appContainer.clipboardEnvironment.imagePaster = imagePaster
        lifecycleScope.launch {
            appContainer.settingsRepository.settings.collect {
                settingsFlow.value = it
                if (!it.inlineAutofill) autofill.clear()
                controller.updateSettings(it)
                predictionHost.applySettings(it)
                appContainer.voiceHost.applySettings(it)
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
    }

    /** Tells the keyboard what we run on; its input profile feeds both events and the decoder. */
    private fun updateDevice(config: Configuration) {
        val profile = DeviceProfile.from(config)
        controller.updateDevice(profile.tablet, profile.landscape, profile.hardwareKeyboard)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            learningHost.requestFlush(FlushReason.TrimMemory)
        }
    }

    override fun onDestroy() {
        // The microphone and the recognizer are closed with the service.
        controller.voice.stopNow()
        clipboardCapture.unregister()
        with(appContainer.clipboardEnvironment) {
            if (imagePaster === this@KeyCorrectImeService.imagePaster) imagePaster = null
        }
        learningHost.requestFlush(FlushReason.TrimMemory)
        if (predictionHostLazy.isInitialized()) predictionHost.close()
        super.onDestroy()
    }

    @Composable
    override fun KeyboardContent() {
        val settings by settingsFlow.collectAsState()
        KeyCorrectTheme {
            KeyboardScreen(
                controller,
                settings,
                feedback,
                autofill = autofill,
                onOpenSettings = ::openSettings,
            )
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
        val identity = attribute?.let(EditorIdentity::from)
        val sameField = identity != null && identity.continues(inputIdentity, restarting)
        inputIdentity = identity
        if (!sameField) autofill.clear()
    }

    override fun onFinishInput() {
        controller.onFinishInput()
        autofill.clear()
        super.onFinishInput()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        controller.onFinishInput()
        configRestarts.reset()
        learningHost.requestFlush(FlushReason.KeyboardHidden)
        super.onFinishInputView(finishingInput)
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
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
}
