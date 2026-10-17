package dev.keycorrect.feature.keyboard

import android.text.InputType
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.input.FieldKind
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.FieldMode
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.settings.KeyboardSettings
import org.junit.Test

/**
 * When the keyboard opens on the letters layer, where a panel's ABC key goes, and the manual field
 * kind switch of plain text fields.
 */
class FieldModeControllerTest {
    private val editor = SimEditor()
    private val controller =
        KeyboardController(editor, KeyboardComposer(BuiltInLayouts.repository()))

    private class Field(val inputType: Int) {
        val profile = FieldProfile.from(inputType, 0)
        val identity = EditorIdentity(1, "app", inputType, 0, null)
    }

    private val text = Field(InputType.TYPE_CLASS_TEXT)
    private val email =
        Field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
    private val password =
        Field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
    private val number = Field(InputType.TYPE_CLASS_NUMBER)

    private fun start(field: Field = text, restarting: Boolean = false) =
        controller.onStartInput(
            field.profile,
            KeyboardSettings(),
            restarting,
            field.identity,
            editor.cursor,
            editor.cursor,
        )

    private fun keys() = controller.layer.rows.flatMap { it.keys }

    private fun modeSwitch() = keys().first { it.label == "?123" }

    private fun offered() =
        keys().flatMap { it.popup }.mapNotNull { it.action as? KeyAction.SetFieldMode }

    private fun pick(mode: FieldMode) = controller.onKey(KeyAction.SetFieldMode(mode))

    // ------------------------------------------------------------------ the letters layer on open

    @Test
    fun theKeyboardShownAgainOnTheSameFieldOpensOnLetters() {
        start()
        controller.onKey(KeyAction.SwitchLayer("symbols"))
        controller.onFinishInput() // hidden
        start(restarting = false) // shown again: not a configuration restart
        assertThat(controller.state.layerId).isEqualTo("letters")
    }

    @Test
    fun anAppRestartOfTheFieldIsNotARotationToTheService() {
        // The service passes restarting = false unless a configuration change caused the restart.
        start()
        controller.onKey(KeyAction.SwitchLayer("symbols"))
        start(restarting = false)
        assertThat(controller.layer.id).isEqualTo("letters")
    }

    @Test
    fun aRotationRestartOfTheSameFieldKeepsTheLayer() {
        start()
        controller.onKey(KeyAction.SwitchLayer("symbols2"))
        start(restarting = true)
        assertThat(controller.state.layerId).isEqualTo("symbols2")
    }

    // ------------------------------------------------------------------ ABC from a panel

    @Test
    fun abcFromTheEmojiPanelGoesToLettersNotTheSymbolsPage() {
        start()
        controller.onKey(KeyAction.SwitchLayer("symbols2"))
        controller.showPanel(KeyboardPanel.Emoji)
        controller.showPanel(KeyboardPanel.Keys)
        assertThat(controller.state.layerId).isEqualTo("letters")
        assertThat(controller.layer.id).isEqualTo("letters")
    }

    @Test
    fun abcFromTheEditingPanelGoesToLetters() {
        start()
        controller.onKey(KeyAction.SwitchLayer("symbols"))
        controller.toggleEditingPanel()
        controller.editing.perform(EditingAction.Close)
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Keys)
        assertThat(controller.state.layerId).isEqualTo("letters")
    }

    @Test
    fun theEditToolBackToTheKeysKeepsTheLayer() {
        start()
        controller.onKey(KeyAction.SwitchLayer("symbols"))
        controller.toggleEditingPanel()
        controller.toggleEditingPanel()
        assertThat(controller.state.layerId).isEqualTo("symbols")
    }

    @Test
    fun abcInANumberFieldReturnsToItsPad() {
        start(number)
        controller.showPanel(KeyboardPanel.Emoji)
        controller.showPanel(KeyboardPanel.Keys)
        assertThat(controller.state.layerId).isEqualTo("numpad")
    }

    // ------------------------------------------------------------------ the manual field switch

    @Test
    fun aPlainTextFieldOffersAllFiveKinds() {
        start()
        assertThat(modeSwitch().popup.map { it.label })
            .containsExactly("Abc", "@", "/", "123", "☎")
            .inOrder()
        assertThat(offered().map { it.mode })
            .containsExactly(
                FieldMode.Text,
                FieldMode.Email,
                FieldMode.Url,
                FieldMode.Number,
                FieldMode.Phone,
            )
            .inOrder()
    }

    @Test
    fun otherFieldKindsDoNotOfferIt() {
        for (field in listOf(email, password, Field(InputType.TYPE_CLASS_PHONE))) {
            start(field)
            assertThat(offered()).isEmpty()
        }
        start(number)
        assertThat(keys().any { it.action is KeyAction.SetFieldMode }).isFalse()
    }

    @Test
    fun pickingEmailGivesTheEmailBottomRowAndRulesUntilTheFieldChanges() {
        start()
        pick(FieldMode.Email)
        assertThat(controller.fieldMode).isEqualTo(FieldMode.Email)
        assertThat(controller.profile.kind).isEqualTo(FieldKind.Email)
        assertThat(controller.profile.autocorrectEnabled).isFalse()
        assertThat(keys().map { it.label }).contains("@")
        // Still reachable: the way back is on the same key.
        assertThat(offered()).isNotEmpty()
        start(restarting = false) // a new opening
        assertThat(controller.fieldMode).isNull()
        assertThat(controller.profile.kind).isEqualTo(FieldKind.Text)
        assertThat(keys().map { it.label }).doesNotContain("@")
    }

    @Test
    fun pickingUrlGivesTheUrlRow() {
        start()
        pick(FieldMode.Url)
        assertThat(controller.profile.kind).isEqualTo(FieldKind.Url)
        assertThat(keys().first { it.label == "." }.popup.map { it.label }).contains(".com")
    }

    @Test
    fun pickingNumberOrPhoneShowsThePadWithAWayBack() {
        start()
        pick(FieldMode.Number)
        assertThat(controller.state.layerId).isEqualTo("numpad")
        assertThat(controller.profile.suggestionsEnabled).isFalse()
        val back = keys().last()
        assertThat(back.label).isEqualTo("ABC")
        assertThat(back.action).isEqualTo(KeyAction.SetFieldMode(FieldMode.Text))
        assertThat(back.popup).isNotEmpty()
        pick(FieldMode.Phone)
        assertThat(controller.state.layerId).isEqualTo("phone")
        pick(FieldMode.Text)
        assertThat(controller.state.layerId).isEqualTo("letters")
        assertThat(controller.fieldMode).isNull()
        assertThat(controller.profile.kind).isEqualTo(FieldKind.Text)
        assertThat(controller.profile.autocorrectEnabled).isTrue()
    }

    @Test
    fun theModeSurvivesARotationRestartOfTheField() {
        start()
        pick(FieldMode.Email)
        start(restarting = true)
        assertThat(controller.fieldMode).isEqualTo(FieldMode.Email)
        assertThat(controller.profile.kind).isEqualTo(FieldKind.Email)
    }

    @Test
    fun aFieldThatDeclaredAKindIgnoresThePick() {
        start(email)
        pick(FieldMode.Number)
        assertThat(controller.fieldMode).isNull()
        assertThat(controller.state.layerId).isEqualTo("letters")
        start(password)
        pick(FieldMode.Phone)
        assertThat(controller.profile.kind).isEqualTo(FieldKind.Password)
    }
}
