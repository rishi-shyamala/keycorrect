package dev.keycorrect.feature.keyboard

import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.keycorrect.core.emoji.Emoji
import dev.keycorrect.core.engineapi.TouchPoint
import dev.keycorrect.core.input.AutoCapitalize
import dev.keycorrect.core.input.EnterAction
import dev.keycorrect.core.input.FieldKind
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.input.TextEditor
import dev.keycorrect.core.layout.ComposeOptions
import dev.keycorrect.core.layout.FieldMode
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.layout.KeyboardGeometry
import dev.keycorrect.core.layout.KeyboardLayer
import dev.keycorrect.core.layout.KeyboardSet
import dev.keycorrect.core.learning.DeviceClass
import dev.keycorrect.core.learning.LearningSink
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.feature.keyboard.clipboard.ClipItem
import dev.keycorrect.feature.keyboard.clipboard.ClipboardEnvironment
import dev.keycorrect.feature.keyboard.clipboard.ClipboardPanelState
import dev.keycorrect.feature.keyboard.emoji.EmojiChoice
import dev.keycorrect.feature.keyboard.emoji.EmojiEnvironment
import dev.keycorrect.feature.keyboard.emoji.EmojiPanelState
import dev.keycorrect.feature.keyboard.emoji.EmojiSearch
import dev.keycorrect.feature.keyboard.prediction.KeyLayoutModels
import dev.keycorrect.feature.keyboard.prediction.PredictionEnvironment
import dev.keycorrect.feature.keyboard.prediction.PredictiveInput
import dev.keycorrect.feature.keyboard.prediction.SelectionState
import dev.keycorrect.feature.keyboard.prediction.StripModel
import dev.keycorrect.feature.keyboard.prediction.StripSlot
import dev.keycorrect.feature.keyboard.prediction.SwipeCase
import dev.keycorrect.feature.keyboard.voice.MainThread
import dev.keycorrect.feature.keyboard.voice.Scheduler
import dev.keycorrect.feature.keyboard.voice.VoiceController
import dev.keycorrect.feature.keyboard.voice.VoiceEnvironment
import kotlin.math.roundToInt

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
@Suppress("LargeClass") // the keyboard's one coordinator; collaborators hold the rest
class KeyboardController(
    private val editor: TextEditor,
    composer: KeyboardComposer,
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
    /** The emoji catalog, recents and variant choices (Phase 4, PR B). */
    internal val emoji: EmojiEnvironment = EmojiEnvironment.None,
    /** Clipboard history (Phase 4, PR C). */
    internal val clipboard: ClipboardEnvironment = ClipboardEnvironment.None,
    /** Voice typing: the microphone, its permission and the recognizer (Phase 6). */
    voiceEnvironment: VoiceEnvironment = VoiceEnvironment.None,
    voiceScheduler: Scheduler = MainThread.scheduler,
    voicePost: (() -> Unit) -> Unit = MainThread::post,
) {
    private var settings = KeyboardSettings()
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
            { emoji.catalog },
            emoji::onEmojiUsed,
            { e ->
                EmojiChoice.resolve(e, emoji.preferredVariant(e.value), settings.emojiSkinTone)
            },
            ::onSwipeCommitted,
        )

    private val swipes =
        SwipeController(
            input,
            clock,
            { swipeTypingActive },
            ::swipeCase,
            ::onSwipeCommitted,
        )

    /** Ctrl and Alt of the terminal extra-keys row; observable so the keys draw active. */
    internal val terminalModifiers = TerminalModifiers(clock)

    /** The latest selection the platform reported, as the editing UI needs it. */
    internal var selection: EditorSelection by mutableStateOf(EditorSelection())
        private set

    internal val editing =
        EditingController(
            tracked,
            input::endComposingForMove,
            ::showPanel,
            { selection },
            { profile.sensitive },
            ownEdits::settledSelection,
            clock,
        )

    /**
     * Whether the selection strip replaces the predictions: a range is selected, the field is not
     * sensitive, and no backspace swipe is previewing one. Derived, so a cursor move recomposes
     * nothing.
     */
    val selectionStripVisible: Boolean by derivedStateOf {
        !profile.sensitive && selection.known && !selection.collapsed && !editing.swipeActive
    }

    /**
     * Voice typing (Phase 6). Its edits go through [tracked] like any of ours; when it starts and
     * ends the typing side settles as for an emoji (the composing word is finished, the text
     * context is read again).
     */
    val voice =
        VoiceController(
            tracked,
            voiceEnvironment,
            { settings },
            { profile.sensitive },
            { settings.incognito || !profile.learningEnabled },
            { settings.autoCapitalize && profile.autoCapitalize != AutoCapitalize.None },
            ::settleForVoice,
            voicePost,
            voiceScheduler,
        )

    private fun settleForVoice() {
        input.endComposingForMove()
        composingNow = input.isComposing
        fieldEmpty.noteTextTyped()
        updateAutoCapitalize()
    }

    /** The spacebar cursor and backspace swipe, for the key surface (Phase 4). */
    val gestures: KeyGestures = SearchAwareGestures()

    /** The emoji search; while it is active the letter keys edit its query (PR B). */
    internal val emojiSearch = EmojiSearch()

    /** The emoji panel's open tab, kept while the search or the keys are shown. */
    internal val emojiPanel = EmojiPanelState()

    private val searching: Boolean
        get() = panel == KeyboardPanel.Emoji && emojiSearch.active

    /** Space and backspace gestures act on the editor, never while a search owns the keys. */
    private inner class SearchAwareGestures : KeyGestures {
        override fun onCursorDrag(dx: Int, dy: Int, select: Boolean) {
            if (!searching) editing.onCursorDrag(dx, dy, select)
        }

        override fun onBackspaceSwipe(words: Int) {
            if (!searching) editing.onBackspaceSwipe(words)
        }

        override fun onBackspaceSwipeEnd(commit: Boolean) = editing.onBackspaceSwipeEnd(commit)

        // While the emoji search owns the keys, a swipe is not started (swipeTypingActive is off
        // then); one that started earlier is finished or cancelled like any other.
        override fun onSwipeStart(path: SwipePath) {
            if (!searching) swipes.onStart(path)
        }

        override fun onSwipeMove(path: SwipePath) = swipes.onMove(path)

        override fun onSwipeEnd(path: SwipePath) = swipes.onEnd(path)

        override fun onSwipeCancel() = swipes.onCancel()
    }

    private val layers = LayerComposition(composer) { profile }

    /** The layer being shown, composed for the current field and settings. */
    val layer: KeyboardLayer
        get() = layers.layer

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

    /** Whether a physical keyboard is attached (from the configuration, see [updateDevice]). */
    var hardwareKeyboardAttached: Boolean by mutableStateOf(false)
        private set

    /** With a physical keyboard: the full on-screen keyboard is up instead of the slim bar. */
    var fullKeyboardShown: Boolean by mutableStateOf(false)
        private set

    /**
     * Whether the full keyboard is the default with a physical keyboard (the system's "show
     * on-screen keyboard" setting); a new field and attaching or detaching a keyboard return to it.
     */
    var fullKeyboardByDefault: Boolean = false
        set(value) {
            if (field != value) fullKeyboardShown = value
            field = value
        }

    /** Combines a dead-key accent with a character (`KeyCharacterMap.getDeadChar`); 0 for none. */
    var deadCharCombiner: (accent: Int, char: Int) -> Int
        get() = physicalKeys.deadCharCombiner
        set(value) {
            physicalKeys.deadCharCombiner = value
        }

    /** What fills the key area: the keys or a panel (Phase 4). */
    var panel: KeyboardPanel by mutableStateOf(KeyboardPanel.Keys)
        private set

    /**
     * The profile every layout, suggestion and autocorrect decision follows: what the field
     * declared, or, after the user switched a plain text field by hand ([fieldMode]), that kind's.
     */
    var profile: FieldProfile by mutableStateOf(DEFAULT_PROFILE)
        private set

    /** What the focused field declared, whatever [fieldMode] says. */
    private var declaredProfile: FieldProfile = DEFAULT_PROFILE

    /**
     * The kind a plain text field was switched to by hand (long-press ?123), null for none (text).
     * Lasts until the field changes; never set in a field that declared a kind of its own.
     */
    var fieldMode: FieldMode? by mutableStateOf(null)
        private set

    private fun effectiveProfile(): FieldProfile {
        // Ignoring "no suggestions" comes first so a kind the user picked still wins over it.
        val declared =
            if (settings.ignoreNoSuggestions) declaredProfile.ignoringNoSuggestions()
            else declaredProfile
        return when (fieldMode) {
            FieldMode.Email -> declared.withKind(FieldKind.Email)
            FieldMode.Url -> declared.withKind(FieldKind.Url)
            FieldMode.Number -> declared.withKind(FieldKind.Number)
            FieldMode.Phone -> declared.withKind(FieldKind.Phone)
            FieldMode.Text,
            null -> declared
        }
    }

    /** Keyboard height in row units, the same on every layer of the field. */
    val heightUnits: Float
        get() = layers.heightUnits

    /**
     * Prepares the keyboard for a field. The keyboard opens on the field's home layer (letters for
     * text) whenever it is opened for input: a new field, or the same field shown again after the
     * keyboard was hidden. Only a [restarting] input with the same [identity] as the previous one
     * (see [EditorIdentity.continues]) keeps the layer, shift and manual [fieldMode] and just
     * refreshes the profile, enter action and auto-capitalisation. The service passes `restarting`
     * true only for the restart a configuration change (rotation) causes, never for an app's own
     * `restartInput` nor for the keyboard being shown again (see [ConfigRestartTracker]).
     * [initialSelStart]/[initialSelEnd] are the field's selection from `EditorInfo` (-1 when
     * unknown), the starting point for recognising the keyboard's own selection updates.
     */
    fun onStartInput(
        profile: FieldProfile,
        settings: KeyboardSettings,
        restarting: Boolean = false,
        identity: EditorIdentity? = null,
        initialSelStart: Int = -1,
        initialSelEnd: Int = -1,
    ) {
        voice.stopNow()
        val initialSelection = EditorSelection(initialSelStart, initialSelEnd)
        val sameField = identity != null && identity.continues(this.identity, restarting)
        this.identity = identity
        declaredProfile = profile
        if (!sameField || profile.kind != FieldKind.Text) fieldMode = null
        this.settings = settings
        val effective = effectiveProfile()
        this.profile = effective
        if (initialSelection.known || !sameField) selection = initialSelection
        enterAction = profile.enterAction
        input.onStartInput(effective, settings, newField = !sameField, selectionState())
        composingNow = input.isComposing
        if (initialSelection.known || !sameField) ownEdits.reset(initialSelection)
        fieldEmpty.refresh(newField = !sameField)
        val newOptions = composeOptions(effective, settings)
        if (sameField) {
            layers.applyOptions(newOptions, state.layerId)
            updateLayoutKey()
            updateAutoCapitalize()
            warmUpSwipe()
            return
        }
        panel = KeyboardPanel.Keys
        fullKeyboardShown = fullKeyboardByDefault
        physicalKeys.clearPendingAccent()
        emojiSearch.close()
        clipboardPanel.reset()
        editing.reset()
        terminalModifiers.reset()
        rebuildLayers(effective, newOptions)
    }

    /** Starts the layers over for the field's [effective] profile: its home layer, shift off. */
    private fun rebuildLayers(effective: FieldProfile, newOptions: ComposeOptions) {
        shiftHeld = false
        shiftChorded = false
        val layerId = LayerComposition.homeLayerFor(effective.kind)
        state = KeyboardState(layerId)
        layers.rebuild(effective.kind, newOptions, layerId)
        updateLayoutKey()
        updateAutoCapitalize()
        warmUpSwipe()
    }

    /**
     * Applies changed [settings]; recomposes the layer if the number row or bottom-key width
     * changed.
     */
    fun updateSettings(settings: KeyboardSettings) {
        this.settings = settings
        if (!settings.voiceEnabled) voice.stopNow()
        input.updateSettings(settings)
        layers.applyOptions(composeOptions(profile, settings), state.layerId)
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
        voice.stopNow()
        if (searching) onSearchKey(action) else onTypingKey(action, touch)
        composingNow = input.isComposing
        if (!searching && (action is KeyAction.Text || action == KeyAction.Space)) {
            fieldEmpty.noteTextTyped()
        }
    }

    private fun onTypingKey(action: KeyAction, touch: TouchPoint?) {
        // A swipe lifted earlier whose decode is late is typed first or dropped; a swipe still
        // under another finger is cancelled by any key (see [SwipeController.onKey]).
        input.settlePendingSwipe()
        swipes.onKey(action)
        cancelSwipeFor(action)
        when (action) {
            is KeyAction.Text -> onTextKey(action, touch)
            KeyAction.Space -> onSpaceKey()
            KeyAction.Backspace -> onBackspaceKey()
            KeyAction.Enter -> onEnterKey()
            KeyAction.Shift -> state = state.onShift(clock())
            is KeyAction.SwitchLayer -> switchLayer(action.layerId)
            is KeyAction.KeyCode -> {
                markChord()
                if (!sendWithModifiers(TerminalKey(action.code))) editing.sendRawKey(action.code)
            }
            is KeyAction.Modifier -> terminalModifiers.onTap(action.modifier)
            KeyAction.Emoji -> showPanel(KeyboardPanel.Emoji)
            is KeyAction.SetFieldMode -> switchFieldMode(action.mode)
            // Voice is only ever a long press (see onKeyLongPress), never a tap.
            KeyAction.Voice,
            KeyAction.None -> Unit
        }
    }

    /** The last char typed since the layer last changed; read only while a symbols page is up. */
    private var lastTypedOnSymbols: Char? = null

    private fun onSpaceKey() {
        markChord()
        // Space right after a symbol on a symbols page ends the symbol run: back to the letters.
        // After a digit the page stays, so numbers can be typed.
        val afterSymbol =
            (state.layerId == KeyboardSet.SYMBOLS || state.layerId == KeyboardSet.SYMBOLS2) &&
                lastTypedOnSymbols?.let { !it.isLetterOrDigit() } == true
        if (!sendWithModifiers(TerminalKey(KeyEvent.KEYCODE_SPACE))) input.onSeparator(" ")
        state = state.afterCharacter(shiftHeld)
        updateAutoCapitalize()
        if (afterSymbol) switchLayer(LayerComposition.homeLayerFor(profile.kind))
    }

    private fun onBackspaceKey() {
        markChord()
        if (!sendWithModifiers(TerminalKey(KeyEvent.KEYCODE_DEL))) input.onBackspace()
        updateAutoCapitalize()
    }

    private fun onTextKey(action: KeyAction.Text, touch: TouchPoint?) {
        markChord()
        val typed = state.transform(action)
        if (!sendWithModifiers(keyCodeForText(typed))) input.onText(typed, touch)
        lastTypedOnSymbols = typed.lastOrNull()
        state = state.afterCharacter(shiftHeld)
        updateAutoCapitalize()
    }

    private fun onEnterKey() {
        markChord()
        if (sendWithModifiers(TerminalKey(KeyEvent.KEYCODE_ENTER))) return
        // Only a committed line break moves the cursor with a callback of its own. In a
        // single-line field Enter is a key event the editor usually swallows, and an IME
        // action does not edit the text.
        val lineBreak = enterAction == EnterAction.NewLine && profile.multiLine
        input.beforeEnter(lineBreak)
        editor.performEnter()
        if (lineBreak) ownEdits.onCommit(1)
        updateAutoCapitalize()
    }

    /**
     * The user picked [mode] for a plain text field (the popup of ?123): the keyboard becomes that
     * kind's, with its layout, bottom row and suggestion and autocorrect rules, until the field
     * changes. Text goes back. Ignored in a field that declared a kind of its own.
     */
    private fun switchFieldMode(mode: FieldMode) {
        if (declaredProfile.kind != FieldKind.Text) return
        val next = mode.takeIf { it != FieldMode.Text }
        if (next == fieldMode) return
        input.onLayerSwitch()
        fieldMode = next
        val effective = effectiveProfile()
        profile = effective
        input.onStartInput(effective, settings, newField = false, selectionState())
        composingNow = input.isComposing
        rebuildLayers(effective, composeOptions(effective, settings))
    }

    private fun switchLayer(layerId: String) {
        // A user-edited layout may name a layer that doesn't exist; stay put rather than crash
        // the keyboard.
        if (!layers.show(layerId)) return
        input.onLayerSwitch()
        lastTypedOnSymbols = null
        shiftHeld = false
        shiftChorded = false
        state = state.withLayer(layerId)
        updateLayoutKey()
        updateAutoCapitalize()
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

    /** What is known of the editor's selection, for the prediction layer's delete decisions. */
    private fun selectionState(): SelectionState {
        val s = selection.normalised()
        return when {
            !s.known -> SelectionState.Unknown
            s.collapsed -> SelectionState.Collapsed
            else -> SelectionState.Range
        }
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
        selection = update.normalised()
        input.noteSelection(update.collapsed.takeIf { update.known })
        val verdict = ownEdits.onUpdate(update)
        if (ownEdits.lastUpdateConfirmed) input.confirmEdits()
        // Our own edits don't change what a cursor at 0 has after it; anything else might.
        fieldEmpty.refresh(externalChange = verdict == UpdateVerdict.External)
        // The user (or the app) moved things: a waiting Word/Line/Sentence no longer applies.
        if (verdict == UpdateVerdict.External) {
            editing.dropDeferred()
            // The user moved the cursor: dictation ends where it stands.
            voice.onExternalChange()
        }
        editing.onEditsSettled()
        when (verdict) {
            is UpdateVerdict.Own -> {
                // Dictation's own edits: the typing side settles when it ends (settleForVoice).
                if (voice.dictating) return
                val resync = verdict.composingLength
                if (resync < 0 || input.resyncComposing(resync)) return
            }
            UpdateVerdict.External -> Unit
        }
        input.onExternalChange(collapsed = update.known && update.collapsed)
        composingNow = input.isComposing
        updateAutoCapitalize()
    }

    /**
     * Another finger's key during a backspace swipe would type over the previewed words: the swipe
     * is cancelled first (its own release then changes nothing).
     */
    private fun cancelSwipeFor(action: KeyAction) {
        if (editing.swipeActive && action != KeyAction.Backspace) editing.cancelSwipe()
    }

    /**
     * Shows [panel] in the key area ([KeyboardPanel.Keys] returns to the keys). The emoji panel is
     * never shown in a sensitive field; leaving it ends any emoji search.
     */
    fun showPanel(panel: KeyboardPanel) {
        showPanelKeepingLayer(panel)
        // The panels' ABC key: back to the letters, not to the symbols page that was up before.
        if (
            panel == KeyboardPanel.Keys &&
                state.layerId != LayerComposition.homeLayerFor(profile.kind)
        ) {
            switchLayer(LayerComposition.homeLayerFor(profile.kind))
        }
    }

    /** [showPanel] without the return to the home layer (leaving a panel by a tool or a paste). */
    private fun showPanelKeepingLayer(panel: KeyboardPanel) {
        if (panel == KeyboardPanel.Emoji && profile.sensitive) return
        if (panel != KeyboardPanel.Keys) voice.stopNow()
        if (panel == KeyboardPanel.Clipboard && !clipboardAvailable) return
        if (panel != KeyboardPanel.Emoji) emojiSearch.close()
        if (panel != KeyboardPanel.Clipboard) clipboardPanel.reset()
        if (panel != KeyboardPanel.Editing) editing.dropDeferred()
        this.panel = panel
        // Typing in the emoji search used the letters' shift state: work it out again.
        if (panel == KeyboardPanel.Keys) updateAutoCapitalize()
    }

    // ------------------------------------------------------------------ clipboard (PR C)

    /** Clipboard UI state: the card whose actions are open, and the clear-all confirmation. */
    internal val clipboardPanel = ClipboardPanelState()

    /**
     * Mirrors [PredictiveInput.isComposing] as observable state, so the chip hides while typing.
     */
    private var composingNow by mutableStateOf(false)

    /**
     * Gesture typing is on for the current field: the setting is on and the field is a text, email,
     * URL, name or address field that takes suggestions and has an engine (docs/archive/PHASE5.md
     * P5-5), and the letters are showing (not a panel, not the symbols, not the emoji search). Off
     * in password, number, phone, date and terminal fields, and where the app asks for no
     * suggestions; also off while suggestions are switched off, as the swiped word is committed
     * like a picked suggestion. Read by the touch layer.
     */
    val swipeTypingActive: Boolean
        get() =
            settings.gestureTyping &&
                panel == KeyboardPanel.Keys &&
                state.layerId == KeyboardSet.LETTERS &&
                profile.kind in SWIPE_KINDS &&
                input.canSwipe()

    /** The casing the shift state gives a word swiped now (docs/archive/PHASE5.md). */
    private fun swipeCase(): SwipeCase =
        when (effectiveShift) {
            ShiftState.Locked -> SwipeCase.All
            ShiftState.Once -> SwipeCase.First
            ShiftState.Auto ->
                if (profile.autoCapitalize == AutoCapitalize.Characters) SwipeCase.All
                else SwipeCase.First
            // The shift state describes the text now; the word goes in after the swipe's space.
            ShiftState.Off -> if (swipeCapsAfterSpace()) SwipeCase.First else SwipeCase.None
        }

    private fun swipeCapsAfterSpace(): Boolean =
        settings.autoCapitalize &&
            profile.autoCapitalize != AutoCapitalize.None &&
            input.swipeCapsWanted(profile.autoCapitalize) == true

    /** A swipe typed a word: like a typed letter, it consumes a one-shot shift. */
    private fun onSwipeCommitted() {
        markChord()
        state = state.afterCharacter(shiftHeld)
        composingNow = input.isComposing
        fieldEmpty.noteTextTyped()
        updateAutoCapitalize()
    }

    /** Whether the clipboard panel is offered (see [ClipboardController.available]). */
    val clipboardAvailable: Boolean
        get() = clipboards.available

    /**
     * A key was held. Only the emoji key has a long press: it opens the clipboard when
     * [clipboardAvailable]. Where it is not (history off, sensitive field) the key surface never
     * arms the long press, so a slow press is just a tap that opens the emoji panel; this is the
     * same rule again for callers that reach here another way.
     */
    fun onKeyLongPress(action: KeyAction) {
        when (action) {
            KeyAction.Emoji -> openClipboard()
            KeyAction.Voice -> voice.toggle()
            else -> Unit
        }
    }

    /** Opens the clipboard panel; does nothing when it is not [clipboardAvailable]. */
    fun openClipboard() = showPanel(KeyboardPanel.Clipboard)

    /** The clip shown as a chip in the strip (see [ClipboardController.stripClip]). */
    val stripClip: ClipItem?
        get() = clipboards.stripClip

    /**
     * Whether the strip has content besides the tools (see [stripBusy]), so they sit small at its
     * edges. Observable state only; autofill chips are the UI's to add.
     */
    val stripBusy: Boolean
        get() =
            stripBusy(
                fieldHasText = !fieldEmpty.empty,
                stripEmpty = strip.isEmpty,
                composing = composingNow,
                selectionStrip = selectionStripVisible,
                hasClip = stripClip != null,
            )

    /** Whether the field has no text at all, which is when the strip's tools spread out. */
    private val fieldEmpty =
        FieldEmptyTracker(
            selection = { selection },
            sensitive = { profile.sensitive },
            textAfterCursor = { editor.textAfterCursor(1) },
        )

    /** What the on-screen keys' touches are recorded as; physical keys are their own profile. */
    private var touchDevice = DeviceClass.Phone
    private var landscape = false

    /** The strip's tools for the current field (see [toolsFor]). */
    internal val tools: List<StripTool>
        get() = toolsFor(profile.sensitive, clipboardAvailable, keyboardToggle = physicalMode)

    /**
     * Whether a physical keyboard is attached and the user wants suggestions for it: the host shows
     * the slim bar (or the full keyboard, see [fullKeyboardShown]) and routes its keys here.
     */
    val physicalMode: Boolean
        get() = hardwareKeyboardAttached && settings.physicalKeyboardSuggestions

    /** The hardware Back key: closes an open panel and returns true; false when none is open. */
    fun onHardwareBack(): Boolean {
        if (panel == KeyboardPanel.Keys) return false
        showPanel(KeyboardPanel.Keys)
        return true
    }

    /** Whether Back belongs to the app: the physical-keyboard mode shows only the slim bar. */
    val backGoesToApp: Boolean
        get() = physicalMode && !fullKeyboardShown

    /** Shows the full on-screen keyboard, or goes back to the slim bar. */
    fun toggleFullKeyboard() {
        fullKeyboardShown = !fullKeyboardShown
        if (!fullKeyboardShown && panel != KeyboardPanel.Keys)
            showPanelKeepingLayer(KeyboardPanel.Keys)
    }

    private val physicalKeys =
        PhysicalKeyRouter(
            input,
            { physicalMode },
            { profile },
            { searching },
            voice::stopNow,
            ::onSearchKey,
            { touchDevice },
            { landscape },
            { typed ->
                composingNow = input.isComposing
                if (typed) fieldEmpty.noteTextTyped()
            },
        )

    /**
     * A key of the physical keyboard. Returns whether it was handled (the app must not see it);
     * false means the app gets it untouched, after the word in progress has been finished as typed
     * where that matters. Nothing is handled in sensitive and terminal fields, with suggestions
     * off, or when [physicalMode] is off.
     */
    fun onPhysicalKey(key: PhysicalKey): Boolean = physicalKeys.onKey(key)

    /**
     * Whether the strip's Edit tool is drawn selected: exactly while the editing panel is open,
     * whatever the selection, the strip content or the tools' busy state do. Derived, so the tool
     * recomposes only when the panel opens or closes.
     */
    val editToolSelected: Boolean by derivedStateOf { panel == KeyboardPanel.Editing }

    /** The Edit tool: opens the editing panel, or goes back to the keys from it. */
    fun toggleEditingPanel() {
        if (panel == KeyboardPanel.Editing) showPanelKeepingLayer(KeyboardPanel.Keys)
        else editing.openPanel()
    }

    /** The panels live in the key area, so the slim bar gives way to the full keyboard for them. */
    private fun openFullKeyboard() {
        if (physicalMode) fullKeyboardShown = true
    }

    /** Runs a tool's action; [openSettings] is the host's way to open its settings. */
    internal fun onTool(tool: StripTool, openSettings: () -> Unit) {
        when (tool) {
            StripTool.Edit -> {
                openFullKeyboard()
                toggleEditingPanel()
            }
            StripTool.Emoji -> {
                openFullKeyboard()
                showPanel(KeyboardPanel.Emoji)
            }
            StripTool.Clipboard -> {
                openFullKeyboard()
                openClipboard()
            }
            StripTool.Keyboard -> toggleFullKeyboard()
            StripTool.Settings -> openSettings()
        }
    }

    private val clipboards =
        ClipboardController(
            clipboard,
            clipboardPanel,
            input,
            tracked,
            { profile },
            { panel },
            { composingNow },
            { strip.isEmpty },
            { selectionStripVisible },
            { composingNow = false },
            { showPanelKeepingLayer(KeyboardPanel.Keys) },
            ::updateAutoCapitalize,
        )

    /** The chip's ✕: hides it for [id] (the clip stays in the history). */
    fun dismissClipChip(id: Long) = clipboards.dismissClipChip(id)

    /** Clip [id] was tapped in the panel or on the chip: pastes it into the field. */
    fun onClipPicked(id: Long) = clipboards.onClipPicked(id)

    fun pinClip(id: Long, pinned: Boolean) = clipboards.pinClip(id, pinned)

    fun deleteClip(id: Long) = clipboards.deleteClip(id)

    fun cleanClipUrl(id: Long) = clipboards.cleanClipUrl(id)

    /** "Clear" confirmed: deletes every unpinned clip. */
    fun clearClips() = clipboards.clearClips()

    private val emojiPanelActions =
        EmojiPanelController(
            emoji,
            emojiSearch,
            input,
            tracked,
            { profile },
            { settings },
            { panel },
            { state.layerId },
            ::switchLayer,
            ::updateAutoCapitalize,
        )

    /** An emoji or kaomoji was picked in the emoji panel (see [EmojiPanelController]). */
    fun onEmojiPicked(value: String, base: String = value, record: Boolean = true) =
        emojiPanelActions.onEmojiPicked(value, base, record)

    /** A grid emoji was tapped: inserts its preferred variant, else the default tone, else base. */
    fun onEmojiTap(item: Emoji) = emojiPanelActions.onEmojiTap(item)

    /** The form of [item] a tap inserts. */
    internal fun emojiValue(item: Emoji): String = emojiPanelActions.emojiValue(item)

    /** [variant] was chosen from [item]'s long-press popup: inserts it and remembers the choice. */
    fun onEmojiVariantPicked(item: Emoji, variant: String) =
        emojiPanelActions.onEmojiVariantPicked(item, variant)

    /** Switches the emoji panel to search: the letters layer, typing edits the query. */
    fun openEmojiSearch() = emojiPanelActions.openEmojiSearch()

    /** Leaves the emoji search, back to the emoji grid. */
    fun closeEmojiSearch() = emojiPanelActions.closeEmojiSearch()

    /** A key pressed while the emoji search is active: it edits the query and nothing else. */
    private fun onSearchKey(action: KeyAction) {
        when (action) {
            is KeyAction.Text -> typeInSearch(state.transform(action))
            KeyAction.Space -> typeInSearch(" ")
            KeyAction.Backspace -> emojiSearch.backspace()
            KeyAction.Enter,
            KeyAction.Emoji -> emojiSearch.close()
            KeyAction.Shift -> state = state.onShift(clock())
            is KeyAction.SwitchLayer -> switchLayer(action.layerId)
            else -> Unit
        }
    }

    private fun typeInSearch(text: String) {
        markChord()
        emojiSearch.type(text)
        state = state.afterCharacter(shiftHeld)
    }

    /** The keyboard left the field (input finished or hidden): ends any composing word. */
    fun onFinishInput() {
        physicalKeys.clearPendingAccent()
        voice.stopNow()
        selection = EditorSelection()
        editing.reset()
        input.onFinishInput()
        composingNow = false
        ownEdits.reset()
    }

    /**
     * The key surface was laid out: remember what the keys look like for the decoder. Zero-size
     * geometry (before the first layout) is ignored; each new geometry gets a new id.
     */
    fun onGeometry(geometry: KeyboardGeometry) {
        if (geometry.width <= 0f || geometry.height <= 0f) return
        input.layout = KeyLayoutModels.from(geometry, ++layoutId)
        warmUpSwipe()
    }

    /** Builds the swipe index off the main thread as soon as swiping can be used in this field. */
    private fun warmUpSwipe() {
        if (swipeTypingActive) input.warmUpSwipe()
    }

    /** A strip slot was tapped. */
    fun onSuggestionTap(slot: StripSlot) {
        input.settlePendingSwipe()
        swipes.onCancel()
        input.onStripTap(slot)
        composingNow = input.isComposing
        updateAutoCapitalize()
    }

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
        if (hardwareKeyboard != hardwareKeyboardAttached) {
            fullKeyboardShown = fullKeyboardByDefault
            physicalKeys.clearPendingAccent()
        }
        hardwareKeyboardAttached = hardwareKeyboard
        touchDevice = if (tablet) DeviceClass.Tablet else DeviceClass.Phone
        this.landscape = landscape
        input.setDevice(touchDevice, landscape)
    }

    /**
     * The touch-statistics key of the layout on screen (device, orientation, layer, number row and
     * key height): the single definition shared by the learning events and the decoder's touch
     * personalisation. Safe to read from any thread (the decoder reads it per decode).
     */
    val currentTouchKey: String
        get() = input.touchKey

    /**
     * Layer (and number row), key height and bottom-row key width, plus a geometry version: touch
     * offsets are only comparable within one. The version changes whenever the built-in key
     * positions change (g2: shared key unit, Samsung-like bottom row and symbol pages).
     */
    private fun updateLayoutKey() {
        val rows =
            (if (layers.options.numberRow) "+nr" else "") +
                (if (layers.options.terminalRow) "+tr" else "")
        val bottom = (layers.options.bottomKeyWidth * PERCENT).roundToInt()
        input.setLayoutKey(
            "${state.layerId}$rows@${settings.keyHeightDp}dp/b$bottom/$GEOMETRY_VERSION"
        )
    }

    /**
     * Sends [key] as a key event with the active terminal modifiers (and shift, if it needs it),
     * clearing the one-shot ones; false (nothing sent) when no modifier is on in a terminal field,
     * so the caller takes the normal path. A character with no key code still ends a one-shot
     * modifier and is typed normally.
     */
    private fun sendWithModifiers(key: TerminalKey?): Boolean {
        val modified = profile.kind == FieldKind.Terminal && terminalModifiers.active
        if (modified) {
            val meta = terminalModifiers.metaState
            terminalModifiers.consume()
            if (key != null) {
                val shift =
                    if (key.shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
                editing.sendRawKey(key.code, meta or shift)
            }
        }
        return modified && key != null
    }

    /** A key is committed while shift is held: shift applies to it and is released as a chord. */
    private fun markChord() {
        if (!shiftHeld) return
        shiftChorded = true
        state = state.withChordShift()
    }

    private fun composeOptions(profile: FieldProfile, settings: KeyboardSettings): ComposeOptions =
        LayerComposition.composeOptions(profile, settings, declaredProfile.kind == FieldKind.Text)

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
        /** Bumped when built-in key positions change, so old touch offsets aren't mixed in. */
        const val GEOMETRY_VERSION = "g2"
        const val PERCENT = 100f

        /** Fields where a swipe types a word (P5-5). */
        val SWIPE_KINDS =
            setOf(
                FieldKind.Text,
                FieldKind.Email,
                FieldKind.Url,
                FieldKind.PersonName,
                FieldKind.PostalAddress,
            )

        /** Before the first field is focused: plain text, no auto-caps. */
        val DEFAULT_PROFILE = FieldProfile.from(InputType.TYPE_CLASS_TEXT, 0)
    }
}
