package dev.keycorrect.feature.keyboard

import android.text.InputType
import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.core.settings.SkinTone
import dev.keycorrect.feature.keyboard.emoji.FakeEmojiEnvironment
import dev.keycorrect.feature.keyboard.emoji.TestEmoji
import dev.keycorrect.feature.keyboard.prediction.StripModel
import org.junit.Test

/** Emoji picking and the emoji search, through [KeyboardController] against [SimEditor]. */
class EmojiControllerTest {
    private val editor = SimEditor()
    private val backend = FakeBackend()
    private val predictor = ScriptedPredictor()
    private val emoji = FakeEmojiEnvironment()
    private val controller: KeyboardController

    init {
        backend.predictor = predictor
        controller =
            KeyboardController(
                editor,
                KeyboardComposer(BuiltInLayouts.repository()),
                testEnv(backend),
                emoji = emoji,
            )
        editor.onOperation = { editor.echo(controller) }
    }

    private fun start(
        text: String = "",
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        settings: KeyboardSettings = KeyboardSettings(),
    ) {
        editor.setText(text)
        controller.onStartInput(
            FieldProfile.from(inputType, 0),
            settings,
            identity = EditorIdentity(1, "pkg", inputType, 0, null),
            initialSelStart = editor.cursor,
            initialSelEnd = editor.cursor,
        )
    }

    private fun type(text: String) = text.forEach { controller.onKey(KeyAction.Text("$it")) }

    private fun openSearch() {
        controller.showPanel(KeyboardPanel.Emoji)
        controller.openEmojiSearch()
    }

    private val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

    // ------------------------------------------------------------------ onEmojiPicked

    @Test
    fun aPickedEmojiIsCommittedAndReportedUsed() {
        start("hi ")
        controller.onEmojiPicked(TestEmoji.PIZZA_VALUE, TestEmoji.PIZZA_VALUE)
        assertThat(editor.text.toString()).isEqualTo("hi ${TestEmoji.PIZZA_VALUE}")
        assertThat(editor.cursor).isEqualTo(editor.text.length)
        assertThat(emoji.used).containsExactly(TestEmoji.PIZZA_VALUE to TestEmoji.PIZZA_VALUE)
    }

    @Test
    fun pickingEndsTheComposingWordWithoutCorrectingOrLearningIt() {
        backend.known += "hello"
        predictor.word("helo", listOf("hello"), autocorrect = "hello")
        start()
        type("helo")
        assertThat(editor.composing).isEqualTo("helo")
        controller.onEmojiPicked(TestEmoji.PIZZA_VALUE)
        assertThat(editor.composing).isEmpty()
        assertThat(editor.text.toString()).isEqualTo("helo${TestEmoji.PIZZA_VALUE}")
        assertThat(controller.strip).isEqualTo(StripModel.Empty)
        // Typing goes on as a new word; the tracker saw the emoji as the keyboard's own edit.
        type("a")
        assertThat(editor.text.toString()).isEqualTo("helo${TestEmoji.PIZZA_VALUE}a")
        assertThat(editor.composing).isEqualTo("a")
    }

    @Test
    fun anEmojiPickDoesNotLookLikeAnExternalMove() {
        start("ab")
        controller.onEmojiPicked(TestEmoji.PIZZA_VALUE)
        val reads = editor.readCalls
        // The echoed selection update was recognised as ours: nothing is re-read for it.
        controller.onSelectionChanged(editor.cursor, editor.cursor)
        assertThat(editor.readCalls).isEqualTo(reads)
    }

    @Test
    fun aKaomojiIsCommittedButNotRecorded() {
        start()
        controller.onEmojiPicked("(^_^)", "(^_^)", record = false)
        assertThat(editor.text.toString()).isEqualTo("(^_^)")
        assertThat(emoji.used).isEmpty()
    }

    @Test
    fun noEmojiIsInsertedInAPasswordField() {
        start("secret", inputType = password)
        controller.onEmojiPicked(TestEmoji.PIZZA_VALUE)
        controller.onEmojiTap(TestEmoji.thumbs)
        controller.onEmojiVariantPicked(TestEmoji.thumbs, TestEmoji.THUMBS_DARK)
        assertThat(editor.text.toString()).isEqualTo("secret")
        assertThat(emoji.used).isEmpty()
        assertThat(emoji.preferred).isEmpty()
    }

    @Test
    fun thePanelAndSearchCannotOpenInAPasswordField() {
        start(inputType = password)
        controller.showPanel(KeyboardPanel.Emoji)
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Keys)
        controller.onKey(KeyAction.Emoji)
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Keys)
        controller.openEmojiSearch()
        assertThat(controller.emojiSearch.active).isFalse()
    }

    // ------------------------------------------------------------------ variant choice

    @Test
    fun aTapInsertsThePreferredVariantFirst() {
        start(settings = KeyboardSettings(emojiSkinTone = SkinTone.Medium))
        emoji.preferred[TestEmoji.THUMBS_VALUE] = TestEmoji.THUMBS_DARK
        controller.onEmojiTap(TestEmoji.thumbs)
        assertThat(editor.text.toString()).isEqualTo(TestEmoji.THUMBS_DARK)
        assertThat(emoji.used).containsExactly(TestEmoji.THUMBS_DARK to TestEmoji.THUMBS_VALUE)
    }

    @Test
    fun withoutAChoiceATapUsesTheDefaultTone() {
        start(settings = KeyboardSettings(emojiSkinTone = SkinTone.Medium))
        controller.onEmojiTap(TestEmoji.thumbs)
        assertThat(editor.text.toString()).isEqualTo(TestEmoji.THUMBS_MEDIUM)
    }

    @Test
    fun withNeitherATapUsesTheBase() {
        start()
        controller.onEmojiTap(TestEmoji.thumbs)
        controller.onEmojiTap(TestEmoji.pizza)
        assertThat(editor.text.toString()).isEqualTo(TestEmoji.THUMBS_VALUE + TestEmoji.PIZZA_VALUE)
    }

    @Test
    fun pickingAVariantInsertsAndRemembersIt() {
        start(settings = KeyboardSettings(emojiSkinTone = SkinTone.Medium))
        controller.onEmojiVariantPicked(TestEmoji.thumbs, TestEmoji.THUMBS_LIGHT)
        assertThat(editor.text.toString()).isEqualTo(TestEmoji.THUMBS_LIGHT)
        assertThat(emoji.preferred).containsExactly(TestEmoji.THUMBS_VALUE, TestEmoji.THUMBS_LIGHT)
        controller.onEmojiTap(TestEmoji.thumbs)
        assertThat(editor.text.toString())
            .isEqualTo(TestEmoji.THUMBS_LIGHT + TestEmoji.THUMBS_LIGHT)
    }

    // ------------------------------------------------------------------ search

    @Test
    fun lettersSpaceAndBackspaceEditTheQueryNotTheApp() {
        start("note ")
        val keysBefore = editor.keys.size
        openSearch()
        assertThat(controller.emojiSearch.active).isTrue()
        type("pizz")
        controller.onKey(KeyAction.Space)
        controller.onKey(KeyAction.Text("a"))
        controller.onKey(KeyAction.Backspace)
        controller.onKey(KeyAction.Backspace)
        assertThat(controller.emojiSearch.query).isEqualTo("pizz")
        assertThat(editor.text.toString()).isEqualTo("note ")
        assertThat(editor.composeCalls).isEqualTo(0)
        assertThat(editor.deletes).isEmpty()
        assertThat(editor.keys).hasSize(keysBefore)
        assertThat(predictor.requests).isEmpty()
        assertThat(controller.strip).isEqualTo(StripModel.Empty)
    }

    @Test
    fun searchOpensOnTheLettersAndShiftedLettersAreQueryText() {
        start()
        controller.showPanel(KeyboardPanel.Emoji)
        controller.onKey(KeyAction.SwitchLayer("symbols"))
        controller.openEmojiSearch()
        assertThat(controller.state.layerId).isEqualTo("letters")
        controller.onKey(KeyAction.Text("p", "P"))
        assertThat(controller.emojiSearch.query).isEqualTo("p")
        assertThat(editor.text.toString()).isEmpty()
    }

    @Test
    fun enterLeavesTheSearchWithoutSendingEnter() {
        start("x")
        openSearch()
        type("pi")
        controller.onKey(KeyAction.Enter)
        assertThat(controller.emojiSearch.active).isFalse()
        assertThat(controller.emojiSearch.query).isEmpty()
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Emoji)
        assertThat(editor.enters).isEqualTo(0)
        // Keys type into the app again once the panel is closed.
        controller.showPanel(KeyboardPanel.Keys)
        type("a")
        assertThat(editor.text.toString()).isEqualTo("xa")
    }

    @Test
    fun closingTheSearchOrThePanelEndsIt() {
        start()
        openSearch()
        controller.closeEmojiSearch()
        assertThat(controller.emojiSearch.active).isFalse()
        openSearch()
        controller.showPanel(KeyboardPanel.Keys)
        assertThat(controller.emojiSearch.active).isFalse()
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Keys)
    }

    @Test
    fun aResultInsertsLikeAGridTapAndTheSearchStaysOpen() {
        start("a ")
        openSearch()
        type("thumbs")
        val result = controller.emojiSearch.results(emoji.catalog).single()
        controller.onEmojiTap(result)
        assertThat(editor.text.toString()).isEqualTo("a ${TestEmoji.THUMBS_VALUE}")
        assertThat(emoji.used).containsExactly(TestEmoji.THUMBS_VALUE to TestEmoji.THUMBS_VALUE)
        assertThat(controller.emojiSearch.query).isEqualTo("thumbs")
    }

    @Test
    fun spaceGesturesDoNothingToTheEditorWhileSearching() {
        start("hello")
        openSearch()
        controller.gestures.onCursorDrag(-2, 0, select = false)
        controller.gestures.onSpaceHold()
        controller.gestures.onBackspaceSwipe(1)
        controller.gestures.onBackspaceSwipeEnd(commit = true)
        assertThat(editor.keys.filter { it.first == KeyEvent.KEYCODE_DPAD_LEFT }).isEmpty()
        assertThat(editor.selections).isEmpty()
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Emoji)
        assertThat(editor.text.toString()).isEqualTo("hello")
    }

    @Test
    fun startingANewFieldClosesThePanelAndTheSearch() {
        start()
        openSearch()
        start(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        assertThat(controller.emojiSearch.active).isFalse()
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Keys)
    }
}
