package dev.keycorrect.feature.keyboard

import android.os.SystemClock
import android.text.InputType
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
    private val ownEdits = OwnEditTracker(clock)
    private var identity: EditorIdentity? = null

    /**
     * True from shift press to release; a key typed meanwhile is a chord. Observable so the keys
     * are drawn shifted while it is held (see [effectiveShift]).
     */
    private var shiftHeld by mutableStateOf(false)
    private var shiftChorded = false

    /** The layer being shown, composed for the current field and settings. */
    var layer: KeyboardLayer by mutableStateOf(composer.compose(keyboardSet, KeyboardSet.LETTERS))
        private set

    var state by mutableStateOf(KeyboardState())
        private set

    /**
     * The shift state keys are drawn (and described) in: [KeyboardState.shift], except that while
     * the shift key is held the keys show shifted whenever a chord would type shifted.
     */
    val effectiveShift: ShiftState
        get() = state.effectiveShift(shiftHeld)

    var enterAction: EnterAction by mutableStateOf(EnterAction.NewLine)
        private set

    var profile: FieldProfile by mutableStateOf(DEFAULT_PROFILE)
        private set

    /**
     * Keyboard height in row units (multiples of the key height setting), the same on every layer
     * of the field: the letters layer's total row weight for the current field and options. Shorter
     * layers (symbols, numpad) stretch their rows to fill it, so switching layers never resizes the
     * IME window.
     */
    var heightUnits: Float by mutableFloatStateOf(DEFAULT_HEIGHT_UNITS)
        private set

    /**
     * Prepares the keyboard for a field. A [restarting] input that has the same [identity] as the
     * previous one (the app re-announcing the same field, e.g. on rotation or when the keyboard is
     * shown again; see [EditorIdentity.continues]) keeps the layer and shift and only refreshes the
     * profile, enter action and auto-capitalisation; anything else is a new field and resets the
     * keyboard.
     */
    fun onStartInput(
        profile: FieldProfile,
        settings: KeyboardSettings,
        restarting: Boolean = false,
        identity: EditorIdentity? = null,
    ) {
        val sameField = identity != null && identity.continues(this.identity, restarting)
        this.identity = identity
        this.profile = profile
        this.settings = settings
        enterAction = profile.enterAction
        val newOptions = composeOptions(profile, settings)
        if (sameField) {
            applyOptions(newOptions)
            updateAutoCapitalize()
            return
        }
        ownEdits.reset()
        shiftHeld = false
        shiftChorded = false
        variant =
            when (profile.kind) {
                FieldKind.Email -> BottomRowVariant.Email
                FieldKind.Url -> BottomRowVariant.Url
                else -> BottomRowVariant.Text
            }
        options = newOptions
        val layerId =
            when (profile.kind) {
                FieldKind.Number -> NUMPAD
                FieldKind.Phone -> PHONE
                FieldKind.DateTime -> DATETIME
                else -> KeyboardSet.LETTERS
            }
        state = KeyboardState(layerId)
        layer = composer.compose(keyboardSet, layerId, variant, options)
        heightUnits = referenceHeightUnits()
        updateAutoCapitalize()
    }

    /** Applies changed [settings]; recomposes the layer if the number row changed. */
    fun updateSettings(settings: KeyboardSettings) {
        this.settings = settings
        applyOptions(composeOptions(profile, settings))
        if (!settings.autoCapitalize && state.shift == ShiftState.Auto) {
            state = state.withAutoShift(false)
        } else {
            updateAutoCapitalize()
        }
    }

    fun onKey(action: KeyAction) {
        when (action) {
            is KeyAction.Text -> {
                markChord()
                editor.commitText(state.transform(action))
                ownEdits.onEdit()
                state = state.afterCharacter(shiftHeld)
                updateAutoCapitalize()
            }
            KeyAction.Space -> {
                markChord()
                editor.commitText(" ")
                ownEdits.onEdit()
                state = state.afterCharacter(shiftHeld)
                updateAutoCapitalize()
            }
            KeyAction.Backspace -> {
                markChord()
                editor.deleteBackward(profile.sensitive)
                ownEdits.onDelete()
                updateAutoCapitalize()
            }
            KeyAction.Enter -> {
                markChord()
                editor.performEnter()
                // Only a committed line break moves the cursor with a callback of its own. In a
                // single-line field Enter is a key event the editor usually swallows, and an IME
                // action does not edit the text.
                if (enterAction == EnterAction.NewLine && profile.multiLine) ownEdits.onEdit()
                updateAutoCapitalize()
            }
            KeyAction.Shift -> state = state.onShift(clock())
            is KeyAction.SwitchLayer -> {
                // A user-edited layout may name a layer that doesn't exist; stay put rather than
                // crash the keyboard.
                val next =
                    runCatching { composer.compose(keyboardSet, action.layerId, variant, options) }
                        .getOrNull() ?: return
                shiftHeld = false
                shiftChorded = false
                state = state.withLayer(action.layerId)
                layer = next
                updateAutoCapitalize()
            }
            KeyAction.None -> Unit
        }
    }

    /**
     * Shift went down: applies the shift transition immediately. Until [onShiftUp], keys typed are
     * a chord: they are shifted without consuming a one-shot shift.
     */
    fun onShiftDown() {
        state = state.onShift(clock())
        shiftHeld = true
        shiftChorded = false
    }

    /** Shift came up. After a chord, shift ends (back to Off) instead of staying on. */
    fun onShiftUp() {
        if (!shiftHeld) return
        shiftHeld = false
        if (!shiftChorded) return
        shiftChorded = false
        state = state.afterShiftChord()
        updateAutoCapitalize()
    }

    /**
     * The cursor moved (typing, tapping in the text, an app edit): re-evaluate auto-caps.
     * [selectionCollapsed] is whether the new selection is a bare cursor. The update caused by an
     * edit we just made is skipped: [onKey] already re-evaluated after it, so asking the editor
     * again would be a second blocking IPC per keystroke (see [OwnEditTracker]). [cursorAtStart] is
     * whether the new selection is a bare cursor at position 0, which lets a following backspace be
     * recognised as one that changes nothing.
     */
    fun onSelectionChanged(selectionCollapsed: Boolean = true, cursorAtStart: Boolean = false) {
        if (ownEdits.consumeIfOwn(selectionCollapsed, cursorAtStart)) return
        updateAutoCapitalize()
    }

    /** A key is committed while shift is held: shift applies to it and is released as a chord. */
    private fun markChord() {
        if (!shiftHeld) return
        shiftChorded = true
        state = state.withChordShift()
    }

    private fun composeOptions(profile: FieldProfile, settings: KeyboardSettings): ComposeOptions =
        if (profile.kind == FieldKind.Number) {
            ComposeOptions(
                numberRow = settings.numberRow || profile.sensitive,
                signedNumber = profile.signedNumber,
                decimalNumber = profile.decimalNumber,
            )
        } else {
            ComposeOptions(numberRow = settings.numberRow || profile.sensitive)
        }

    /** Adopts [newOptions]; recomposes the current layer and the height if they changed. */
    private fun applyOptions(newOptions: ComposeOptions) {
        if (newOptions == options) return
        options = newOptions
        layer = composer.compose(keyboardSet, state.layerId, variant, options)
        heightUnits = referenceHeightUnits()
    }

    /**
     * Total row weight of the letters layer for the current field. The number row is left out for
     * numeric fields: their pads already have the digits, so it would only make them taller.
     */
    private fun referenceHeightUnits(): Float {
        val numeric =
            profile.kind == FieldKind.Number ||
                profile.kind == FieldKind.Phone ||
                profile.kind == FieldKind.DateTime
        val reference =
            runCatching {
                composer.compose(
                    keyboardSet,
                    KeyboardSet.LETTERS,
                    variant,
                    if (numeric) options.copy(numberRow = false) else options,
                )
            }
                .getOrNull() ?: return heightUnits
        return reference.rows.sumOf { it.height.toDouble() }.toFloat()
    }

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
        const val DEFAULT_HEIGHT_UNITS = 4f

        /** Before the first field is focused: plain text, no auto-caps. */
        val DEFAULT_PROFILE = FieldProfile.from(InputType.TYPE_CLASS_TEXT, 0)
    }
}
