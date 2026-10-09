package dev.keycorrect.feature.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.input.EnterAction
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.input.TextEditor
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.settings.KeyboardSettings
import org.junit.Test

class KeyboardControllerTest {
    private class FakeEditor : TextEditor {
        val text = StringBuilder()
        var enters = 0

        /** What [cursorCapsMode] reports; tests set it to mimic the editor. */
        var capsMode = 0
        var capsQueries = 0

        override fun commitText(text: CharSequence) {
            this.text.append(text)
        }

        override fun deleteBackward() {
            if (text.isNotEmpty()) text.setLength(text.length - 1)
        }

        override fun performEnter() {
            enters++
        }

        override fun cursorCapsMode(): Int {
            capsQueries++
            return capsMode
        }
    }

    private val editor = FakeEditor()
    private var now = 0L
    private val controller =
        KeyboardController(editor, KeyboardComposer(BuiltInLayouts.repository())) { now }

    private val textProfile = profile(InputType.TYPE_CLASS_TEXT)
    private val sentences =
        profile(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)

    private fun profile(inputType: Int, imeOptions: Int = 0) =
        FieldProfile.from(inputType, imeOptions)

    private fun start(
        profile: FieldProfile = textProfile,
        settings: KeyboardSettings = KeyboardSettings(),
    ) = controller.onStartInput(profile, settings)

    private fun labels() = controller.layer.rows.flatMap { r -> r.keys.map { it.label } }

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
    fun emailFieldGetsAtSignInBottomRow() {
        start(profile(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertThat(labels()).contains("@")
    }

    @Test
    fun urlFieldGetsDotComKey() {
        start(profile(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI))
        assertThat(labels()).contains(".com")
    }

    @Test
    fun plainTextHasNeitherAtNorDotCom() {
        start()
        assertThat(labels()).containsNoneOf("@", ".com")
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
        controller.onKey(KeyAction.Shift) // Auto -> Once
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

    private companion object {
        /** Any non-zero caps mode, e.g. `TextUtils.CAP_MODE_SENTENCES`. */
        const val TextUtilsCaps = 0x4000
    }
}
