package dev.keycorrect.feature.keyboard

import android.view.KeyCharacterMap
import dev.keycorrect.core.input.FieldKind
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.learning.DeviceClass
import dev.keycorrect.feature.keyboard.prediction.PredictiveInput

/**
 * Routes the physical keyboard's keys (see [PhysicalKey]) into [PredictiveInput], or into the emoji
 * search while it owns the keys, and holds the dead-key state between two presses.
 */
@Suppress("LongParameterList") // the controller's state reaches it as lambdas
internal class PhysicalKeyRouter(
    private val input: PredictiveInput,
    private val physicalMode: () -> Boolean,
    private val profile: () -> FieldProfile,
    private val searching: () -> Boolean,
    private val stopVoice: () -> Unit,
    private val onSearchKey: (KeyAction) -> Unit,
    private val touchDevice: () -> DeviceClass,
    private val landscape: () -> Boolean,
    /** Runs after every handled-or-not key; [typed] when text went in (the field is not empty). */
    private val afterKey: (typed: Boolean) -> Unit,
) {
    /** The accent of a dead key pressed and not yet combined; 0 for none. */
    private var pendingAccent = 0

    /** Combines a dead-key accent with a character (`KeyCharacterMap.getDeadChar`); 0 for none. */
    var deadCharCombiner: (accent: Int, char: Int) -> Int = { accent, char ->
        KeyCharacterMap.getDeadChar(accent, char)
    }

    /** Forgets a dead key waiting for its letter (new field, finished input, keyboard change). */
    fun clearPendingAccent() {
        pendingAccent = 0
    }

    /**
     * A key of the physical keyboard. Returns whether it was handled (the app must not see it);
     * false means the app gets it untouched. Nothing is handled in sensitive and terminal fields,
     * with suggestions off, or when physical mode is off.
     */
    fun onKey(key: PhysicalKey): Boolean {
        if (!physicalKeysHandled(key)) {
            pendingAccent = 0
            return false
        }
        stopVoice()
        val resolved = resolveDeadKey(key)
        return resolved == null || processPhysical(resolved)
    }

    private fun processPhysical(resolved: PhysicalKey): Boolean {
        // Typing on the physical keys is its own learning profile; touches keep the screen's.
        input.setDevice(DeviceClass.HardwareKeyboard, landscape())
        val handled =
            try {
                if (searching()) onPhysicalSearchKey(resolved) else onPhysicalTypingKey(resolved)
            } finally {
                input.setDevice(touchDevice(), landscape())
            }
        afterKey(handled && !searching() && resolved != PhysicalKey.Backspace)
        return handled
    }

    /**
     * Applies a pending dead key to [key]: the key to process instead, or null when [key] was
     * absorbed (a dead key waiting for its letter, or a backspace that cancelled it).
     */
    private fun resolveDeadKey(key: PhysicalKey): PhysicalKey? {
        val pending = pendingAccent
        if (key !is PhysicalKey.Ignore) pendingAccent = 0
        return when {
            key is PhysicalKey.Dead -> {
                pendingAccent = if (pending == key.accent) 0 else key.accent
                if (pending == 0) null else PhysicalKey.Text(charText(pending))
            }
            pending == 0 -> key
            key is PhysicalKey.Text -> {
                val combined = deadCharCombiner(pending, key.text.codePointAt(0))
                PhysicalKey.Text(
                    if (combined != 0) charText(combined) else charText(pending) + key.text
                )
            }
            key == PhysicalKey.Space -> PhysicalKey.Text(charText(pending))
            key == PhysicalKey.Backspace -> null
            key == PhysicalKey.Ignore -> key.also { pendingAccent = pending }
            else -> key
        }
    }

    private fun charText(codePoint: Int) = String(Character.toChars(codePoint))

    private fun physicalKeysHandled(key: PhysicalKey): Boolean =
        physicalMode() &&
            key != PhysicalKey.Ignore &&
            !profile().sensitive &&
            profile().kind != FieldKind.Terminal &&
            (searching() || input.canPredict())

    private fun onPhysicalTypingKey(key: PhysicalKey): Boolean {
        input.settlePendingSwipe()
        return when (key) {
            is PhysicalKey.Text -> true.also { input.onText(key.text, null) }
            PhysicalKey.Space -> true.also { input.onSeparator(" ") }
            PhysicalKey.Backspace -> true.also { input.onBackspace() }
            PhysicalKey.Enter -> false.also { input.beforeEnter(commitsLineBreak = false) }
            PhysicalKey.Shortcut -> false.also { input.onLayerSwitch() }
            PhysicalKey.Ignore,
            is PhysicalKey.Dead -> false
        }
    }

    private fun onPhysicalSearchKey(key: PhysicalKey): Boolean {
        val action =
            when (key) {
                is PhysicalKey.Text -> KeyAction.Text(key.text)
                PhysicalKey.Space -> KeyAction.Space
                PhysicalKey.Backspace -> KeyAction.Backspace
                PhysicalKey.Enter -> KeyAction.Enter
                else -> return false
            }
        onSearchKey(action)
        return true
    }
}
