package dev.keycorrect.feature.keyboard

import android.text.InputType
import androidx.compose.runtime.snapshots.Snapshot
import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.engineapi.FieldClass
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.layout.KeyboardGeometry
import dev.keycorrect.core.learning.CommitMethod
import dev.keycorrect.core.learning.WordCommitted
import dev.keycorrect.core.learning.WordReplaced
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.feature.keyboard.prediction.SlotKind
import org.junit.Test

/**
 * Gesture typing through [KeyboardController] (docs/PHASE5.md, PR B): the gating, the preview in
 * the strip, the commit with its auto-space and casing, backspace, the alternatives and learning.
 * The touch layer is replaced by calls to [KeyboardController.gestures].
 */
class SwipeTypingTest {
    private val editor = SimEditor()
    private val backend = FakeBackend()
    private val predictor = ScriptedPredictor()
    private val sink = RecordingSink()
    private val executor = ManualExecutor()
    private var inline = true
    private var now = 10_000L
    private val controller: KeyboardController

    init {
        backend.predictor = predictor
        backend.known += listOf("hello", "world", "hallo", "hells", "hi", "done", "there")
        val loopExecutor =
            java.util.concurrent.Executor { if (inline) it.run() else executor.execute(it) }
        controller =
            KeyboardController(
                editor,
                KeyboardComposer(BuiltInLayouts.repository()),
                testEnv(backend, executor = loopExecutor, gestureWaitMs = 1),
                clock = { now },
                learning = sink,
            )
        editor.onOperation = { editor.echo(controller) }
    }

    private val textField = FieldProfile.from(InputType.TYPE_CLASS_TEXT, 0)

    private fun start(
        profile: FieldProfile = textField,
        settings: KeyboardSettings = KeyboardSettings(),
        text: String = "",
    ) {
        editor.setText(text)
        controller.onStartInput(
            profile,
            settings,
            restarting = false,
            identity = EditorIdentity(1, "pkg", 1, 0, null),
            initialSelStart = editor.cursor,
            initialSelEnd = editor.cursor,
        )
        controller.onGeometry(KeyboardGeometry(controller.layer, GEO_W, GEO_H))
    }

    private fun text() = editor.text.toString()

    private fun type(text: String) = text.forEach { controller.onKey(KeyAction.Text("$it")) }

    private fun space() = controller.onKey(KeyAction.Space)

    private fun backspace() = controller.onKey(KeyAction.Backspace)

    private fun path(points: Int = 8): SwipePath =
        SwipePath().also { p -> repeat(points) { p.add(10f * it, 50f, now + 10L * it) } }

    /** A whole swipe that decodes to [words]. */
    private fun swipe(vararg words: String) {
        predictor.swipeWords = words.toList()
        val p = path()
        controller.gestures.onSwipeStart(p)
        controller.gestures.onSwipeEnd(p)
    }

    private fun committed() = sink.of<WordCommitted>()

    // ------------------------------------------------------------------ gating

    private fun active(inputType: Int, settings: KeyboardSettings = KeyboardSettings()): Boolean {
        start(FieldProfile.from(inputType, 0), settings)
        return controller.swipeTypingActive
    }

    @Test
    fun swipeIsOnInTextEmailUrlNameAndAddressFields() {
        val text = InputType.TYPE_CLASS_TEXT
        assertThat(active(text)).isTrue()
        assertThat(active(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)).isTrue()
        assertThat(active(text or InputType.TYPE_TEXT_VARIATION_URI)).isTrue()
        assertThat(active(text or InputType.TYPE_TEXT_VARIATION_PERSON_NAME)).isTrue()
        assertThat(active(text or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS)).isTrue()
    }

    @Test
    fun swipeIsOffInPasswordNumberPhoneDateTerminalAndNoSuggestionFields() {
        val text = InputType.TYPE_CLASS_TEXT
        assertThat(active(text or InputType.TYPE_TEXT_VARIATION_PASSWORD)).isFalse()
        assertThat(active(text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)).isFalse()
        assertThat(active(InputType.TYPE_CLASS_NUMBER)).isFalse()
        assertThat(active(InputType.TYPE_CLASS_PHONE)).isFalse()
        assertThat(active(InputType.TYPE_CLASS_DATETIME)).isFalse()
        assertThat(active(InputType.TYPE_NULL)).isFalse()
        assertThat(active(text or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)).isFalse()
    }

    @Test
    fun swipeFollowsTheSettingTheEngineAndTheLayer() {
        assertThat(active(InputType.TYPE_CLASS_TEXT, KeyboardSettings(gestureTyping = false)))
            .isFalse()
        assertThat(active(InputType.TYPE_CLASS_TEXT, KeyboardSettings(suggestionsEnabled = false)))
            .isFalse()
        start()
        assertThat(controller.swipeTypingActive).isTrue()
        controller.onKey(KeyAction.SwitchLayer("symbols"))
        assertThat(controller.swipeTypingActive).isFalse()
        controller.onKey(KeyAction.SwitchLayer("letters"))
        assertThat(controller.swipeTypingActive).isTrue()
        backend.predictor = null
        assertThat(controller.swipeTypingActive).isFalse()
    }

    @Test
    fun aSwipeWhereItIsOffIsIgnored() {
        start(
            FieldProfile.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                0,
            )
        )
        swipe("hello")
        assertThat(text()).isEmpty()
        assertThat(predictor.gestureRequests).isEmpty()
        assertThat(sink.isSilent).isTrue()
    }

    // ------------------------------------------------------------------ preview

    @Test
    fun theTopCandidateIsPreviewedInTheCentreSlotAndNothingIsTyped() {
        start()
        predictor.swipeWords = listOf("hello", "hallo", "hells")
        val p = path()
        controller.gestures.onSwipeStart(p)
        val strip = controller.strip
        assertThat(strip.centre?.text).isEqualTo("hello")
        assertThat(strip.left).isNull()
        assertThat(strip.right).isNull()
        assertThat(text()).isEmpty()
        val request = predictor.gestureRequests.single()
        assertThat(request.gesture.points).hasSize(8)
        assertThat(request.layout.keys).isNotEmpty()
        assertThat(request.maxCandidates).isEqualTo(3)
    }

    @Test
    fun theRequestCarriesTheTextBeforeTheCursorAndTheFieldClass() {
        start(
            FieldProfile.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
                0,
            ),
            text = "Say hi",
        )
        predictor.swipeWords = listOf("there")
        controller.gestures.onSwipeStart(path())
        val context = predictor.gestureRequests.single().context
        assertThat(context.before).isEqualTo("Say hi ") // the swipe will add the space
        assertThat(context.field).isEqualTo(FieldClass.PersonName)
    }

    @Test
    fun previewsAreThrottledToOneEveryFiftyMilliseconds() {
        start()
        predictor.swipeWords = listOf("hello")
        val p = path(4)
        controller.gestures.onSwipeStart(p)
        assertThat(predictor.gestureRequests).hasSize(1)
        repeat(3) {
            p.add(100f + it, 50f, now)
            controller.gestures.onSwipeMove(p)
        }
        assertThat(predictor.gestureRequests).hasSize(1)
        now += SwipeController.PREVIEW_INTERVAL_MS
        p.add(200f, 50f, now)
        controller.gestures.onSwipeMove(p)
        assertThat(predictor.gestureRequests).hasSize(2)
        assertThat(predictor.gestureRequests.last().gesture.points.size).isEqualTo(8)
        now += SwipeController.PREVIEW_INTERVAL_MS - 1
        p.add(210f, 50f, now)
        controller.gestures.onSwipeMove(p)
        assertThat(predictor.gestureRequests).hasSize(2)
    }

    @Test
    fun anOlderPreviewIsDroppedWhenANewerOneIsAsked() {
        start()
        inline = false
        predictor.swipeWords = listOf("hello")
        val p = path(4)
        controller.gestures.onSwipeStart(p) // queued
        now += SwipeController.PREVIEW_INTERVAL_MS
        p.add(100f, 50f, now)
        controller.gestures.onSwipeMove(p) // queued, makes the first stale
        executor.runAll()
        assertThat(predictor.gestureRequests).hasSize(1) // the first never ran
        assertThat(predictor.gestureRequests.single().gesture.points).hasSize(5)
        assertThat(controller.strip.centre?.text).isEqualTo("hello")
    }

    @Test
    fun aPreviewThatArrivesAfterTheLiftIsDropped() {
        start()
        inline = false
        predictor.swipeWords = listOf("hello")
        val p = path()
        controller.gestures.onSwipeStart(p)
        controller.gestures.onSwipeCancel()
        executor.runAll()
        assertThat(controller.strip.isEmpty).isTrue()
        assertThat(predictor.gestureRequests).isEmpty()
    }

    @Test
    fun aNoMatchPreviewLeavesTheStripEmpty() {
        start()
        predictor.swipeWords = emptyList()
        controller.gestures.onSwipeStart(path())
        assertThat(controller.strip.isEmpty).isTrue()
    }

    // ------------------------------------------------------------------ commit and auto-space

    @Test
    fun theTopWordIsCommittedWithoutATrailingSpace() {
        start()
        swipe("hello", "hallo", "hells")
        assertThat(text()).isEqualTo("hello")
        assertThat(editor.composing).isEmpty()
    }

    @Test
    fun aSpaceGoesInAfterAWordTypedWithoutOne() {
        start()
        type("hi") // still composing
        swipe("world")
        assertThat(text()).isEqualTo("hi world")
        assertThat(sink.of<WordCommitted>().map { it.committed })
            .containsExactly("hi", "world")
            .inOrder()
    }

    @Test
    fun noExtraSpaceAfterASpace() {
        start(text = "hi ")
        swipe("world")
        assertThat(text()).isEqualTo("hi world")
    }

    @Test
    fun aSpaceGoesInAfterSentencePunctuation() {
        start(text = "Done.")
        swipe("world")
        assertThat(text()).isEqualTo("Done. world")
    }

    @Test
    fun noSpaceAtTheStartOfTheFieldOrAfterANewline() {
        start()
        swipe("hello")
        assertThat(text()).isEqualTo("hello")
        start(text = "hi\n")
        swipe("world")
        assertThat(text()).isEqualTo("hi\nworld")
    }

    @Test
    fun noSpaceAfterAnOpeningBracket() {
        start(text = "(")
        swipe("hello")
        assertThat(text()).isEqualTo("(hello")
    }

    @Test
    fun anEmailFieldGetsNoAutoSpace() {
        start(
            FieldProfile.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                0,
            )
        )
        swipe("hello")
        swipe("world")
        assertThat(text()).isEqualTo("helloworld")
    }

    @Test
    fun theNextSwipeAddsASpaceFirst() {
        start()
        swipe("hello")
        swipe("world")
        assertThat(text()).isEqualTo("hello world")
        swipe("there")
        assertThat(text()).isEqualTo("hello world there")
    }

    @Test
    fun punctuationAttachesToTheSwipedWord() {
        start()
        swipe("hello")
        type(".")
        assertThat(text()).isEqualTo("hello.")
        swipe("world")
        assertThat(text()).isEqualTo("hello. world")
    }

    @Test
    fun aTypedWordAfterASwipeGetsASpaceFirst() {
        start()
        swipe("hello")
        type("w")
        assertThat(text()).isEqualTo("hello w")
        assertThat(editor.composing).isEqualTo("w")
    }

    @Test
    fun theSpaceKeyAfterASwipeAddsOneSpace() {
        start()
        swipe("hello")
        space()
        assertThat(text()).isEqualTo("hello ")
    }

    @Test
    fun noSpaceIsPendingOnceTheCursorMoved() {
        start()
        swipe("hello")
        editor.moveCursor(0)
        editor.echo(controller)
        type("w")
        assertThat(text()).isEqualTo("whello")
    }

    @Test
    fun noSpaceIsPendingInAnotherField() {
        start()
        swipe("hello")
        start()
        type("w")
        assertThat(text()).isEqualTo("w")
    }

    @Test
    fun nothingIsTypedWhenNoWordFits() {
        start()
        swipe()
        assertThat(text()).isEmpty()
        assertThat(controller.strip.isEmpty).isTrue()
        assertThat(sink.isSilent).isTrue()
    }

    @Test
    fun aCancelTypesNothingAndRestoresTheStrip() {
        start()
        predictor.swipeWords = listOf("hello")
        val p = path()
        controller.gestures.onSwipeStart(p)
        assertThat(controller.strip.centre?.text).isEqualTo("hello")
        controller.gestures.onSwipeCancel()
        assertThat(text()).isEmpty()
        assertThat(controller.strip.centre).isNull()
        controller.gestures.onSwipeEnd(p) // a late end changes nothing
        assertThat(text()).isEmpty()
    }

    @Test
    fun aSwipeIsIgnoredWhileTheEmojiSearchOwnsTheKeys() {
        start()
        controller.showPanel(KeyboardPanel.Emoji)
        controller.openEmojiSearch()
        assertThat(controller.swipeTypingActive).isFalse()
        swipe("hello")
        assertThat(text()).isEmpty()
        assertThat(predictor.gestureRequests).isEmpty()
    }

    // ------------------------------------------------------------------ casing

    @Test
    fun shiftOnceCapitalisesTheWordAndTurnsOff() {
        start()
        controller.onKey(KeyAction.Shift)
        assertThat(controller.state.shift).isEqualTo(ShiftState.Once)
        swipe("hello")
        assertThat(text()).isEqualTo("Hello")
        assertThat(controller.state.shift).isEqualTo(ShiftState.Off)
    }

    @Test
    fun capsLockMakesTheWordAllCapsAndStaysOn() {
        start()
        controller.onKey(KeyAction.Shift)
        now += 100
        controller.onKey(KeyAction.Shift)
        assertThat(controller.state.shift).isEqualTo(ShiftState.Locked)
        swipe("hello")
        assertThat(text()).isEqualTo("HELLO")
        assertThat(controller.state.shift).isEqualTo(ShiftState.Locked)
    }

    @Test
    fun autoCapitalisationAtASentenceStartCapitalisesTheWord() {
        start(
            FieldProfile.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                0,
            )
        )
        assertThat(controller.state.shift).isEqualTo(ShiftState.Auto)
        swipe("hello")
        assertThat(text()).isEqualTo("Hello")
        assertThat(controller.state.shift).isEqualTo(ShiftState.Off)
        swipe("world")
        assertThat(text()).isEqualTo("Hello world")
    }

    @Test
    fun theAlternativesAreCasedLikeTheWord() {
        start()
        controller.onKey(KeyAction.Shift)
        swipe("hello", "hallo")
        assertThat(controller.strip.left?.text).isEqualTo("Hallo")
    }

    @Test
    fun withoutShiftTheDecodersCasingStands() {
        start()
        swipe("Paris")
        assertThat(text()).isEqualTo("Paris")
    }

    // ------------------------------------------------------------------ backspace

    @Test
    fun theFirstBackspaceDeletesTheWordAndTheSpaceItAdded() {
        start()
        type("hi")
        swipe("world")
        assertThat(text()).isEqualTo("hi world")
        backspace()
        assertThat(text()).isEqualTo("hi")
        backspace()
        assertThat(text()).isEqualTo("h") // characters, as usual
    }

    @Test
    fun theFirstBackspaceDeletesTheWordWhenNoSpaceWasAdded() {
        start(text = "hi ")
        swipe("world")
        backspace()
        assertThat(text()).isEqualTo("hi ")
        backspace()
        assertThat(text()).isEqualTo("hi")
    }

    @Test
    fun aKeyTypedAfterTheSwipeEndsTheWholeWordDelete() {
        start()
        swipe("hello")
        type(".")
        backspace()
        assertThat(text()).isEqualTo("hello")
    }

    @Test
    fun aCursorMoveEndsTheWholeWordDelete() {
        start()
        swipe("hello")
        editor.moveCursor(4)
        editor.echo(controller)
        backspace()
        assertThat(text()).isEqualTo("helo") // one character, not the word
    }

    @Test
    fun aSecondSwipeIsDeletedAsAWholeToo() {
        start()
        swipe("hello")
        swipe("world")
        backspace()
        assertThat(text()).isEqualTo("hello")
    }

    // ------------------------------------------------------------------ the strip

    @Test
    fun theStripOffersTheAlternativesWithTheWordMarkedCurrent() {
        start()
        swipe("hello", "hallo", "hells")
        val strip = controller.strip
        assertThat(strip.centre?.text).isEqualTo("hello")
        assertThat(strip.centre?.bold).isTrue()
        assertThat(strip.left?.text).isEqualTo("hallo")
        assertThat(strip.right?.text).isEqualTo("hells")
        assertThat(strip.left?.kind).isEqualTo(SlotKind.Suggestion)
    }

    @Test
    fun tappingAnAlternativeReplacesTheSwipedWord() {
        start(text = "hi ")
        swipe("hello", "hallo", "hells")
        controller.onSuggestionTap(controller.strip.left!!)
        assertThat(text()).isEqualTo("hi hallo")
        assertThat(controller.strip.centre?.text).isEqualTo("hallo")
        assertThat(controller.strip.left?.text).isEqualTo("hello")
        controller.onSuggestionTap(controller.strip.right!!)
        assertThat(text()).isEqualTo("hi hells")
        backspace() // still one whole word
        assertThat(text()).isEqualTo("hi ")
    }

    @Test
    fun tappingTheCurrentWordChangesNothing() {
        start()
        swipe("hello", "hallo")
        controller.onSuggestionTap(controller.strip.centre!!)
        assertThat(text()).isEqualTo("hello")
        assertThat(sink.of<WordReplaced>()).isEmpty()
    }

    @Test
    fun aSingleCandidateShowsOnlyTheWord() {
        start()
        swipe("hello")
        assertThat(controller.strip.centre?.text).isEqualTo("hello")
        assertThat(controller.strip.left).isNull()
        assertThat(controller.strip.right).isNull()
    }

    @Test
    fun theAlternativesGoAwayWithTheNextKey() {
        start()
        swipe("hello", "hallo")
        type(".")
        assertThat(controller.strip.isEmpty).isTrue()
    }

    // ------------------------------------------------------------------ learning

    @Test
    fun aSwipedWordIsLearnedLikeAPickedSuggestionWithoutTouches() {
        start(text = "hi ")
        swipe("hello", "hallo")
        val e = committed().single()
        assertThat(e.method).isEqualTo(CommitMethod.Gesture)
        assertThat(e.committed).isEqualTo("hello")
        assertThat(e.typed).isEmpty()
        assertThat(e.context).containsExactly("hi")
        assertThat(e.touches).isEmpty()
        assertThat(controller.touchLog.snapshot()).isEmpty()
        type(".")
        assertThat(sink.sentence.map { it.word to it.end }).containsExactly("hello" to true)
    }

    @Test
    fun replacingTheWordFromTheStripCountsAsReplacingASuggestion() {
        start(text = "hi ")
        swipe("hello", "hallo")
        controller.onSuggestionTap(controller.strip.left!!)
        val r = sink.of<WordReplaced>().single()
        assertThat(r.original).isEqualTo("hello")
        assertThat(r.replacement).isEqualTo("hallo")
        assertThat(r.context).containsExactly("hi")
    }

    @Test
    fun aDeletedSwipedWordNeverReachesTheSentenceBuffer() {
        start(text = "hi ")
        swipe("hello")
        backspace()
        type("x")
        assertThat(sink.sentence.map { it.word }).doesNotContain("hello")
    }

    @Test
    fun incognitoLearnsNothingFromASwipe() {
        start(settings = KeyboardSettings(incognito = true))
        swipe("hello", "hallo")
        assertThat(text()).isEqualTo("hello")
        controller.onSuggestionTap(controller.strip.left!!)
        assertThat(text()).isEqualTo("hallo")
        type(".")
        assertThat(sink.isSilent).isTrue()
    }

    @Test
    fun aNoLearningFieldLearnsNothingFromASwipe() {
        start(
            FieldProfile.from(
                InputType.TYPE_CLASS_TEXT,
                android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            )
        )
        swipe("hello")
        type(".")
        assertThat(text()).isEqualTo("hello.")
        assertThat(sink.isSilent).isTrue()
    }

    private companion object {
        const val GEO_W = 1000f
        const val GEO_H = 400f
    }

    // ------------------------------------------------------------------ engine readiness

    @Test
    fun swipeComesOnAsSoonAsTheEngineLoadsWithoutANewStartInput() {
        backend.predictor = null
        start()
        val read = HashSet<Any>()
        val snapshot = Snapshot.takeSnapshot(readObserver = { read += it })
        val before =
            try {
                snapshot.enter { controller.swipeTypingActive }
            } finally {
                snapshot.dispose()
            }
        assertThat(before).isFalse()
        val changed = HashSet<Any>()
        val handle = Snapshot.registerApplyObserver { objects, _ -> changed.addAll(objects) }
        backend.predictor = predictor
        Snapshot.sendApplyNotifications()
        handle.dispose()
        // What the keyboard composition read was invalidated by the engine arriving.
        assertThat(changed.any { it in read }).isTrue()
        assertThat(controller.swipeTypingActive).isTrue()
    }

    // ------------------------------------------------------------------ late final decode

    /** A whole swipe whose decoder thread has not run yet: the lift finds no answer in time. */
    private fun liftWithoutAnAnswer(vararg words: String) {
        inline = false
        predictor.swipeWords = words.toList()
        val p = path()
        controller.gestures.onSwipeStart(p)
        controller.gestures.onSwipeEnd(p)
    }

    @Test
    fun aLateFinalDecodeIsTypedWhenItArrives() {
        start(
            FieldProfile.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                0,
            )
        )
        liftWithoutAnAnswer("hello", "hallo")
        assertThat(text()).isEmpty()
        executor.runAll()
        assertThat(text()).isEqualTo("Hello")
        assertThat(controller.state.shift).isEqualTo(ShiftState.Off) // consumed like a typed letter
        assertThat(controller.strip.centre?.text).isEqualTo("Hello")
        assertThat(controller.strip.centre?.bold).isTrue()
        // Only the final request was decoded (the preview was stale); nothing came from a preview.
        assertThat(predictor.gestureRequests).hasSize(1)
    }

    @Test
    fun aKeyBeforeTheLateResultDropsTheSwipeAndTypesNothingStale() {
        start()
        liftWithoutAnAnswer("hello")
        type("w") // waits a little more, still nothing: the swipe is dropped
        assertThat(text()).isEqualTo("w")
        executor.runAll()
        assertThat(text()).isEqualTo("w")
        assertThat(committed().map { it.committed }).doesNotContain("hello")
        assertThat(controller.strip.centre?.text).isNotEqualTo("hello")
    }

    @Test
    fun aLateResultIsDroppedWhenTheCursorMovedMeanwhile() {
        start(text = "ab")
        liftWithoutAnAnswer("hello")
        editor.moveCursor(0)
        editor.echo(controller)
        executor.runAll()
        assertThat(text()).isEqualTo("ab")
    }

    // ------------------------------------------------------------------ auto-cap before a swipe

    @Test
    fun aWordSwipedAfterASentenceEndIsCapitalised() {
        start(
            FieldProfile.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                0,
            ),
            text = "hello.",
        )
        swipe("there")
        assertThat(text()).isEqualTo("hello. There")
        swipe("world") // not a sentence start
        assertThat(text()).isEqualTo("hello. There world")
    }

    @Test
    fun aWordSwipedAfterAWordIsCapitalisedInWordsMode() {
        start(
            FieldProfile.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS,
                0,
            ),
            text = "hello",
        )
        swipe("world")
        assertThat(text()).isEqualTo("hello World")
    }

    @Test
    fun noCapitalAfterAWordMidSentence() {
        start(
            FieldProfile.from(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                0,
            ),
            text = "hello",
        )
        swipe("world")
        assertThat(text()).isEqualTo("hello world")
    }

    // ------------------------------------------------------------------ other fingers

    @Test
    fun aKeyFromAnotherFingerDuringASwipeCancelsTheSwipe() {
        start()
        predictor.swipeWords = listOf("hello")
        val p = path()
        controller.gestures.onSwipeStart(p)
        type("a")
        controller.gestures.onSwipeEnd(p)
        assertThat(text()).isEqualTo("a")
        assertThat(predictor.gestureRequests.map { it.context.before }).containsExactly("")
    }

    @Test
    fun aBackspaceDuringASwipeCancelsItAndDeletesAsUsual() {
        start(text = "hi")
        predictor.swipeWords = listOf("hello")
        val p = path()
        controller.gestures.onSwipeStart(p)
        backspace()
        controller.gestures.onSwipeEnd(p)
        assertThat(text()).isEqualTo("h")
    }

    @Test
    fun shiftDuringASwipeDoesNotCancelIt() {
        start()
        predictor.swipeWords = listOf("hello")
        val p = path()
        controller.gestures.onSwipeStart(p)
        controller.onKey(KeyAction.Shift)
        controller.gestures.onSwipeEnd(p)
        assertThat(text()).isEqualTo("Hello")
    }

    // ------------------------------------------------------------------ composing and the strip

    @Test
    fun aComposingWordIsFinishedAsTypedBeforeTheSwipedWord() {
        start()
        type("hel")
        swipe("world")
        assertThat(text()).isEqualTo("hel world")
        assertThat(predictor.gestureRequests.last().context.before).isEqualTo("hel ")
    }

    @Test
    fun theAlternativesAreGoneOnceTheCursorMoved() {
        start()
        swipe("hello", "hallo")
        assertThat(controller.strip.left ?: controller.strip.right).isNotNull()
        editor.moveCursor(0)
        editor.echo(controller)
        // Nothing offers a replacement of the swiped word any more, and the text is untouched.
        assertThat(controller.strip.left).isNull()
        assertThat(controller.strip.right).isNull()
        assertThat(text()).isEqualTo("hello")
    }
}
