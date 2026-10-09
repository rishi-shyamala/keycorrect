package dev.keycorrect.feature.keyboard

import android.os.SystemClock
import android.text.InputType
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.keycorrect.core.input.AutoCapitalize
import dev.keycorrect.core.input.EnterAction
import dev.keycorrect.core.input.FieldKind
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.input.TextEditor
import dev.keycorrect.core.layout.BottomRowVariant
import dev.keycorrect.core.layout.ComposeOptions
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.layout.KeyboardLayer
import dev.keycorrect.core.layout.KeyboardSet
import dev.keycorrect.core.settings.KeyboardSettings

/**
 * Translates key actions into editor operations and owns the keyboard's observable state: the
 * composed [layer], the [state] (layer id and shift), the field's [enterAction] and [profile].
 */
class KeyboardController(
    private val editor: TextEditor,
    private val composer: KeyboardComposer,
    private val clock: () -> Long = SystemClock::uptimeMillis,
) {
    private val keyboardSet = KeyboardSet.Default
    private var settings = KeyboardSettings()
    private var variant = BottomRowVariant.Text
    private var options = ComposeOptions()

    /** The layer being shown, composed for the current field and settings. */
    var layer: KeyboardLayer by mutableStateOf(composer.compose(keyboardSet, KeyboardSet.LETTERS))
        private set

    var state by mutableStateOf(KeyboardState())
        private set

    var enterAction: EnterAction by mutableStateOf(EnterAction.NewLine)
        private set

    var profile: FieldProfile by mutableStateOf(DEFAULT_PROFILE)
        private set

    /** Resets the keyboard for a newly focused field. */
    fun onStartInput(profile: FieldProfile, settings: KeyboardSettings) {
        this.profile = profile
        this.settings = settings
        enterAction = profile.enterAction
        variant =
            when (profile.kind) {
                FieldKind.Email -> BottomRowVariant.Email
                FieldKind.Url -> BottomRowVariant.Url
                else -> BottomRowVariant.Text
            }
        options = ComposeOptions(numberRow = settings.numberRow || profile.sensitive)
        val layerId =
            when (profile.kind) {
                FieldKind.Number -> NUMPAD
                FieldKind.Phone -> PHONE
                FieldKind.DateTime -> DATETIME
                else -> KeyboardSet.LETTERS
            }
        state = KeyboardState(layerId)
        layer = composer.compose(keyboardSet, layerId, variant, options)
        updateAutoCapitalize()
    }

    /** Applies changed [settings]; recomposes the layer if the number row changed. */
    fun updateSettings(settings: KeyboardSettings) {
        this.settings = settings
        val newOptions = ComposeOptions(numberRow = settings.numberRow || profile.sensitive)
        if (newOptions != options) {
            options = newOptions
            layer = composer.compose(keyboardSet, state.layerId, variant, options)
        }
        if (!settings.autoCapitalize && state.shift == ShiftState.Auto) {
            state = state.withAutoShift(false)
        } else {
            updateAutoCapitalize()
        }
    }

    fun onKey(action: KeyAction) {
        when (action) {
            is KeyAction.Text -> {
                editor.commitText(state.transform(action))
                state = state.afterCharacter()
                updateAutoCapitalize()
            }
            KeyAction.Space -> {
                editor.commitText(" ")
                state = state.afterCharacter()
                updateAutoCapitalize()
            }
            KeyAction.Backspace -> {
                editor.deleteBackward()
                updateAutoCapitalize()
            }
            KeyAction.Enter -> {
                editor.performEnter()
                updateAutoCapitalize()
            }
            KeyAction.Shift -> state = state.onShift(clock())
            is KeyAction.SwitchLayer -> {
                // A user-edited layout may name a layer that doesn't exist; stay put rather than
                // crash the keyboard.
                val next =
                    runCatching { composer.compose(keyboardSet, action.layerId, variant, options) }
                        .getOrNull() ?: return
                state = state.withLayer(action.layerId)
                layer = next
                updateAutoCapitalize()
            }
            KeyAction.None -> Unit
        }
    }

    /** The cursor moved (typing, tapping in the text, an app edit): re-evaluate auto-caps. */
    fun onSelectionChanged() = updateAutoCapitalize()

    /**
     * Sets [ShiftState.Auto] when the editor wants a capital at the cursor, clears it otherwise.
     * Does nothing off the letters layer, and never overrides a manual shift (Once or Locked).
     */
    private fun updateAutoCapitalize() {
        if (
            !settings.autoCapitalize ||
                profile.autoCapitalize == AutoCapitalize.None ||
                state.layerId != KeyboardSet.LETTERS ||
                state.shift == ShiftState.Once ||
                state.shift == ShiftState.Locked
        ) {
            return
        }
        val next = state.withAutoShift(editor.cursorCapsMode() != 0)
        if (next != state) state = next
    }

    private companion object {
        const val NUMPAD = "numpad"
        const val PHONE = "phone"
        const val DATETIME = "datetime"

        /** Before the first field is focused: plain text, no auto-caps. */
        val DEFAULT_PROFILE = FieldProfile.from(InputType.TYPE_CLASS_TEXT, 0)
    }
}
