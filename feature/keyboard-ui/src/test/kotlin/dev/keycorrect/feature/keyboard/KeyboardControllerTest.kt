package dev.keycorrect.feature.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.keycorrect.core.input.EnterAction
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.settings.KeyboardSettings
import org.junit.Test

class KeyboardControllerTest {
    private val editor = SimEditor()
    private var now = 0L
    private val controller =
        KeyboardController(editor, KeyboardComposer(BuiltInLayouts.repository()), clock = { now })

    /** A focused field as the platform describes it; [profile] is derived like the service does. */
    private data class Field(
        val inputType: Int,
        val imeOptions: Int = 0,
        val fieldId: Int = 1,
        val packageName: String? = "app.one",
        val privateImeOptions: String? = null,
    ) {
        val profile = FieldProfile.from(inputType, imeOptions)
        val identity =
            EditorIdentity(fieldId, packageName, inputType, imeOptions, privateImeOptions)
    }

    private val textProfile = Field(InputType.TYPE_CLASS_TEXT)
    private val sentences =
        Field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)

    private fun profile(inputType: Int, imeOptions: Int = 0) = Field(inputType, imeOptions)

    private fun start(
        field: Field = textProfile,
        settings: KeyboardSettings = KeyboardSettings(),
        restarting: Boolean = false,
    ) =
        controller.onStartInput(
            field.profile,
            settings,
            restarting,
            field.identity,
            editor.cursor,
            editor.cursor,
        )

    private fun labels() = controller.layer.rows.flatMap { r -> r.keys.map { it.label } }

    private fun popupLabels(label: String) =
        controller.layer.rows.flatMap { it.keys }.first { it.label == label }.popup.map { it.label }

    private fun type(text: String) = text.forEach { controller.onKey(KeyAction.Text("$it")) }

    private val shiftedA = KeyAction.Text("a", "A")

    @Test
    fun fieldKindsPickLayers() {
        val cases =
            mapOf(
                InputType.TYPE_CLASS_NUMBER to "numpad",
                InputType.TYPE_CLASS_PHONE to "phone",
                InputType.TYPE_CLASS_DATETIME to "datetime",
                InputType.TYPE_CLASS_TEXT to "letters",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD to "letters",
            )
        for ((inputType, layerId) in cases) {
            start(profile(inputType))
            assertThat(controller.state.layerId).isEqualTo(layerId)
            assertThat(controller.layer.id).isEqualTo(layerId)
        }
    }

    @Test
    fun currentTouchKeyTracksDeviceLayerNumberRowAndKeyHeight() {
        start()
        assertThat(controller.currentTouchKey).isEqualTo("Phone/port/letters@54dp")

        controller.updateDevice(tablet = true, landscape = true)
        assertThat(controller.currentTouchKey).isEqualTo("Tablet/land/letters@54dp")

        controller.updateSettings(KeyboardSettings(numberRow = true, keyHeightDp = 60))
        assertThat(controller.currentTouchKey).isEqualTo("Tablet/land/letters+nr@60dp")

        controller.onKey(KeyAction.SwitchLayer("symbols"))
        assertThat(controller.currentTouchKey).isEqualTo("Tablet/land/symbols+nr@60dp")

        // A sensitive field forces the number row on, as the layout (and so the key) shows.
        controller.updateDevice(tablet = false, landscape = false, hardwareKeyboard = true)
        start(profile(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertThat(controller.currentTouchKey).isEqualTo("HardwareKeyboard/port/letters+nr@54dp")
    }

    @Test
    fun emailFieldGetsAtSignInBottomRow() {
        start(profile(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertThat(labels()).contains("@")
    }

    @Test
    fun urlFieldGetsSlashKeyWithDotComOnThePeriod() {
        start(profile(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI))
        assertThat(labels()).contains("/")
        assertThat(popupLabels(".")).contains(".com")
    }

    @Test
    fun plainTextHasNeitherAtNorDotCom() {
        start()
        assertThat(labels()).containsNoneOf("@", "/")
        assertThat(popupLabels(".")).doesNotContain(".com")
    }

    @Test
    fun numberRowFollowsSettingAndSensitivity() {
        start()
        val plainRows = controller.layer.rows.size
        start(settings = KeyboardSettings(numberRow = true))
        assertThat(controller.layer.rows.size).isEqualTo(plainRows + 1)
        start(profile(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertThat(controller.layer.rows.size).isEqualTo(plainRows + 1)
        assertThat(controller.profile.sensitive).isTrue()
    }

    private fun emojiKeyWidth() =
        controller.layer.rows.flatMap { it.keys }.first { it.action == KeyAction.Emoji }.width

    @Test
    fun bottomKeyWidthSettingSizesTheBottomRowAndRecomposesOnChange() {
        start()
        assertThat(emojiKeyWidth()).isEqualTo(0.45f)
        val spaceBefore =
            controller.layer.rows.flatMap { it.keys }.first { it.action == KeyAction.Space }
        controller.updateSettings(KeyboardSettings(bottomKeyWidth = 1f))
        assertThat(emojiKeyWidth()).isEqualTo(1f)
        val spaceAfter =
            controller.layer.rows.flatMap { it.keys }.first { it.action == KeyAction.Space }
        assertThat(spaceAfter.width).isLessThan(spaceBefore.width)
        start(settings = KeyboardSettings(bottomKeyWidth = 0.8f))
        assertThat(emojiKeyWidth()).isEqualTo(0.8f)
    }

    @Test
    fun enterActionComesFromProfile() {
        start(profile(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH))
        assertThat(controller.enterAction)
            .isEqualTo(EnterAction.Editor(EditorInfo.IME_ACTION_SEARCH))
    }

    @Test
    fun updateSettingsRecomposesWhenNumberRowChanges() {
        start()
        val before = controller.layer
        controller.updateSettings(KeyboardSettings(numberRow = true))
        assertThat(controller.layer.rows.size).isEqualTo(before.rows.size + 1)
        controller.updateSettings(KeyboardSettings(numberRow = true, keyHeightDp = 60))
        controller.updateSettings(KeyboardSettings())
        assertThat(controller.layer).isEqualTo(before)
    }

    @Test
    fun typesShiftedThenLowerCase() {
        start()
        controller.onKey(KeyAction.Shift)
        controller.onKey(shiftedA)
        controller.onKey(KeyAction.Text("b", "B"))
        controller.onKey(KeyAction.Space)
        assertThat(editor.text.toString()).isEqualTo("Ab ")
    }

    @Test
    fun capsLockKeepsTypingUpperCase() {
        start()
        controller.onKey(KeyAction.Shift)
        now = 100
        controller.onKey(KeyAction.Shift)
        assertThat(controller.state.shift).isEqualTo(ShiftState.Locked)
        controller.onKey(shiftedA)
        controller.onKey(shiftedA)
        assertThat(editor.text.toString()).isEqualTo("AA")
        now = 5_000
        controller.onKey(KeyAction.Shift)
        assertThat(controller.state.shift).isEqualTo(ShiftState.Off)
    }

    @Test
    fun backspaceAndEnterDelegateToEditor() {
        start()
        type("a")
        controller.onKey(KeyAction.Backspace)
        controller.onKey(KeyAction.Enter)
        controller.onKey(KeyAction.None)
        assertThat(editor.text.toString()).isEmpty()
        assertThat(editor.enters).isEqualTo(1)
    }

    @Test
    fun switchLayerRecomposesKeepingVariantAndOptions() {
        start(
            profile(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
            KeyboardSettings(numberRow = true),
        )
        controller.onKey(KeyAction.SwitchLayer("symbols"))
        assertThat(controller.state.layerId).isEqualTo("symbols")
        assertThat(controller.layer.id).isEqualTo("symbols")
        assertThat(labels()).contains("@")
        controller.onKey(KeyAction.SwitchLayer("letters"))
        assertThat(controller.layer.id).isEqualTo("letters")
        assertThat(controller.layer.rows.first().keys.map { it.label }).contains("1")
    }

    @Test
    fun switchingLayerResetsShift() {
        start()
        controller.onKey(KeyAction.Shift)
        controller.onKey(KeyAction.SwitchLayer("symbols"))
        assertThat(controller.state.shift).isEqualTo(ShiftState.Off)
    }

    @Test
    fun autoCapSetsAutoAndConsumesIt() {
        editor.capsMode = TextUtilsCaps
        start(sentences)
        assertThat(controller.state.shift).isEqualTo(ShiftState.Auto)
        editor.capsMode = 0 // typing moved the cursor mid-sentence
        controller.onKey(shiftedA)
        assertThat(editor.text.toString()).isEqualTo("A")
        assertThat(controller.state.shift).isEqualTo(ShiftState.Off)
    }

    @Test
    fun autoCapFollowsEditorAfterEachKey() {
        editor.capsMode = 0
        start(sentences)
        assertThat(controller.state.shift).isEqualTo(ShiftState.Off)
        editor.capsMode = TextUtilsCaps // as if a sentence just ended
        controller.onKey(KeyAction.Space)
        assertThat(controller.state.shift).isEqualTo(ShiftState.Auto)
        controller.onKey(KeyAction.Backspace)
        editor.capsMode = 0
        controller.onKey(KeyAction.Backspace)
        assertThat(controller.state.shift).isEqualTo(ShiftState.Off)
    }

    @Test
    fun selectionChangeReevaluatesAutoCap() {
        start(sentences)
        editor.capsMode = TextUtilsCaps
        controller.onSelectionChanged()
        assertThat(controller.state.shift).isEqualTo(ShiftState.Auto)
        editor.capsMode = 0
        controller.onSelectionChanged()
        assertThat(controller.state.shift).isEqualTo(ShiftState.Off)
    }

    @Test
    fun autoCapDoesNotClearManualOnce() {
        start(sentences)
        controller.onKey(KeyAction.Shift)
        assertThat(controller.state.shift).isEqualTo(ShiftState.Once)
        editor.capsMode = 0
        controller.onSelectionChanged()
        assertThat(controller.state.shift).isEqualTo(ShiftState.Once)
    }

    @Test
    fun autoCapDoesNotTouchCapsLock() {
        editor.capsMode = TextUtilsCaps
        start(sentences)
        controller.onKey(KeyAction.Shift) // Auto -> Off
        now = 100
        controller.onKey(KeyAction.Shift) // double tap -> Locked
        editor.capsMode = 0
        controller.onSelectionChanged()
        assertThat(controller.state.shift).isEqualTo(ShiftState.Locked)
    }

    @Test
    fun autoCapOffWhenFieldOrSettingDisablesIt() {
        editor.capsMode = TextUtilsCaps
        start(textProfile) // no cap flags
        assertThat(controller.state.shift).isEqualTo(ShiftState.Off)
        start(sentences, KeyboardSettings(autoCapitalize = false))
        assertThat(controller.state.shift).isEqualTo(ShiftState.Off)
        assertThat(editor.capsQueries).isEqualTo(0)
    }

    @Test
    fun disablingAutoCapSettingClearsAuto() {
        editor.capsMode = TextUtilsCaps
        start(sentences)
        controller.updateSettings(KeyboardSettings(autoCapitalize = false))
        assertThat(controller.state.shift).isEqualTo(ShiftState.Off)
    }

    @Test
    fun autoCapOnlyOnLettersLayer() {
        editor.capsMode = TextUtilsCaps
        start(sentences)
        controller.onKey(KeyAction.SwitchLayer("symbols"))
        assertThat(controller.state.shift).isEqualTo(ShiftState.Off)
        editor.capsMode = TextUtilsCaps
        controller.onSelectionChanged()
        assertThat(controller.state.shift).isEqualTo(ShiftState.Off)
        controller.onKey(KeyAction.SwitchLayer("letters"))
        assertThat(controller.state.shift).isEqualTo(ShiftState.Auto)
    }

    @Test
    fun backspaceTellsEditorWhetherFieldIsSensitive() {
        start()
        controller.onKey(KeyAction.Backspace)
        start(profile(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        controller.onKey(KeyAction.Backspace)
        assertThat(editor.deletes).containsExactly(false, true).inOrder()
    }

    @Test
    fun restartOnSameFieldKeepsLayerAndShift() {
        start()
        controller.onKey(KeyAction.Shift)
        start(restarting = true)
        assertThat(controller.state.shift).isEqualTo(ShiftState.Once)
        controller.onKey(KeyAction.SwitchLayer("symbols"))
        start(restarting = true)
        assertThat(controller.state.layerId).isEqualTo("symbols")
        assertThat(controller.layer.id).isEqualTo("symbols")
    }

    @Test
    fun restartOnSameFieldRefreshesAutoCap() {
        editor.capsMode = 0
        start(sentences)
        assertThat(controller.state.shift).isEqualTo(ShiftState.Off)
        editor.capsMode = TextUtilsCaps
        start(sentences, restarting = true)
        assertThat(controller.state.shift).isEqualTo(ShiftState.Auto)
        assertThat(controller.profile).isEqualTo(sentences.profile)
    }

    @Test
    fun restartOnSameFieldStillAppliesChangedSettings() {
        start()
        controller.onKey(KeyAction.Shift)
        val noRow = controller.layer
        start(settings = KeyboardSettings(numberRow = true), restarting = true)
        assertThat(controller.layer).isNotEqualTo(noRow)
        assertThat(controller.state.shift).isEqualTo(ShiftState.Once) // same field: kept
    }

    @Test
    fun restartingFlagAloneDoesNotKeepState() {
        // The platform says "restarting", but nothing says it is the same field.
        val base = textProfile
        val different =
            mapOf(
                "fieldId" to base.copy(fieldId = 2),
                "package" to base.copy(packageName = "app.two"),
                "inputType" to base.copy(inputType = InputType.TYPE_CLASS_TEXT or 0x10000),
                "imeOptions" to base.copy(imeOptions = EditorInfo.IME_ACTION_SEARCH),
                "privateImeOptions" to base.copy(privateImeOptions = "x=1"),
            )
        for ((what, next) in different) {
            start(base)
            controller.onKey(KeyAction.Shift)
            controller.onKey(KeyAction.SwitchLayer("symbols"))
            start(next, restarting = true)
            assertWithMessage(what).that(controller.state.layerId).isEqualTo("letters")
            assertWithMessage(what).that(controller.state.shift).isEqualTo(ShiftState.Off)
        }
    }

    @Test
    fun restartWithoutAPreviousFieldResets() {
        start(restarting = true)
        assertThat(controller.state.layerId).isEqualTo("letters")
        controller.onKey(KeyAction.SwitchLayer("symbols"))
        controller.onStartInput(textProfile.profile, KeyboardSettings(), restarting = true)
        assertThat(controller.state.layerId).isEqualTo("letters") // no identity: never "same"
    }

    @Test
    fun restartOnDifferentKindResets() {
        start()
        controller.onKey(KeyAction.Shift)
        controller.onKey(KeyAction.SwitchLayer("symbols"))
        start(profile(InputType.TYPE_CLASS_NUMBER), restarting = true)
        assertThat(controller.state.layerId).isEqualTo("numpad")
        start(
            profile(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI),
            restarting = true,
        )
        assertThat(controller.state.layerId).isEqualTo("letters")
        assertThat(popupLabels(".")).contains(".com")
    }

    @Test
    fun restartWithNewImeActionResetsAndTakesTheEnterAction() {
        start(profile(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH))
        assertThat(controller.enterAction)
            .isEqualTo(EnterAction.Editor(EditorInfo.IME_ACTION_SEARCH))
        start(profile(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEND), restarting = true)
        assertThat(controller.enterAction).isEqualTo(EnterAction.Editor(EditorInfo.IME_ACTION_SEND))
    }

    @Test
    fun newInputOnSameKindResets() {
        start()
        controller.onKey(KeyAction.Shift)
        controller.onKey(KeyAction.SwitchLayer("symbols"))
        start(restarting = false)
        assertThat(controller.state.layerId).isEqualTo("letters")
        assertThat(controller.state.shift).isEqualTo(ShiftState.Off)
    }

    @Test
    fun numberFieldFlagsRestrictTheNumpad() {
        fun numpadLabels(inputType: Int): Set<String> {
            start(profile(inputType))
            return labels().toSet()
        }
        val plain = numpadLabels(InputType.TYPE_CLASS_NUMBER)
        assertThat(plain).containsNoneOf("-", ".", ",")
        val signed = numpadLabels(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED)
        assertThat(signed).contains("-")
        assertThat(signed).containsNoneOf(".", ",")
        val decimal =
            numpadLabels(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        assertThat(decimal).containsAtLeast(".", ",")
        assertThat(decimal).doesNotContain("-")
        val both =
            numpadLabels(
                InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_SIGNED or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL
            )
        assertThat(both).containsAtLeast("-", ".", ",")
    }

    @Test
    fun nonNumberFieldsKeepTheDefaultOptions() {
        start(profile(InputType.TYPE_CLASS_PHONE))
        assertThat(labels()).contains("+")
        start(profile(InputType.TYPE_CLASS_DATETIME))
        assertThat(labels()).isNotEmpty()
    }

    @Test
    fun heightIsTheSameOnEveryLayerOfAField() {
        start()
        val letters = controller.heightUnits
        for (id in listOf("symbols", "symbols2", "letters")) {
            controller.onKey(KeyAction.SwitchLayer(id))
            assertThat(controller.heightUnits).isEqualTo(letters)
        }
        assertThat(letters).isEqualTo(weight(controller.layer))
    }

    @Test
    fun numberRowMakesTheWholeKeyboardOneRowTaller() {
        start()
        val plain = controller.heightUnits
        start(settings = KeyboardSettings(numberRow = true))
        assertThat(controller.heightUnits).isEqualTo(plain + 1f)
        controller.onKey(KeyAction.SwitchLayer("symbols"))
        assertThat(controller.heightUnits).isEqualTo(plain + 1f)
        // A setting change while on symbols resizes the window once, for every layer.
        controller.updateSettings(KeyboardSettings())
        assertThat(controller.heightUnits).isEqualTo(plain)
    }

    @Test
    fun numericPadsAreAsTallAsTextKeyboardsWithoutNumberRow() {
        start()
        val text = controller.heightUnits
        val settings = KeyboardSettings(numberRow = true)
        for (type in
            listOf(
                InputType.TYPE_CLASS_NUMBER,
                InputType.TYPE_CLASS_PHONE,
                InputType.TYPE_CLASS_DATETIME,
            )) {
            start(profile(type), settings)
            assertThat(controller.heightUnits).isEqualTo(text)
        }
    }

    // --- shift press and chord ---

    @Test
    fun shiftActsOnPressAndTapStaysOnAfterRelease() {
        start()
        controller.onShiftDown()
        assertThat(controller.state.shift).isEqualTo(ShiftState.Once)
        controller.onShiftUp()
        assertThat(controller.state.shift).isEqualTo(ShiftState.Once)
        controller.onKey(shiftedA)
        controller.onKey(shiftedA)
        assertThat(editor.text.toString()).isEqualTo("Aa")
    }

    @Test
    fun holdingShiftTypesCapitalsAndReleaseEndsShift() {
        start()
        controller.onShiftDown()
        controller.onKey(shiftedA)
        controller.onKey(KeyAction.Text("b", "B"))
        assertThat(controller.state.shift).isEqualTo(ShiftState.Once)
        controller.onShiftUp()
        assertThat(controller.state.shift).isEqualTo(ShiftState.Off)
        controller.onKey(shiftedA)
        assertThat(editor.text.toString()).isEqualTo("ABa")
    }

    @Test
    fun holdingShiftFromAutoStillTypesCapitals() {
        editor.capsMode = TextUtilsCaps
        start(sentences)
        assertThat(controller.state.shift).isEqualTo(ShiftState.Auto)
        controller.onShiftDown() // cancels Auto...
        assertThat(controller.state.shift).isEqualTo(ShiftState.Off)
        editor.capsMode = 0
        controller.onKey(shiftedA) // ...but the held shift still applies
        controller.onKey(KeyAction.Text("b", "B"))
        controller.onShiftUp()
        controller.onKey(shiftedA)
        assertThat(editor.text.toString()).isEqualTo("ABa")
        assertThat(controller.state.shift).isEqualTo(ShiftState.Off)
    }

    @Test
    fun shiftReleaseAfterChordDoesNotCountAsATapForCapsLock() {
        start()
        now = 1_000
        controller.onShiftDown()
        controller.onKey(shiftedA)
        now = 1_050
        controller.onShiftUp()
        now = 1_100
        controller.onShiftDown()
        assertThat(controller.state.shift).isEqualTo(ShiftState.Once)
    }

    @Test
    fun quickDoubleTapStillLocks() {
        start()
        now = 1_000
        controller.onShiftDown()
        controller.onShiftUp()
        now = 1_100
        controller.onShiftDown()
        controller.onShiftUp()
        assertThat(controller.state.shift).isEqualTo(ShiftState.Locked)
    }

    @Test
    fun shiftReleaseWithoutPressIsIgnored() {
        start()
        controller.onShiftUp()
        assertThat(controller.state.shift).isEqualTo(ShiftState.Off)
    }

    @Test
    fun switchingLayerWhileShiftHeldForgetsTheChord() {
        start()
        controller.onShiftDown()
        controller.onKey(KeyAction.SwitchLayer("symbols"))
        controller.onShiftUp()
        controller.onKey(KeyAction.SwitchLayer("letters"))
        assertThat(controller.state.shift).isEqualTo(ShiftState.Off)
    }

    // --- effective shift (what the keys are drawn as) ---

    @Test
    fun keysDrawShiftedWhileShiftIsHeldAndTypeShifted() {
        start()
        assertThat(controller.effectiveShift).isEqualTo(ShiftState.Off)
        controller.onShiftDown()
        assertThat(controller.effectiveShift).isEqualTo(ShiftState.Once)
        controller.onKey(shiftedA)
        assertThat(controller.effectiveShift).isEqualTo(ShiftState.Once) // still held
        controller.onShiftUp()
        assertThat(controller.effectiveShift).isEqualTo(ShiftState.Off)
        assertThat(editor.text.toString()).isEqualTo("A")
    }

    @Test
    fun keysDrawShiftedWhenHoldingShiftOverAutoCap() {
        editor.capsMode = TextUtilsCaps
        start(sentences)
        assertThat(controller.effectiveShift).isEqualTo(ShiftState.Auto)
        controller.onShiftDown() // the press cancels Auto, but the chord is still shifted
        assertThat(controller.state.shift).isEqualTo(ShiftState.Off)
        assertThat(controller.effectiveShift).isEqualTo(ShiftState.Once)
        controller.onShiftUp()
        assertThat(controller.effectiveShift).isEqualTo(ShiftState.Off)
    }

    @Test
    fun effectiveShiftIsTheRealShiftWhenNotHeld() {
        for (shift in ShiftState.entries) {
            val state = KeyboardState(shift = shift)
            assertThat(state.effectiveShift(shiftHeld = false)).isEqualTo(shift)
        }
        val off = KeyboardState()
        assertThat(off.effectiveShift(shiftHeld = true)).isEqualTo(ShiftState.Once)
        for (shift in listOf(ShiftState.Once, ShiftState.Auto, ShiftState.Locked)) {
            assertThat(KeyboardState(shift = shift).effectiveShift(shiftHeld = true))
                .isEqualTo(shift)
        }
    }

    @Test
    fun rollingChordWithShiftReleasedFirstStillTypesTheCapital() {
        start()
        controller.onShiftDown()
        controller.onShiftUp() // released before the letter is: no chord yet, shift stays Once
        controller.onKey(shiftedA)
        controller.onKey(shiftedA)
        assertThat(editor.text.toString()).isEqualTo("Aa")
        assertThat(controller.state.shift).isEqualTo(ShiftState.Off)
    }

    @Test
    fun shiftChordWithAPopupEntryTypesTheShiftedEntry() {
        start()
        controller.onShiftDown()
        controller.onKey(KeyAction.Text("à", "À")) // a long-press popup entry
        controller.onShiftUp()
        controller.onKey(KeyAction.Text("à", "À"))
        assertThat(editor.text.toString()).isEqualTo("Àà")
        assertThat(controller.state.shift).isEqualTo(ShiftState.Off)
    }

    // --- own-edit suppression ---

    @Test
    fun selectionUpdateCausedByOurEditSkipsTheCapsQuery() {
        start(sentences)
        editor.capsQueries = 0
        controller.onKey(KeyAction.Text("a"))
        assertThat(editor.capsQueries).isEqualTo(1) // the controller's own re-evaluation
        editor.echo(controller) // the platform echoing our edit
        assertThat(editor.capsQueries).isEqualTo(1)
        editor.echo(controller) // a duplicated echo is not an external change either
        assertThat(editor.capsQueries).isEqualTo(1)
        editor.moveCursor(0) // the user taps elsewhere
        editor.echo(controller)
        assertThat(editor.capsQueries).isEqualTo(2)
    }

    @Test
    fun everyKindOfEditIsRecognised() {
        editor.enterInsertsLineBreak = true
        start(
            Field(
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            )
        )
        editor.capsQueries = 0
        controller.onKey(KeyAction.Space)
        controller.onKey(KeyAction.Backspace)
        controller.onKey(KeyAction.Enter) // commits a line break
        assertThat(editor.capsQueries).isEqualTo(3)
        editor.echo(controller) // the three updates arrive as one
        editor.echo(controller)
        assertThat(editor.capsQueries).isEqualTo(3)
    }

    @Test
    fun eachEditIsMatchedByItsOwnState() {
        start(sentences)
        editor.capsQueries = 0
        repeat(5) {
            controller.onKey(KeyAction.Text("a"))
            editor.echo(controller)
        }
        assertThat(editor.capsQueries).isEqualTo(5) // one per key, none per echo
    }

    @Test
    fun enterWithImeActionIsNotCountedAsAnEdit() {
        start(
            profile(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                EditorInfo.IME_ACTION_SEARCH,
            )
        )
        editor.capsQueries = 0
        controller.onKey(KeyAction.Enter)
        editor.echo(controller) // the app moved nothing: the cursor is where it was
        assertThat(editor.capsQueries).isEqualTo(1)
        editor.moveCursor(0)
        editor.setText("abc", at = 1) // an external move that must not be swallowed
        editor.echo(controller)
        assertThat(editor.capsQueries).isEqualTo(2)
    }

    @Test
    fun enterInASingleLineFieldIsNotCountedAsAnEdit() {
        start(sentences) // NewLine action, but a single-line field: Enter is a key event
        editor.capsQueries = 0
        controller.onKey(KeyAction.Enter)
        editor.setText("abc", at = 2) // an app reaction, reported as a move
        editor.echo(controller)
        assertThat(editor.capsQueries).isEqualTo(2)
    }

    @Test
    fun backspaceAtTheStartOfTheFieldExpectsNoUpdate() {
        start(sentences)
        editor.capsQueries = 0
        controller.onKey(KeyAction.Backspace) // deletes nothing, so no callback will follow
        editor.setText("abc", at = 3)
        editor.echo(controller) // an external move: must not be swallowed
        assertThat(editor.capsQueries).isEqualTo(2)
    }

    @Test
    fun aSelectionRangeIsNeverOurs() {
        start(sentences)
        controller.onKey(KeyAction.Text("a"))
        editor.capsQueries = 0
        controller.onSelectionChanged(0, 1)
        assertThat(editor.capsQueries).isEqualTo(1)
        editor.echo(controller) // the pending expectation was cleared, not left behind
        assertThat(editor.capsQueries).isEqualTo(2)
    }

    @Test
    fun newFieldForgetsPendingEdits() {
        start(sentences)
        controller.onKey(KeyAction.Text("a"))
        editor.setText("")
        start(sentences)
        editor.capsQueries = 0
        editor.setText("x", at = 1)
        editor.echo(controller)
        assertThat(editor.capsQueries).isEqualTo(1)
    }

    private fun weight(layer: dev.keycorrect.core.layout.KeyboardLayer): Float =
        layer.rows.sumOf { it.height.toDouble() }.toFloat()

    private companion object {
        /** Any non-zero caps mode, e.g. `TextUtils.CAP_MODE_SENTENCES`. */
        const val TextUtilsCaps = 0x4000
    }

    @Test
    fun theEmojiKeyOpensTheEmojiPanelAndIsAbsentInPasswordFields() {
        start(textProfile)
        assertThat(controller.layer.rows.last().keys.map { it.action }).contains(KeyAction.Emoji)
        controller.onKey(KeyAction.Emoji)
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Emoji)
        start(profile(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Keys)
        assertThat(controller.layer.rows.flatMap { it.keys }.map { it.action })
            .doesNotContain(KeyAction.Emoji)
    }
}
