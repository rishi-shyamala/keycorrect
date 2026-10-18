package dev.keycorrect.feature.keyboard

import android.text.InputType
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.settings.KeyboardSettings
import org.junit.Test

/** Space on a symbols page returns to the letters after a symbol, not after a digit (round 4). */
class SymbolsSpaceTest {
    private val editor = SimEditor()
    private val controller =
        KeyboardController(
            editor,
            KeyboardComposer(BuiltInLayouts.repository()),
            testEnv(FakeBackend()),
            clock = { 10_000L },
        )

    init {
        controller.onStartInput(
            FieldProfile.from(InputType.TYPE_CLASS_TEXT, 0),
            KeyboardSettings(),
            false,
            null,
            0,
            0,
        )
    }

    private fun key(action: KeyAction) = controller.onKey(action)

    private fun layer() = controller.state.layerId

    @Test
    fun spaceAfterASymbolGoesBackToLetters() {
        key(KeyAction.SwitchLayer("symbols"))
        key(KeyAction.Text("$"))
        key(KeyAction.Space)
        assertThat(editor.text.toString()).isEqualTo("$ ")
        assertThat(layer()).isEqualTo("letters")
        assertThat(controller.layer.id).isEqualTo("letters")
    }

    @Test
    fun spaceAfterADigitStays() {
        key(KeyAction.SwitchLayer("symbols"))
        key(KeyAction.Text("5"))
        key(KeyAction.Space)
        assertThat(layer()).isEqualTo("symbols")
        key(KeyAction.Text("0"))
        key(KeyAction.Space)
        assertThat(layer()).isEqualTo("symbols")
    }

    @Test
    fun spaceAfterASymbolOnThePageTwoGoesBackToLetters() {
        key(KeyAction.SwitchLayer("symbols2"))
        key(KeyAction.Text("!"))
        key(KeyAction.Space)
        assertThat(layer()).isEqualTo("letters")
    }

    @Test
    fun spaceWithNothingTypedOnThePageStays() {
        key(KeyAction.SwitchLayer("symbols"))
        key(KeyAction.Space)
        assertThat(layer()).isEqualTo("symbols")
    }

    @Test
    fun theLastCharTypedOnThePageDecides() {
        key(KeyAction.SwitchLayer("symbols"))
        key(KeyAction.Text("$"))
        key(KeyAction.Text("7"))
        key(KeyAction.Space)
        assertThat(layer()).isEqualTo("symbols")
    }

    @Test
    fun spaceOnTheLettersLayerDoesNothingSpecial() {
        key(KeyAction.Text("$"))
        key(KeyAction.Space)
        assertThat(layer()).isEqualTo("letters")
    }

    @Test
    fun aSymbolTypedBeforeLeavingAndComingBackDoesNotCount() {
        key(KeyAction.SwitchLayer("symbols"))
        key(KeyAction.Text("$"))
        key(KeyAction.SwitchLayer("symbols2"))
        key(KeyAction.Space)
        assertThat(layer()).isEqualTo("symbols2")
    }
}
