package dev.keycorrect.feature.keyboard.prediction

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.engineapi.Candidate
import dev.keycorrect.core.engineapi.CandidateSource
import dev.keycorrect.core.engineapi.ComposingInput
import dev.keycorrect.core.engineapi.GestureInput
import dev.keycorrect.core.engineapi.GestureRequest
import dev.keycorrect.core.engineapi.KeyLayoutModel
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.PredictResult
import dev.keycorrect.core.engineapi.TouchPoint
import dev.keycorrect.core.engineapi.TypingContext
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.layout.KeyboardGeometry
import dev.keycorrect.core.layout.KeyboardSet
import dev.keycorrect.feature.keyboard.ManualExecutor
import dev.keycorrect.feature.keyboard.R
import org.junit.Test

class ShortcutExpanderTest {
    private val expander =
        ShortcutExpander(now = { 42L }, formatDate = { "D$it" }, formatTime = { "T$it" })

    @Test
    fun plainTextIsUnchanged() {
        assertThat(expander.expand("on my way")).isEqualTo(Expansion("on my way"))
    }

    @Test
    fun dateAndTimeUseTheFormatters() {
        assertThat(expander.expand("{date} {time}").text).isEqualTo("D42 T42")
    }

    @Test
    fun theFirstCursorMarkerWinsAndTheRestAreDropped() {
        val e = expander.expand("a{cursor}b{cursor}c")
        assertThat(e.text).isEqualTo("abc")
        assertThat(e.cursor).isEqualTo(1)
        assertThat(e.head).isEqualTo("a")
        assertThat(e.tail).isEqualTo("bc")
    }

    @Test
    fun clipboardAndUnknownVariablesStayLiteral() {
        assertThat(expander.expand("x {clipboard} {nope} {").text)
            .isEqualTo("x {clipboard} {nope} {")
    }
}

class StripBuilderTest {
    private fun d(t: String) = Candidate(t, -1f, CandidateSource.Dictionary)

    private fun build(result: PredictResult, typed: String, pending: Candidate? = null) =
        StripBuilder.build(result, typed, pending) { "<$it>" }

    @Test
    fun withoutAutocorrectTheBestIsCentredAndTheNextTwoFlank() {
        val m = build(PredictResult(listOf(d("a"), d("b"), d("c")), d("x"), null), "x")
        assertThat(listOf(m.left?.text, m.centre?.text, m.right?.text))
            .containsExactly("b", "a", "c")
            .inOrder()
        assertThat(m.centre?.bold).isFalse()
    }

    @Test
    fun slotLabelsFollowTheSlotKindAndAnAutoExpandingShortcutReadsAsAutocorrect() {
        assertThat(StripSlot("a", SlotKind.Suggestion).labelRes())
            .isEqualTo(R.string.strip_suggestion)
        assertThat(StripSlot("a", SlotKind.Literal).labelRes()).isEqualTo(R.string.strip_literal)
        assertThat(StripSlot("a", SlotKind.Shortcut).labelRes()).isEqualTo(R.string.strip_shortcut)
        assertThat(StripSlot("a", SlotKind.Shortcut, bold = true).labelRes())
            .isEqualTo(R.string.strip_autocorrect)
    }

    @Test
    fun aPendingAutocorrectIsBoldInTheCentreWithTheLiteralOnTheLeft() {
        val fix = d("the")
        val m = build(PredictResult(listOf(fix, d("tea")), d("teh"), fix), "teh", fix)
        assertThat(m.centre).isEqualTo(StripSlot("the", SlotKind.Autocorrect, "the", bold = true))
        assertThat(m.left?.kind).isEqualTo(SlotKind.Literal)
        assertThat(m.left?.text).isEqualTo("teh")
        assertThat(m.right?.text).isEqualTo("tea")
    }

    @Test
    fun aPhraseTakesTheRightSlot() {
        val phrase = Candidate("how are you", -1f, CandidateSource.Dictionary, true)
        val m =
            build(
                PredictResult(listOf(d("how"), d("hot"), d("house")), d("how"), null, phrase),
                "how",
            )
        assertThat(m.right?.kind).isEqualTo(SlotKind.Phrase)
        assertThat(m.right?.labelRes()).isEqualTo(R.string.strip_phrase)
    }

    @Test
    fun withNothingToOfferTheTypedWordSitsInTheCentre() {
        val m = build(PredictResult(emptyList(), d("zzq"), null), "zzq")
        assertThat(m.centre?.kind).isEqualTo(SlotKind.Literal)
        assertThat(m.left).isNull()
    }

    @Test
    fun shortcutsShowTheirResolvedPreviewButInsertTheTemplate() {
        val s = Candidate("{date}", 0f, CandidateSource.Shortcut)
        val m = build(PredictResult(listOf(s), d("dd"), null), "dd")
        assertThat(m.centre?.text).isEqualTo("<{date}>")
        assertThat(m.centre?.insert).isEqualTo("{date}")
    }

    @Test
    fun nextWordPredictionsHaveNoLiteral() {
        val m = build(PredictResult(listOf(d("a"), d("b")), null, null), "")
        assertThat(m.left?.text).isEqualTo("b")
        assertThat(m.centre?.text).isEqualTo("a")
    }

    @Test
    fun equalModelsAreEqualSoTheStripDoesNotRecompose() {
        val r = PredictResult(listOf(d("a")), d("x"), null)
        assertThat(build(r, "x")).isEqualTo(build(r.copy(sequence = 9), "x"))
    }
}

class WordTouchLogTest {
    private fun rec(w: String) = WordRecord(w, w, emptyList(), 1, CommitMethod.Typed)

    @Test
    fun keepsTheNewestRecordsOldestFirst() {
        val log = WordTouchLog(3)
        listOf("a", "b", "c", "d").forEach { log.record(rec(it)) }
        assertThat(log.snapshot().map { it.typed }).containsExactly("b", "c", "d").inOrder()
        assertThat(log.size).isEqualTo(3)
    }

    @Test
    fun marksOnlyTheLatestMatchingRecordReverted() {
        val log = WordTouchLog(3)
        log.record(rec("a"))
        log.markLastReverted("zzz")
        assertThat(log.snapshot().single().reverted).isFalse()
        log.markLastReverted("a")
        assertThat(log.snapshot().single().reverted).isTrue()
    }

    @Test
    fun clearEmptiesIt() {
        val log = WordTouchLog()
        log.record(rec("a"))
        log.clear()
        assertThat(log.snapshot()).isEmpty()
    }
}

class KeyLayoutModelsTest {
    private val layer =
        KeyboardComposer(BuiltInLayouts.repository())
            .compose(KeyboardSet.Default, KeyboardSet.LETTERS)

    @Test
    fun hasTheLettersCentredInSurfacePixelsWithTheGivenId() {
        val model = KeyLayoutModels.from(KeyboardGeometry(layer, 1000f, 400f), 7L)
        assertThat(model.id).isEqualTo(7L)
        val chars = model.keys.map { it.char }
        assertThat(chars).containsAtLeastElementsIn("qwertyuiopasdfghjklzxcvbnm".toList())
        val q = model.keys.first { it.char == 'q' }
        val p = model.keys.first { it.char == 'p' }
        assertThat(q.centerX).isLessThan(p.centerX)
        assertThat(p.centerX).isLessThan(1000f)
    }

    @Test
    fun aResizedKeyboardIsADifferentModel() {
        val a = KeyLayoutModels.from(KeyboardGeometry(layer, 1000f, 400f), 1L)
        val b = KeyLayoutModels.from(KeyboardGeometry(layer, 500f, 200f), 2L)
        assertThat(a).isNotEqualTo(b)
    }
}

class DecoderLoopTest {
    private val executor = ManualExecutor()
    private val delivered = mutableListOf<PredictResult>()
    private var predictor: Predictor? = Predictor { r, _ ->
        PredictResult(emptyList(), null, null, sequence = r.sequence)
    }
    private val loop = DecoderLoop(executor, { it.run() }) { predictor }

    private fun request(seq: Long) =
        PredictRequest(
            TypingContext(),
            ComposingInput("a", listOf(null)),
            KeyLayoutModel(1, emptyList()),
            sequence = seq,
        )

    @Test
    fun deliversAResultForTheCurrentSequence() {
        val seq = loop.advance()
        loop.submit(request(seq)) { delivered += it }
        executor.runAll()
        assertThat(delivered.map { it.sequence }).containsExactly(seq)
    }

    @Test
    fun dropsAResultOnceANewerEventHasArrived() {
        val seq = loop.advance()
        loop.submit(request(seq)) { delivered += it }
        loop.advance()
        executor.runAll()
        assertThat(delivered).isEmpty()
    }

    @Test
    fun theStaleLambdaTurnsTrueWhenInputMovesOn() {
        var staleSeen = false
        predictor = Predictor { r, stale ->
            loop.advance()
            staleSeen = stale()
            PredictResult(emptyList(), null, null, sequence = r.sequence)
        }
        loop.predictNow(request(loop.advance()))
        assertThat(staleSeen).isTrue()
    }

    @Test
    fun aFailingPredictorYieldsNothingAndNoCrash() {
        predictor = Predictor { _, _ -> throw IllegalStateException("secret typed text") }
        assertThat(loop.predictNow(request(loop.advance()))).isNull()
    }

    @Test
    fun withoutAPredictorNothingIsQueued() {
        predictor = null
        loop.submit(request(loop.advance())) { delivered += it }
        assertThat(executor.queue).isEmpty()
        assertThat(loop.predictNow(request(1))).isNull()
    }

    // ------------------------------------------------------------------ final swipe decode

    private fun gestureRequest(seq: Long) =
        GestureRequest(
            TypingContext(),
            GestureInput(listOf(TouchPoint(0f, 0f, 0L), TouchPoint(1f, 1f, 10L))),
            KeyLayoutModel(1, emptyList()),
            3,
            seq,
        )

    @Test
    fun aFinalGestureDecodeIsDeliveredLateWhileTheSequenceIsCurrent() {
        val seq = loop.advance()
        val final = loop.submitFinalGesture(gestureRequest(seq))!!
        assertThat(final.await(1)).isFalse() // the decoder thread has not run
        assertThat(final.whenDone { delivered += it }).isTrue()
        executor.runAll()
        assertThat(delivered.map { it.sequence }).containsExactly(seq)
    }

    @Test
    fun aFinalGestureDecodeIsNotDeliveredAfterNewerInputOrACancel() {
        val seq = loop.advance()
        val newer = loop.submitFinalGesture(gestureRequest(seq))!!
        newer.whenDone { delivered += it }
        loop.advance()
        val seq2 = loop.advance()
        val cancelled = loop.submitFinalGesture(gestureRequest(seq2))!!
        cancelled.whenDone { delivered += it }
        cancelled.cancel()
        executor.runAll()
        assertThat(delivered).isEmpty()
    }

    @Test
    fun aFinalGestureDecodeThatFinishedFirstIsReadDirectly() {
        val seq = loop.advance()
        val final = loop.submitFinalGesture(gestureRequest(seq))!!
        executor.runAll()
        assertThat(final.await(1)).isTrue()
        assertThat(final.result?.sequence).isEqualTo(seq)
        assertThat(final.whenDone { delivered += it }).isFalse()
    }

    // ------------------------------------------------------------------ separator policy

    @Test
    fun aSeparatorUsesTheCompletedResultOfTheCurrentSequence() {
        val seq = loop.advance()
        loop.submit(request(seq)) { delivered += it }
        executor.runAll()
        var decodedOnCaller = false
        predictor = Predictor { r, _ ->
            decodedOnCaller = true
            PredictResult(emptyList(), null, null, sequence = r.sequence)
        }
        assertThat(loop.resultForSeparator(seq) { request(seq) }?.sequence).isEqualTo(seq)
        assertThat(decodedOnCaller).isFalse()
    }

    @Test
    fun aSeparatorDecodesOnTheCallerOnlyWhenNothingIsInFlight() {
        val seq = loop.advance()
        assertThat(loop.resultForSeparator(seq) { request(seq) }?.sequence).isEqualTo(seq)
    }

    @Test
    fun aSeparatorGivesUpAfterTheBoundedWaitWhileADecodeIsOutstanding() {
        val impatient = DecoderLoop(executor, { it.run() }, separatorWaitMs = 10) { predictor }
        val seq = impatient.advance()
        impatient.submit(request(seq)) { delivered += it } // queued, never run
        var called = false
        predictor = Predictor { r, _ ->
            called = true
            PredictResult(emptyList(), null, null, sequence = r.sequence)
        }
        val start = System.nanoTime()
        assertThat(impatient.resultForSeparator(seq) { request(seq) }).isNull()
        assertThat(called).isFalse()
        assertThat((System.nanoTime() - start) / 1_000_000).isAtMost(1_000L)
    }

    @Test
    fun aSeparatorWaitsForAnOutstandingDecodeOnAnotherThread() {
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val gate = java.util.concurrent.CountDownLatch(1)
            val threaded =
                DecoderLoop(pool, { it.run() }, separatorWaitMs = 5_000) {
                    Predictor { r, _ ->
                        gate.await(5, java.util.concurrent.TimeUnit.SECONDS)
                        PredictResult(emptyList(), null, null, sequence = r.sequence)
                    }
                }
            val seq = threaded.advance()
            threaded.submit(request(seq)) { delivered += it }
            Thread {
                Thread.sleep(30)
                gate.countDown()
            }
                .start()
            assertThat(threaded.resultForSeparator(seq) { error("must not decode here") }?.sequence)
                .isEqualTo(seq)
        } finally {
            pool.shutdownNow()
        }
    }
}
