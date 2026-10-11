package dev.keycorrect.feature.keyboard

import android.os.SystemClock
import android.text.InputType
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.keycorrect.core.engineapi.TouchPoint
import dev.keycorrect.core.input.AutoCapitalize
import dev.keycorrect.core.input.EnterAction
import dev.keycorrect.core.input.FieldKind
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.input.TextEditor
import dev.keycorrect.core.layout.BottomRowVariant
import dev.keycorrect.core.layout.ComposeOptions
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.layout.KeyboardGeometry
import dev.keycorrect.core.layout.KeyboardLayer
import dev.keycorrect.core.layout.KeyboardSet
import dev.keycorrect.core.learning.DeviceClass
import dev.keycorrect.core.learning.LearningSink
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.feature.keyboard.prediction.KeyLayoutModels
import dev.keycorrect.feature.keyboard.prediction.PredictionEnvironment
import dev.keycorrect.feature.keyboard.prediction.PredictiveInput
import dev.keycorrect.feature.keyboard.prediction.StripModel
import dev.keycorrect.feature.keyboard.prediction.StripSlot
import dev.keycorrect.feature.keyboard.prediction.WordTouchLog

/**
 * Translates key actions into editor operations and owns the keyboard's observable state: the
 * composed [layer], the [state] (layer id and shift), the field's [enterAction] and [profile].
 *
 * Typing goes through [PredictiveInput] (composing words, autocorrect, suggestions) where the field
 * and settings allow it, and straight to the editor otherwise; the suggestion [strip] it produces
 * is observable state. The controller keeps the shift and auto-capitalisation logic and the
 * matching of selection callbacks to the keyboard's own edits ([OwnEditTracker]).
 *
 * Auto-capitalisation is computed from the text before the cursor when [PredictiveInput] has it
 * cached, and asked of the editor (a blocking call) only otherwise: when input starts, after an
 * external cursor move, or in fields where nothing is read.
 */
class KeyboardController(
    private val editor: TextEditor,
    private val composer: KeyboardComposer,
    prediction: PredictionEnvironment = PredictionEnvironment.Disabled,
    private val clock: () -> Long = SystemClock::uptimeMillis,
    learning: LearningSink = LearningSink.None,
    wallClock: () -> Long = System::currentTimeMillis,
    /**
     * Gets the word events that deserve their own vibration (an autocorrection, an unknown word),
     * once per word at the separator and only if the settings want it. The host plays it (see
     * [KeyFeedback.onWordEvent]).
     */
    wordFeedback: WordFeedback = WordFeedback.None,
) {
    private val keyboardSet = KeyboardSet.Default
    private var settings = KeyboardSettings()
    private var variant = BottomRowVariant.Text
    private var options = ComposeOptions()
    private val ownEdits = OwnEditTracker(clock)
    private val tracked = TrackedEditor(editor, ownEdits)
    private var layoutId = 0L
    private var identity: EditorIdentity? = null

    /**
     * True from shift press to release; a key typed meanwhile is a chord. Observable so the keys
     * are drawn shifted while it is held (see [effectiveShift]).
     */
    private var shiftHeld by mutableStateOf(false)
    private var shiftChorded = false

    /** The suggestions to show above the keys; empty when there are none. */
    var strip: StripModel by mutableStateOf(StripModel.Empty)
        private set

    private val input =
        PredictiveInput(
            tracked,
            prediction,
            { strip = it },
            learning,
            clock,
            wallClock,
            wordFeedback::onWordFeedback,
        )

    /** Touches of recently committed words, in memory only (for Phase 3 learning). */
    val touchLog: WordTouchLog
        get() = input.touchLog

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
     * keyboard. [initialSelStart]/[initialSelEnd] are the field's selection from `EditorInfo` (-1
     * when unknown), the starting point for recognising the keyboard's own selection updates.
     */
    fun onStartInput(
        profile: FieldProfile,
        settings: KeyboardSettings,
        restarting: Boolean = false,
        identity: EditorIdentity? = null,
        initialSelStart: Int = -1,
        initialSelEnd: Int = -1,
    ) {
        val initialSelection = EditorSelection(initialSelStart, initialSelEnd)
        val sameField = identity != null && identity.continues(this.identity, restarting)
        this.identity = identity
        this.profile = profile
        this.settings = settings
        enterAction = profile.enterAction
        input.onStartInput(profile, settings, newField = !sameField)
        if (initialSelection.known || !sameField) ownEdits.reset(initialSelection)
        val newOptions = composeOptions(profile, settings)
        if (sameField) {
            applyOptions(newOptions)
            updateLayoutKey()
            updateAutoCapitalize()
            return
        }
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
        updateLayoutKey()
        updateAutoCapitalize()
    }

    /** Applies changed [settings]; recomposes the layer if the number row changed. */
    fun updateSettings(settings: KeyboardSettings) {
        this.settings = settings
        input.updateSettings(settings)
        applyOptions(composeOptions(profile, settings))
        updateLayoutKey()
        if (!settings.autoCapitalize && state.shift == ShiftState.Auto) {
            state = state.withAutoShift(false)
        } else {
            updateAutoCapitalize()
        }
    }

    /**
     * A key was committed. [touch] is where the finger went down for a plain tap of a character
     * key, null for repeats, popup entries and accessibility actions.
     */
    fun onKey(action: KeyAction, touch: TouchPoint? = null) {
        when (action) {
            is KeyAction.Text -> {
                markChord()
                input.onText(state.transform(action), touch)
                state = state.afterCharacter(shiftHeld)
                updateAutoCapitalize()
            }
            KeyAction.Space -> {
                markChord()
                input.onSeparator(" ")
                state = state.afterCharacter(shiftHeld)
                updateAutoCapitalize()
            }
            KeyAction.Backspace -> {
                markChord()
                input.onBackspace()
                updateAutoCapitalize()
            }
            KeyAction.Enter -> {
                markChord()
                // Only a committed line break moves the cursor with a callback of its own. In a
                // single-line field Enter is a key event the editor usually swallows, and an IME
                // action does not edit the text.
                val lineBreak = enterAction == EnterAction.NewLine && profile.multiLine
                input.beforeEnter(lineBreak)
                editor.performEnter()
                if (lineBreak) ownEdits.onCommit(1)
                updateAutoCapitalize()
            }
            KeyAction.Shift -> state = state.onShift(clock())
            is KeyAction.SwitchLayer -> {
                // A user-edited layout may name a layer that doesn't exist; stay put rather than
                // crash the keyboard.
                val next =
                    runCatching { composer.compose(keyboardSet, action.layerId, variant, options) }
                        .getOrNull() ?: return
                input.onLayerSwitch()
                shiftHeld = false
                shiftChorded = false
                state = state.withLayer(action.layerId)
                layer = next
                updateLayoutKey()
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
     * The platform reported a selection change (`onUpdateSelection`): [selStart]/[selEnd] are the
     * new selection, [composingStart]/[composingEnd] the composing region (-1 when none). Updates
     * explained by the keyboard's own edits are ignored ([OwnEditTracker]), so typing costs no
     * extra work or editor query; an editor that changed the length of our composing word has it
     * adopted. Anything else is external: the composing word ends as it stands, pending suggestions
     * are dropped, and the text around the cursor and auto-caps are re-evaluated. An update with
     * unknown values (the defaults) is external.
     */
    fun onSelectionChanged(
        selStart: Int = -1,
        selEnd: Int = -1,
        composingStart: Int = -1,
        composingEnd: Int = -1,
    ) {
        val update = EditorSelection(selStart, selEnd, composingStart, composingEnd)
        if (update.known) input.noteSelection(update.collapsed)
        when (val verdict = ownEdits.onUpdate(update)) {
            is UpdateVerdict.Own -> {
                val resync = verdict.composingLength
                if (resync < 0 || input.resyncComposing(resync)) return
            }
            UpdateVerdict.External -> Unit
        }
        input.onExternalChange(collapsed = update.known && update.collapsed)
        updateAutoCapitalize()
    }

    /** The keyboard left the field (input finished or hidden): ends any composing word. */
    fun onFinishInput() {
        input.onFinishInput()
        ownEdits.reset()
    }

    /**
     * The key surface was laid out: remember what the keys look like for the decoder. Zero-size
     * geometry (before the first layout) is ignored; each new geometry gets a new id.
     */
    fun onGeometry(geometry: KeyboardGeometry) {
        if (geometry.width <= 0f || geometry.height <= 0f) return
        input.layout = KeyLayoutModels.from(geometry, ++layoutId)
    }

    /** A strip slot was tapped. */
    fun onSuggestionTap(slot: StripSlot) {
        input.onStripTap(slot)
        updateAutoCapitalize()
    }

    /** The "Add word to dictionary" prompt was tapped. */
    fun onAddWordTap() = input.onAddWordTap()

    /**
     * A suggestion was long-pressed: asks, inline in the strip, whether to never suggest it. Does
     * nothing where learning is off (see [canBlockSuggestions]).
     */
    fun onSuggestionLongPress(slot: StripSlot) = input.onSuggestionLongPress(slot)

    /** "Yes" on the never-suggest prompt. */
    fun onNeverSuggestConfirm() = input.onNeverConfirm()

    /** "Cancel" on the never-suggest prompt. */
    fun onNeverSuggestCancel() = input.onNeverCancel()

    /** Whether a long press on a suggestion can block it (false while learning is off). */
    val canBlockSuggestions: Boolean
        get() = input.canBlockSuggestions

    /**
     * Tells the learning events what device the keyboard runs on: [tablet] from the host's screen
     * class, [hardwareKeyboard] when a physical keyboard is attached (it wins), [landscape] from
     * the configuration. Call on start and whenever the configuration changes.
     */
    fun updateDevice(tablet: Boolean, landscape: Boolean, hardwareKeyboard: Boolean = false) {
        val device =
            when {
                hardwareKeyboard -> DeviceClass.HardwareKeyboard
                tablet -> DeviceClass.Tablet
                else -> DeviceClass.Phone
            }
        input.setDevice(device, landscape)
    }

    /**
     * The touch-statistics key of the layout on screen (device, orientation, layer, number row and
     * key height): the single definition shared by the learning events and the decoder's touch
     * personalisation. Safe to read from any thread (the decoder reads it per decode).
     */
    val currentTouchKey: String
        get() = input.touchKey

    /** Layer (and number row) plus key height: touch offsets are only comparable within one. */
    private fun updateLayoutKey() {
        val rows = if (options.numberRow) "+nr" else ""
        input.setLayoutKey("${state.layerId}$rows@${settings.keyHeightDp}dp")
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
        val wanted = input.capsFromCache(profile.autoCapitalize) ?: (editor.cursorCapsMode() != 0)
        val next = state.withAutoShift(wanted)
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
