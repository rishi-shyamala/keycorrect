package dev.keycorrect.feature.keyboard

import android.text.InputType
import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.learning.DeviceClass
import dev.keycorrect.core.settings.KeyboardSettings
import org.junit.Test

/** Physical keyboard events through [KeyboardController.onPhysicalKey] and [physicalKeyFor]. */
class PhysicalKeyboardTest {
    private val editor = SimEditor()
    private val backend = FakeBackend()
    private val predictor = ScriptedPredictor()
    private val sink = RecordingSink()
    private var now = 10_000L
    private val controller: KeyboardController

    init {
        backend.predictor = predictor
        backend.known += listOf("the", "hello", "world")
        controller =
            KeyboardController(
                editor,
                KeyboardComposer(BuiltInLayouts.repository()),
                testEnv(backend, scheduler = ManualScheduler()),
                clock = { now },
                learning = sink,
                wallClock = { 1_700_000_000_000L },
            )
        controller.updateDevice(tablet = true, landscape = true, hardwareKeyboard = true)
    }

    private fun start(
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        settings: KeyboardSettings = KeyboardSettings(numberRow = false),
    ) {
        controller.onStartInput(
            FieldProfile.from(inputType, 0),
            settings,
            restarting = false,
            identity = EditorIdentity(1, "pkg", inputType, 0, null),
            initialSelStart = editor.cursor,
            initialSelEnd = editor.cursor,
        )
    }

    /** Presses a letter or digit key the way the service maps it (ASCII, shift gives upper). */
    private fun press(c: Char, shift: Boolean = false): Boolean {
        val ch = if (shift) c.uppercaseChar() else c.lowercaseChar()
        val code = KeyEvent.KEYCODE_A + (c.lowercaseChar() - 'a')
        return controller.onPhysicalKey(physicalKeyFor(code, ch.code, false, false, false))
    }

    private fun type(word: String) = word.forEach { assertThat(press(it)).isTrue() }

    private fun space() =
        controller.onPhysicalKey(
            physicalKeyFor(KeyEvent.KEYCODE_SPACE, ' '.code, false, false, false)
        )

    private fun backspace() =
        controller.onPhysicalKey(physicalKeyFor(KeyEvent.KEYCODE_DEL, 0x7f, false, false, false))

    private fun text() = editor.text.toString()

    @Test
    fun printableKeysComposeAndFillTheStrip() {
        predictor.word("teh", listOf("the"), autocorrect = "the")
        start()
        type("teh")
        assertThat(editor.composing).isNotEmpty()
        assertThat(text()).isEqualTo("teh")
        assertThat(controller.strip.isEmpty).isFalse()
    }

    @Test
    fun spaceAutocorrectsAndOneBackspaceUndoes() {
        predictor.word("teh", listOf("the"), autocorrect = "the")
        start()
        type("teh")
        assertThat(space()).isTrue()
        assertThat(text()).isEqualTo("the ")
        assertThat(backspace()).isTrue()
        assertThat(text()).isEqualTo("teh")
    }

    @Test
    fun backspaceShortensTheComposingWord() {
        start()
        type("hel")
        assertThat(backspace()).isTrue()
        assertThat(text()).isEqualTo("he")
    }

    @Test
    fun shiftGivesCapitals() {
        start()
        assertThat(press('h', shift = true)).isTrue()
        type("ello")
        assertThat(text()).isEqualTo("Hello")
    }

    @Test
    fun punctuationEndsTheWordLikeTheScreenKey() {
        predictor.word("teh", listOf("the"), autocorrect = "the")
        start()
        type("teh")
        val comma = physicalKeyFor(KeyEvent.KEYCODE_COMMA, ','.code, false, false, false)
        assertThat(controller.onPhysicalKey(comma)).isTrue()
        assertThat(text()).isEqualTo("the,")
    }

    @Test
    fun shortcutsFinishTheWordAsTypedAndPassThrough() {
        predictor.word("teh", listOf("the"), autocorrect = "the")
        start()
        type("teh")
        val ctrlA = physicalKeyFor(KeyEvent.KEYCODE_A, 'a'.code, true, false, false)
        assertThat(ctrlA).isEqualTo(PhysicalKey.Shortcut)
        assertThat(controller.onPhysicalKey(ctrlA)).isFalse()
        assertThat(text()).isEqualTo("teh") // not corrected
        assertThat(editor.composing).isEmpty()
    }

    @Test
    fun navigationAndFunctionKeysPassThrough() {
        for (code in
            listOf(
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_TAB,
                KeyEvent.KEYCODE_ESCAPE,
                KeyEvent.KEYCODE_F5,
                KeyEvent.KEYCODE_FORWARD_DEL,
                KeyEvent.KEYCODE_MOVE_HOME,
            )) {
            assertThat(physicalKeyFor(code, 0, false, false, false)).isEqualTo(PhysicalKey.Shortcut)
        }
        assertThat(physicalKeyFor(KeyEvent.KEYCODE_TAB, 9, false, false, false))
            .isEqualTo(PhysicalKey.Shortcut)
        assertThat(physicalKeyFor(KeyEvent.KEYCODE_B, 'b'.code, false, true, false))
            .isEqualTo(PhysicalKey.Shortcut)
        assertThat(physicalKeyFor(KeyEvent.KEYCODE_B, 'b'.code, false, false, true))
            .isEqualTo(PhysicalKey.Shortcut)
    }

    @Test
    fun modifierKeysAloneChangeNothing() {
        start()
        type("hel")
        val shift = physicalKeyFor(KeyEvent.KEYCODE_SHIFT_LEFT, 0, false, false, false)
        assertThat(shift).isEqualTo(PhysicalKey.Ignore)
        assertThat(controller.onPhysicalKey(shift)).isFalse()
        assertThat(editor.composing).isNotEmpty()
    }

    @Test
    fun enterFinishesTheWordWithItsCorrectionThenPassesThrough() {
        predictor.word("teh", listOf("the"), autocorrect = "the")
        start()
        type("teh")
        val enter = physicalKeyFor(KeyEvent.KEYCODE_ENTER, '\n'.code, false, false, false)
        assertThat(enter).isEqualTo(PhysicalKey.Enter)
        assertThat(controller.onPhysicalKey(enter)).isFalse()
        assertThat(editor.composing).isEmpty()
    }

    @Test
    fun controlCharactersPassThroughAndDeadKeysAreDead() {
        assertThat(
                physicalKeyFor(
                    KeyEvent.KEYCODE_GRAVE,
                    Int.MIN_VALUE or '`'.code,
                    false,
                    false,
                    false,
                )
            )
            .isEqualTo(PhysicalKey.Dead('`'.code))
        assertThat(physicalKeyFor(KeyEvent.KEYCODE_BACK, 0, false, false, false))
            .isEqualTo(PhysicalKey.Shortcut)
    }

    @Test
    fun sensitiveFieldsGetNoSuggestionsAndEveryEventPassesThrough() {
        start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertThat(press('a')).isFalse()
        assertThat(space()).isFalse()
        assertThat(backspace()).isFalse()
        assertThat(text()).isEmpty()
    }

    @Test
    fun theSettingOffMeansNothingIsHandled() {
        start(settings = KeyboardSettings(numberRow = false, physicalKeyboardSuggestions = false))
        assertThat(press('a')).isFalse()
        assertThat(text()).isEmpty()
    }

    @Test
    fun withoutAHardwareKeyboardNothingIsHandled() {
        controller.updateDevice(tablet = true, landscape = true, hardwareKeyboard = false)
        start()
        assertThat(press('a')).isFalse()
    }

    @Test
    fun learningRunsUnderTheHardwareKeyboardProfile() {
        start()
        type("hello")
        space()
        val committed = sink.of<dev.keycorrect.core.learning.WordCommitted>().single()
        assertThat(committed.profile.device).isEqualTo(DeviceClass.HardwareKeyboard)
        assertThat(committed.touches.filterNotNull()).isEmpty()
    }

    @Test
    fun theSlimBarGivesWayToTheFullKeyboardForPanelsAndBack() {
        start()
        assertThat(controller.tools).contains(StripTool.Keyboard)
        assertThat(controller.fullKeyboardShown).isFalse()
        controller.onTool(StripTool.Emoji) {}
        assertThat(controller.fullKeyboardShown).isTrue()
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Emoji)
        controller.onTool(StripTool.Keyboard) {}
        assertThat(controller.fullKeyboardShown).isFalse()
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Keys)
    }

    @Test
    fun withoutAHardwareKeyboardThereIsNoToggle() {
        controller.updateDevice(tablet = false, landscape = false, hardwareKeyboard = false)
        start()
        assertThat(controller.tools).doesNotContain(StripTool.Keyboard)
    }

    private fun dead(accent: Char) =
        controller.onPhysicalKey(
            physicalKeyFor(
                KeyEvent.KEYCODE_GRAVE,
                Int.MIN_VALUE or accent.code,
                false,
                false,
                false,
            )
        )

    private fun combiner() {
        controller.deadCharCombiner = { accent, char ->
            if (accent == '\u00b4'.code && char == 'e'.code) 'é'.code else 0
        }
    }

    @Test
    fun aDeadKeyIsHeldBackAndCombinesWithTheNextLetter() {
        start()
        combiner()
        assertThat(
                physicalKeyFor(KeyEvent.KEYCODE_GRAVE, Int.MIN_VALUE or 0xb4, false, false, false)
            )
            .isEqualTo(PhysicalKey.Dead(0xb4))
        assertThat(dead('\u00b4')).isTrue()
        assertThat(text()).isEmpty()
        assertThat(press('e')).isTrue()
        assertThat(text()).isEqualTo("é")
    }

    @Test
    fun aDeadKeyWithoutACombinationTypesTheAccentAndTheLetter() {
        start()
        combiner()
        dead('\u00b4')
        press('x')
        assertThat(text()).isEqualTo("\u00b4x")
    }

    @Test
    fun spaceAfterADeadKeyTypesTheAccentAndBackspaceCancelsIt() {
        start()
        combiner()
        dead('\u00b4')
        space()
        assertThat(text()).isEqualTo("\u00b4")
        dead('\u00b4')
        backspace()
        press('e')
        assertThat(text()).isEqualTo("\u00b4e")
    }

    @Test
    fun aShortcutClearsAPendingDeadKey() {
        start()
        combiner()
        dead('\u00b4')
        controller.onPhysicalKey(PhysicalKey.Shortcut)
        press('e')
        assertThat(text()).isEqualTo("e")
    }

    @Test
    fun aNewFieldClearsAPendingDeadKey() {
        start()
        combiner()
        dead('\u00b4')
        start()
        press('e')
        assertThat(text()).isEqualTo("e")
    }

    @Test
    fun touchesKeepTheScreenProfileAndPhysicalKeysTheHardwareOne() {
        start()
        assertThat(controller.currentTouchKey).startsWith("Tablet/")
        press('a')
        assertThat(controller.currentTouchKey).startsWith("Tablet/")
    }

    @Test
    fun enterInTheEmojiSearchGoesToTheSearchNotTheApp() {
        start()
        controller.showPanel(KeyboardPanel.Emoji)
        controller.openEmojiSearch()
        val enter = physicalKeyFor(KeyEvent.KEYCODE_ENTER, '\n'.code, false, false, false)
        assertThat(controller.onPhysicalKey(enter)).isTrue()
    }

    @Test
    fun backClosesAPanelAndOtherwiseBelongsToTheAppOnTheSlimBar() {
        start()
        assertThat(controller.backGoesToApp).isTrue()
        assertThat(controller.onHardwareBack()).isFalse()
        controller.onTool(StripTool.Emoji) {}
        assertThat(controller.backGoesToApp).isFalse()
        assertThat(controller.onHardwareBack()).isTrue()
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Keys)
    }

    @Test
    fun theFullKeyboardResetsOnANewFieldAndWhenTheKeyboardAttaches() {
        start()
        controller.toggleFullKeyboard()
        assertThat(controller.fullKeyboardShown).isTrue()
        start()
        assertThat(controller.fullKeyboardShown).isFalse()
        controller.toggleFullKeyboard()
        controller.updateDevice(tablet = true, landscape = true, hardwareKeyboard = false)
        controller.updateDevice(tablet = true, landscape = true, hardwareKeyboard = true)
        assertThat(controller.fullKeyboardShown).isFalse()
        controller.fullKeyboardByDefault = true
        assertThat(controller.fullKeyboardShown).isTrue()
        start()
        assertThat(controller.fullKeyboardShown).isTrue()
    }
}
