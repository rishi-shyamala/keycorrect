package dev.keycorrect.feature.keyboard

import android.text.InputType
import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.input.EditAction
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.settings.KeyboardSettings
import org.junit.Test

/**
 * The cursor drag, the backspace swipe, the editing panel's actions and the selection strip's, run
 * through [KeyboardController] against [SimEditor] (which reports every state it passes through).
 */
class EditingControllerTest {
    private val editor = SimEditor()
    private val backend = FakeBackend()
    private val predictor = ScriptedPredictor()
    private val controller: KeyboardController

    private val shift = KeyEvent.META_SHIFT_ON
    private val ctrl = KeyEvent.META_CTRL_ON

    init {
        backend.predictor = predictor
        controller =
            KeyboardController(
                editor,
                KeyboardComposer(BuiltInLayouts.repository()),
                testEnv(backend),
            )
        editor.onOperation = { editor.echo(controller) }
    }

    private fun start(
        text: String = "",
        at: Int = text.length,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        fieldId: Int = 1,
        restarting: Boolean = false,
    ) {
        editor.setText(text, at)
        controller.onStartInput(
            FieldProfile.from(inputType, 0),
            KeyboardSettings(),
            restarting,
            EditorIdentity(fieldId, "pkg", inputType, 0, null),
            editor.cursor,
            editor.cursor,
        )
    }

    private fun password() =
        start(
            "secret",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )

    private val gestures
        get() = controller.gestures

    private fun perform(action: EditingAction) = controller.editing.perform(action)

    // ------------------------------------------------------------------ cursor drag

    @Test
    fun dragSendsArrowKeysInOneBatch() {
        start("hello world", at = 5)
        gestures.onCursorDrag(-3, 0, select = false)
        assertThat(editor.keys).containsExactly(*Array(3) { KeyEvent.KEYCODE_DPAD_LEFT to 0 })
        assertThat(editor.batches).isEqualTo(1)
        assertThat(editor.cursor).isEqualTo(2)
    }

    @Test
    fun dragRightAndDownKeysWithSelectAddShift() {
        start("ab\ncd\nef", at = 0)
        gestures.onCursorDrag(2, 1, select = true)
        assertThat(editor.keys)
            .containsExactly(
                KeyEvent.KEYCODE_DPAD_RIGHT to shift,
                KeyEvent.KEYCODE_DPAD_RIGHT to shift,
                KeyEvent.KEYCODE_DPAD_DOWN to shift,
            )
            .inOrder()
        assertThat(editor.selStart).isEqualTo(0)
        assertThat(editor.selEnd).isGreaterThan(2)
    }

    @Test
    fun dragUpSendsUpKeys() {
        start("ab\ncd\nef", at = 7)
        gestures.onCursorDrag(0, -2, select = false)
        assertThat(editor.keys)
            .containsExactly(
                KeyEvent.KEYCODE_DPAD_UP to 0,
                KeyEvent.KEYCODE_DPAD_UP to 0,
            )
    }

    @Test
    fun emptyDragDoesNothing() {
        start("abc")
        gestures.onCursorDrag(0, 0, select = false)
        assertThat(editor.keys).isEmpty()
        assertThat(editor.batches).isEqualTo(0)
    }

    @Test
    fun movesEndComposingBeforeAnyKey() {
        start()
        "hel".forEach { controller.onKey(KeyAction.Text("$it")) }
        assertThat(editor.composing).isEqualTo("hel")
        // From here on, note the key count at each report the editor makes.
        val seen = mutableListOf<Pair<Int, String>>()
        editor.onOperation = { seen += editor.keys.size to editor.composing }
        gestures.onCursorDrag(-1, 0, select = false)
        // The first report is the composing word being finished, before the first key.
        assertThat(seen.first()).isEqualTo(0 to "")
        assertThat(editor.keys).hasSize(1)
    }

    @Test
    fun panelActionsEndComposingFirst() {
        start()
        "hel".forEach { controller.onKey(KeyAction.Text("$it")) }
        val seen = mutableListOf<Int>()
        editor.onOperation = { seen += editor.keys.size }
        perform(EditingAction.Left)
        assertThat(seen.first()).isEqualTo(0)
        assertThat(editor.composing).isEmpty()
    }

    // ------------------------------------------------------------------ backspace swipe

    @Test
    fun swipePreviewsWordsWithTheSelection() {
        start("foo bar baz ")
        gestures.onBackspaceSwipe(1)
        assertThat(editor.selections.last()).isEqualTo(8 to 12)
        assertThat(editor.selected).isEqualTo("baz ")
        gestures.onBackspaceSwipe(2)
        assertThat(editor.selections.last()).isEqualTo(4 to 12)
        assertThat(editor.selected).isEqualTo("bar baz ")
        gestures.onBackspaceSwipe(1)
        assertThat(editor.selections.last()).isEqualTo(8 to 12)
        gestures.onBackspaceSwipe(0)
        assertThat(editor.selections.last()).isEqualTo(12 to 12)
        assertThat(editor.cursor).isEqualTo(12)
        assertThat(editor.anchor).isEqualTo(-1)
    }

    @Test
    fun swipeCommitDeletesTheSelectedWords() {
        start("foo bar baz ")
        gestures.onBackspaceSwipe(2)
        gestures.onBackspaceSwipeEnd(commit = true)
        assertThat(editor.text.toString()).isEqualTo("foo ")
        assertThat(editor.cursor).isEqualTo(4)
    }

    @Test
    fun swipeKeepsTrailingSpacesWithTheirWord() {
        start("foo bar ")
        gestures.onBackspaceSwipe(1)
        gestures.onBackspaceSwipeEnd(commit = true)
        assertThat(editor.text.toString()).isEqualTo("foo ")
    }

    @Test
    fun swipeWithManySpacesAndAWordWithNoSpace() {
        start("foo   bar")
        gestures.onBackspaceSwipe(1)
        gestures.onBackspaceSwipeEnd(commit = true)
        assertThat(editor.text.toString()).isEqualTo("foo   ")
        gestures.onBackspaceSwipe(1)
        gestures.onBackspaceSwipeEnd(commit = true)
        assertThat(editor.text.toString()).isEmpty()
    }

    @Test
    fun swipeCancelPutsTheCursorBack() {
        start("foo bar baz ", at = 12)
        gestures.onBackspaceSwipe(2)
        gestures.onBackspaceSwipeEnd(commit = false)
        assertThat(editor.text.toString()).isEqualTo("foo bar baz ")
        assertThat(editor.cursor).isEqualTo(12)
        assertThat(editor.anchor).isEqualTo(-1)
        assertThat(editor.deletes).isEmpty()
    }

    @Test
    fun swipeBackToZeroAndReleaseDeletesNothing() {
        start("foo bar ")
        gestures.onBackspaceSwipe(1)
        gestures.onBackspaceSwipe(0)
        gestures.onBackspaceSwipeEnd(commit = true)
        assertThat(editor.text.toString()).isEqualTo("foo bar ")
        assertThat(editor.deletes).isEmpty()
    }

    @Test
    fun swipePastTheStartSelectsEverything() {
        start("foo bar")
        gestures.onBackspaceSwipe(9)
        assertThat(editor.selections.last()).isEqualTo(0 to 7)
    }

    @Test
    fun swipeReadsABoundedWindow() {
        start("x".repeat(1000))
        gestures.onBackspaceSwipe(1)
        assertThat(editor.selections.last()).isEqualTo(744 to 1000)
    }

    @Test
    fun swipeReadsTheTextOnceAndHidesTheStripWhilePreviewing() {
        start("foo bar baz ")
        gestures.onBackspaceSwipe(1)
        val reads = editor.readCalls
        gestures.onBackspaceSwipe(2)
        gestures.onBackspaceSwipe(3)
        assertThat(editor.readCalls).isEqualTo(reads)
        assertThat(controller.selection.collapsed).isFalse()
        assertThat(controller.selectionStripVisible).isFalse()
        gestures.onBackspaceSwipeEnd(commit = false)
        assertThat(controller.selectionStripVisible).isFalse()
    }

    @Test
    fun swipeIsANoOpInASensitiveField() {
        password()
        gestures.onBackspaceSwipe(1)
        gestures.onBackspaceSwipe(2)
        gestures.onBackspaceSwipeEnd(commit = true)
        assertThat(editor.readCalls).isEqualTo(0)
        assertThat(editor.selections).isEmpty()
        assertThat(editor.deletes).isEmpty()
        assertThat(editor.text.toString()).isEqualTo("secret")
    }

    // ------------------------------------------------------------------ panel actions

    @Test
    fun panelMovesSendTheirKeys() {
        start("hello")
        val expected =
            mapOf(
                EditingAction.Left to (KeyEvent.KEYCODE_DPAD_LEFT to 0),
                EditingAction.Right to (KeyEvent.KEYCODE_DPAD_RIGHT to 0),
                EditingAction.Up to (KeyEvent.KEYCODE_DPAD_UP to 0),
                EditingAction.Down to (KeyEvent.KEYCODE_DPAD_DOWN to 0),
                EditingAction.WordLeft to (KeyEvent.KEYCODE_DPAD_LEFT to ctrl),
                EditingAction.WordRight to (KeyEvent.KEYCODE_DPAD_RIGHT to ctrl),
                EditingAction.Home to (KeyEvent.KEYCODE_MOVE_HOME to 0),
                EditingAction.End to (KeyEvent.KEYCODE_MOVE_END to 0),
                EditingAction.Undo to (KeyEvent.KEYCODE_Z to ctrl),
                EditingAction.Redo to (KeyEvent.KEYCODE_Z to (ctrl or shift)),
                EditingAction.Backspace to (KeyEvent.KEYCODE_DEL to 0),
                EditingAction.Delete to (KeyEvent.KEYCODE_FORWARD_DEL to 0),
            )
        for ((action, key) in expected) {
            editor.keys.clear()
            perform(action)
            assertThat(editor.keys).containsExactly(key)
        }
    }

    @Test
    fun panelMenuActions() {
        start("hello")
        for (action in
            listOf(
                EditingAction.SelectAll to EditAction.SelectAll,
                EditingAction.Cut to EditAction.Cut,
                EditingAction.Copy to EditAction.Copy,
                EditingAction.Paste to EditAction.Paste,
            )) {
            editor.menuActions.clear()
            perform(action.first)
            assertThat(editor.menuActions).containsExactly(action.second)
        }
        assertThat(editor.keys).isEmpty()
    }

    @Test
    fun cutCopyPasteMoveTheText() {
        start("hello world")
        perform(EditingAction.SelectAll)
        assertThat(editor.selected).isEqualTo("hello world")
        perform(EditingAction.Cut)
        assertThat(editor.text.toString()).isEmpty()
        assertThat(editor.clipboard).isEqualTo("hello world")
        perform(EditingAction.Paste)
        assertThat(editor.text.toString()).isEqualTo("hello world")
    }

    @Test
    fun selectModeAddsShiftToMoves() {
        start("hello")
        assertThat(controller.editing.selectMode).isFalse()
        perform(EditingAction.SelectToggle)
        assertThat(controller.editing.selectMode).isTrue()
        perform(EditingAction.Left)
        perform(EditingAction.WordLeft)
        perform(EditingAction.End)
        assertThat(editor.keys)
            .containsExactly(
                KeyEvent.KEYCODE_DPAD_LEFT to shift,
                KeyEvent.KEYCODE_DPAD_LEFT to (ctrl or shift),
                KeyEvent.KEYCODE_MOVE_END to shift,
            )
            .inOrder()
        perform(EditingAction.SelectToggle)
        editor.keys.clear()
        perform(EditingAction.Right)
        assertThat(editor.keys).containsExactly(KeyEvent.KEYCODE_DPAD_RIGHT to 0)
    }

    @Test
    fun closeReturnsToTheKeysAndClearsSelectMode() {
        start("hello")
        controller.toggleEditingPanel()
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Editing)
        perform(EditingAction.SelectToggle)
        perform(EditingAction.Close)
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Keys)
        assertThat(controller.editing.selectMode).isFalse()
        assertThat(editor.keys).isEmpty()
    }

    @Test
    fun theEditToolTogglesThePanel() {
        start("hello")
        assertThat(controller.toolsVisible).isTrue()
        controller.toggleEditingPanel()
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Editing)
        assertThat(controller.toolsVisible).isTrue()
        controller.toggleEditingPanel()
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Keys)
        controller.showPanel(KeyboardPanel.Emoji)
        assertThat(controller.toolsVisible).isFalse()
    }

    @Test
    fun theEditToolOpensThePanelWithSelectModeOff() {
        start("hello")
        perform(EditingAction.SelectToggle)
        controller.toggleEditingPanel()
        assertThat(controller.editing.selectMode).isFalse()
    }

    @Test
    fun panelClosesInANewFieldButNotWhenTheSameFieldRestarts() {
        start("one", fieldId = 1)
        controller.toggleEditingPanel()
        perform(EditingAction.SelectToggle)
        start("one", fieldId = 1, restarting = true)
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Editing)
        start("two", fieldId = 2)
        assertThat(controller.panel).isEqualTo(KeyboardPanel.Keys)
        assertThat(controller.editing.selectMode).isFalse()
    }

    @Test
    fun keyActionsAndMenuStillWorkInASensitiveField() {
        password()
        perform(EditingAction.Left)
        perform(EditingAction.Paste)
        assertThat(editor.keys).containsExactly(KeyEvent.KEYCODE_DPAD_LEFT to 0)
        assertThat(editor.menuActions).containsExactly(EditAction.Paste)
        assertThat(editor.readCalls).isEqualTo(0)
    }

    // ------------------------------------------------------------------ select word / sentence /
    // line

    private val prose = "Hello there. How are you? Fine!\nSecond line here"

    @Test
    fun selectWordSelectsTheWordAroundTheCursor() {
        start(prose, at = 9)
        perform(EditingAction.SelectWord)
        assertThat(editor.selections.last()).isEqualTo(6 to 11)
        assertThat(editor.selected).isEqualTo("there")
    }

    @Test
    fun selectWordAtTheEdgesOfAWord() {
        start(prose, at = 6)
        perform(EditingAction.SelectWord)
        assertThat(editor.selected).isEqualTo("there")
        start(prose, at = 11, fieldId = 2)
        perform(EditingAction.SelectWord)
        assertThat(editor.selected).isEqualTo("there")
    }

    @Test
    fun selectSentenceSelectsTheSentenceAroundTheCursor() {
        start(prose, at = 16)
        perform(EditingAction.SelectSentence)
        assertThat(editor.selected).isEqualTo("How are you?")
    }

    @Test
    fun selectSentenceStopsAtLineBreaks() {
        start(prose, at = 28)
        perform(EditingAction.SelectSentence)
        assertThat(editor.selected).isEqualTo("Fine!")
        start(prose, at = 40, fieldId = 2)
        perform(EditingAction.SelectSentence)
        assertThat(editor.selected).isEqualTo("Second line here")
    }

    @Test
    fun selectLineSelectsTheLineAroundTheCursor() {
        start(prose, at = 20)
        perform(EditingAction.SelectLine)
        assertThat(editor.selected).isEqualTo("Hello there. How are you? Fine!")
        start(prose, at = 36, fieldId = 2)
        perform(EditingAction.SelectLine)
        assertThat(editor.selected).isEqualTo("Second line here")
    }

    @Test
    fun selectAroundDoesNothingWhereThereIsNothingToSelect() {
        start("a   b", at = 3)
        perform(EditingAction.SelectWord)
        assertThat(editor.selections).isEmpty()
    }

    @Test
    fun textBasedSelectionIsUnavailableInASensitiveField() {
        password()
        val unavailable =
            listOf(EditingAction.SelectWord, EditingAction.SelectSentence, EditingAction.SelectLine)
        for (action in unavailable) {
            assertThat(controller.editing.isAvailable(action)).isFalse()
            perform(action)
        }
        assertThat(controller.editing.isAvailable(EditingAction.SelectAll)).isTrue()
        assertThat(controller.editing.isAvailable(EditingAction.Left)).isTrue()
        assertThat(editor.selections).isEmpty()
        assertThat(editor.readCalls).isEqualTo(0)
        start("text", fieldId = 2)
        assertThat(controller.editing.isAvailable(EditingAction.SelectWord)).isTrue()
    }

    @Test
    fun cutAndCopyAreUnavailableWithoutASelection() {
        start("hello", at = 2)
        assertThat(controller.editing.isAvailable(EditingAction.Copy)).isFalse()
        assertThat(controller.editing.isAvailable(EditingAction.Cut)).isFalse()
        editor.setSelection(0, 3)
        assertThat(controller.editing.isAvailable(EditingAction.Copy)).isTrue()
        assertThat(controller.editing.isAvailable(EditingAction.Paste)).isTrue()
    }

    // ------------------------------------------------------------------ selection strip

    @Test
    fun stripAppearsForARangeAndNotForACursor() {
        start("hello world")
        assertThat(controller.selectionStripVisible).isFalse()
        editor.setSelection(0, 5)
        assertThat(controller.selectionStripVisible).isTrue()
        editor.setSelection(3, 3)
        assertThat(controller.selectionStripVisible).isFalse()
    }

    @Test
    fun stripShowsForARangeThatWasSelectedWhenInputStarted() {
        editor.setText("hello world", 5)
        editor.setSelection(0, 5)
        controller.onStartInput(
            FieldProfile.from(InputType.TYPE_CLASS_TEXT, 0),
            KeyboardSettings(),
            false,
            EditorIdentity(1, "pkg", InputType.TYPE_CLASS_TEXT, 0, null),
            0,
            5,
        )
        assertThat(controller.selectionStripVisible).isTrue()
    }

    @Test
    fun stripNeverAppearsInASensitiveField() {
        password()
        editor.setSelection(0, 3)
        assertThat(controller.selection.collapsed).isFalse()
        assertThat(controller.selectionStripVisible).isFalse()
        assertThat(controller.editing.expandSelectionToWord()).isFalse()
        assertThat(controller.editing.cycleCase()).isFalse()
        assertThat(editor.readCalls).isEqualTo(0)
    }

    @Test
    fun stripGoesWhenInputFinishes() {
        start("hello")
        editor.setSelection(0, 3)
        controller.onFinishInput()
        assertThat(controller.selectionStripVisible).isFalse()
    }

    @Test
    fun wordExpandsTheSelectionToWholeWords() {
        start("one two three four")
        editor.setSelection(5, 10) // "wo th"
        assertThat(controller.editing.expandSelectionToWord()).isTrue()
        assertThat(editor.selected).isEqualTo("two three")
        assertThat(controller.selectionStripVisible).isTrue()
        // Already whole words: nothing to do.
        assertThat(controller.editing.expandSelectionToWord()).isFalse()
    }

    @Test
    fun sentenceExpandsTheSelectionToWholeSentences() {
        start("Hi there. Good morning to you. Bye now.")
        editor.setSelection(14, 18) // "d mo"
        assertThat(controller.editing.expandSelectionToSentence()).isTrue()
        assertThat(editor.selected).isEqualTo("Good morning to you.")
        editor.setSelection(5, 16) // spans two sentences
        assertThat(controller.editing.expandSelectionToSentence()).isTrue()
        assertThat(editor.selected).isEqualTo("Hi there. Good morning to you.")
    }

    @Test
    fun copyUsesTheEditorsMenu() {
        start("hello world")
        editor.setSelection(0, 5)
        assertThat(controller.editing.copySelection()).isTrue()
        assertThat(editor.menuActions).containsExactly(EditAction.Copy)
        assertThat(editor.clipboard).isEqualTo("hello")
    }

    @Test
    fun caseCycleKeepsTheSelection() {
        start("say hello world now")
        editor.setSelection(4, 15)
        for (expected in listOf("Hello World", "HELLO WORLD", "hello world", "Hello World")) {
            assertThat(controller.editing.cycleCase()).isTrue()
            assertThat(editor.selected).isEqualTo(expected)
            assertThat(editor.selStart).isEqualTo(4)
            assertThat(editor.selEnd).isEqualTo(15)
            assertThat(controller.selection.selStart).isEqualTo(4)
            assertThat(controller.selection.selEnd).isEqualTo(15)
        }
        assertThat(editor.text.toString()).isEqualTo("say Hello World now")
    }

    @Test
    fun caseCycleSelectsTheNewLengthWhenItChanges() {
        start("straße")
        editor.setSelection(0, 6)
        assertThat(controller.editing.cycleCase()).isTrue() // Strasse
        assertThat(controller.editing.cycleCase()).isTrue() // STRASSE, one char longer
        assertThat(editor.text.toString()).isEqualTo("STRASSE")
        assertThat(editor.selected).isEqualTo("STRASSE")
        assertThat(editor.selEnd).isEqualTo(7)
    }

    @Test
    fun caseCycleDoesNothingToTextWithoutLetters() {
        start("123")
        editor.setSelection(0, 3)
        assertThat(controller.editing.cycleCase()).isFalse()
        assertThat(editor.text.toString()).isEqualTo("123")
    }

    @Test
    fun engineHookIsOffUnlessSwitchedOn() {
        start("hello world")
        editor.setSelection(0, 5)
        val sent = mutableListOf<String>()
        controller.editing.engineHook = { sent += it }
        assertThat(controller.editing.sendSelectionToEngine()).isFalse()
        controller.editing.engineHookEnabled = true
        assertThat(controller.editing.sendSelectionToEngine()).isTrue()
        assertThat(sent).containsExactly("hello")
    }

    @Test
    fun backspaceSwipeStartsFromTheEditorsCursorEvenWhenTheLastReportIsBehind() {
        // On a device the delete-on-press is reported after the swipe may already have begun.
        start("foo bar baz")
        editor.onOperation = null // the platform hasn't reported the delete yet
        controller.onKey(KeyAction.Backspace)
        assertThat(editor.text.toString()).isEqualTo("foo bar ba")
        controller.gestures.onBackspaceSwipe(1)
        assertThat(editor.selected).isEqualTo("ba")
        assertThat(editor.selections.last()).isEqualTo(8 to 10)
    }

    @Test
    fun aMoveThatLeavesTheCursorInPlaceStillEndsTheComposingWord() {
        // The finish-composing update is the keyboard's own, so no external change follows it.
        start("")
        "hel".forEach { controller.onKey(KeyAction.Text("$it")) }
        assertThat(editor.composing).isEqualTo("hel")
        perform(EditingAction.Right) // already at the end: the cursor doesn't move
        controller.onKey(KeyAction.Text("l"))
        assertThat(editor.text.toString()).isEqualTo("hell")
    }

    @Test
    fun cutAndCopyAreUnavailableInAPasswordField() {
        password()
        editor.setSelection(0, 6)
        assertThat(controller.editing.isAvailable(EditingAction.Cut)).isFalse()
        assertThat(controller.editing.isAvailable(EditingAction.Copy)).isFalse()
        assertThat(controller.editing.isAvailable(EditingAction.Paste)).isTrue()
    }

    @Test
    fun aKeyFromAnotherFingerCancelsTheBackspaceSwipe() {
        start("foo bar baz")
        gestures.onBackspaceSwipe(2)
        assertThat(editor.selected).isEqualTo("bar baz")
        controller.onKey(KeyAction.Text("x")) // a second finger
        assertThat(editor.text.toString()).isEqualTo("foo bar bazx")
        gestures.onBackspaceSwipe(3) // the swipe finger keeps moving: no new swipe
        gestures.onBackspaceSwipeEnd(commit = true)
        assertThat(editor.text.toString()).isEqualTo("foo bar bazx")
    }
}

class TextUnitsTest {
    private fun word(text: String, start: Int, end: Int = start) =
        TextUnits.expandWord(text, start, end)?.let { text.substring(it.start, it.end) }

    private fun sentence(text: String, start: Int, end: Int = start) =
        TextUnits.expandSentence(text, start, end)?.let { text.substring(it.start, it.end) }

    private fun line(text: String, start: Int, end: Int = start) =
        TextUnits.expandLine(text, start, end)?.let { text.substring(it.start, it.end) }

    @Test
    fun wordsBackCountTrailingSpacesWithTheirWord() {
        assertThat(TextUnits.wordsBackLength("foo bar ", 1)).isEqualTo(4)
        assertThat(TextUnits.wordsBackLength("foo bar ", 2)).isEqualTo(8)
        assertThat(TextUnits.wordsBackLength("foo bar", 1)).isEqualTo(3)
        assertThat(TextUnits.wordsBackLength("foo bar", 0)).isEqualTo(0)
        assertThat(TextUnits.wordsBackLength("foo bar", 5)).isEqualTo(7)
        assertThat(TextUnits.wordsBackLength("   ", 1)).isEqualTo(3)
        assertThat(TextUnits.wordsBackLength("a\nb ", 2)).isEqualTo(4)
    }

    @Test
    fun wordsBackDoNotSplitASurrogatePairAtTheWindowEdge() {
        val text = "\uDE00abc" // a window that starts on a low surrogate
        assertThat(TextUnits.wordsBackLength(text, 1)).isEqualTo(3)
    }

    @Test
    fun wordBoundaries() {
        assertThat(word("don't stop", 2)).isEqualTo("don't")
        assertThat(word("don't stop", 10)).isEqualTo("stop")
        assertThat(word("don't stop", 6)).isEqualTo("stop")
        assertThat(word("a   b", 3)).isNull()
        assertThat(word("", 0)).isNull()
        assertThat(word("one two three", 5, 10)).isEqualTo("two three")
        assertThat(word("naïve café", 8)).isEqualTo("café")
        assertThat(word("x_1 y", 1)).isEqualTo("x_1")
    }

    @Test
    fun wordAtAWordEndTakesTheWordBefore() {
        assertThat(word("foo bar", 3)).isEqualTo("foo")
        assertThat(word("foo bar", 4)).isEqualTo("bar")
        assertThat(word("foo bar ", 8)).isEqualTo("bar")
        assertThat(word("foo. ", 5)).isNull() // punctuation in between: not a word end
        assertThat(word("foo\n", 4)).isNull() // only a space counts
        assertThat(word(" ", 1)).isNull()
        assertThat(word("a  b", 2)).isEqualTo("a")
        assertThat(word("foo bar ", 8, 8)).isEqualTo("bar")
    }

    @Test
    fun sentenceBoundaries() {
        val text = "Hi. There now. Ok"
        assertThat(sentence(text, 6)).isEqualTo("There now.")
        assertThat(sentence(text, 4)).isEqualTo("There now.")
        assertThat(sentence(text, 3)).isEqualTo("Hi.")
        assertThat(sentence(text, 16)).isEqualTo("Ok")
        assertThat(sentence("Hi there", 3)).isEqualTo("Hi there")
        assertThat(sentence("Wait... what?! Really", 9)).isEqualTo("what?!")
        assertThat(sentence("He said \"hi.\" Then left.", 18)).isEqualTo("Then left.")
        assertThat(sentence("3.14 is pi. Yes", 2)).isEqualTo("3.14 is pi.")
        assertThat(sentence("one\ntwo three. four", 6)).isEqualTo("two three.")
        assertThat(sentence("Hello world.   ", 13)).isEqualTo("Hello world.")
        assertThat(sentence("", 0)).isNull()
    }

    @Test
    fun sentenceExpandsAcrossSentencesOfARange() {
        assertThat(sentence("Hi. There now. Ok", 1, 8)).isEqualTo("Hi. There now.")
    }

    @Test
    fun cjkSentencesNeedNoSpace() {
        assertThat(sentence("你好。再见", 4)).isEqualTo("再见")
        assertThat(sentence("你好。再见", 1)).isEqualTo("你好。")
    }

    @Test
    fun lineBoundaries() {
        val text = "ab\ncd\nef"
        assertThat(line(text, 4)).isEqualTo("cd")
        assertThat(line(text, 3)).isEqualTo("cd")
        assertThat(line(text, 5)).isEqualTo("cd")
        assertThat(line(text, 0)).isEqualTo("ab")
        assertThat(line(text, 8)).isEqualTo("ef")
        assertThat(line("ab\n\ncd", 3)).isNull()
        assertThat(line(text, 1, 7)).isEqualTo(text)
    }

    @Test
    fun caseCycleOrder() {
        assertThat(CaseCycle.next("hello world")).isEqualTo("Hello World")
        assertThat(CaseCycle.next("Hello World")).isEqualTo("HELLO WORLD")
        assertThat(CaseCycle.next("HELLO WORLD")).isEqualTo("hello world")
    }

    @Test
    fun caseCycleOfMixedAndTinyText() {
        assertThat(CaseCycle.next("hELLO")).isEqualTo("hello")
        assertThat(CaseCycle.next("Hello world")).isEqualTo("hello world")
        assertThat(CaseCycle.next("a")).isEqualTo("A")
        assertThat(CaseCycle.next("A")).isEqualTo("a")
        assertThat(CaseCycle.next("123")).isEqualTo("123")
        assertThat(CaseCycle.next("don't STOP")).isEqualTo("don't stop")
        assertThat(CaseCycle.next("don't stop")).isEqualTo("Don't Stop")
        assertThat(CaseCycle.next("3rd place")).isEqualTo("3rd Place")
    }

    @Test
    fun aCrlfLineIsSelectedWithoutItsCarriageReturn() {
        val text = "one\r\ntwo\r\n"
        assertThat(TextUnits.expandLine(text, 1, 1)).isEqualTo(TextSpan(0, 3))
        assertThat(TextUnits.expandLine(text, 6, 6)).isEqualTo(TextSpan(5, 8))
    }
}
