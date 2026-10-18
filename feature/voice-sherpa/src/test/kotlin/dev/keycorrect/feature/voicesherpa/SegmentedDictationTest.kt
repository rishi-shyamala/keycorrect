package dev.keycorrect.feature.voicesherpa

import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors
import dev.keycorrect.core.voice.SttConfig
import dev.keycorrect.core.voice.SttError
import dev.keycorrect.core.voice.SttResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test

class SegmentedDictationTest {
    /** Cuts a segment whenever [cutEvery] samples have arrived; flush closes the remainder. */
    private class FakeDetector(private val cutEvery: Int) : SpeechDetector {
        private val pending = ArrayDeque<FloatArray>()
        private var buffer = FloatArray(0)
        var released = false

        override fun accept(samples: FloatArray) {
            buffer += samples
            while (buffer.size >= cutEvery) {
                pending.addLast(buffer.copyOfRange(0, cutEvery))
                buffer = buffer.copyOfRange(cutEvery, buffer.size)
            }
        }

        override fun flush() {
            if (buffer.isNotEmpty()) pending.addLast(buffer)
            buffer = FloatArray(0)
        }

        override fun reset() {
            buffer = FloatArray(0)
            pending.clear()
        }

        override val speechDetected: Boolean
            get() = buffer.isNotEmpty()

        override fun nextSegment() = pending.removeFirstOrNull()

        override fun release() {
            released = true
        }
    }

    /** "Recognises" a segment as the words in [script], in order; its length is ignored. */
    private class FakeRecognizer(private val script: List<String>) : SegmentRecognizer {
        var calls = 0
        var released = false

        override fun recognize(segment: FloatArray) = script.getOrElse(calls++) { "" }

        override fun release() {
            released = true
        }
    }

    private class Harness(cutEvery: Int, script: List<String>) {
        val detector = FakeDetector(cutEvery)
        val recognizer = FakeRecognizer(script)
        val results = CopyOnWriteArrayList<SttResult>()
        private val done = CountDownLatch(1)
        val created = CountDownLatch(1)
        val session =
            SegmentedDictation(
                listener = {
                    results += it
                    if (it is SttResult.Final || it is SttResult.Error) done.countDown()
                },
                create = {
                    created.countDown()
                    SpeechComponents(detector, recognizer)
                },
            )

        fun await() = assertThat(done.await(5, TimeUnit.SECONDS)).isTrue()
    }

    @Test
    fun partialsGrowWithEachFinishedSegmentAndFinalCompletesThem() {
        val h = Harness(cutEvery = 4, script = listOf("hello", "world", "again"))
        h.session.acceptAudio(ShortArray(8)) // two segments
        h.session.acceptAudio(ShortArray(2)) // the third, closed by finish()
        h.session.finish()
        h.await()
        assertThat(h.results)
            .containsExactly(
                SttResult.Partial("hello", 5, endsSegment = true),
                SttResult.Partial("hello world", 11, endsSegment = true),
                SttResult.Partial("hello world again", 17, endsSegment = true),
                SttResult.Final("hello world again"),
            )
            .inOrder()
    }

    @Test
    fun blankSegmentsAddNothing() {
        val h = Harness(cutEvery = 2, script = listOf(" ", "  hi  ", ""))
        h.session.acceptAudio(ShortArray(6))
        h.session.finish()
        h.await()
        assertThat(h.results)
            .containsExactly(SttResult.Partial("hi", 2, endsSegment = true), SttResult.Final("hi"))
            .inOrder()
    }

    @Test
    fun silenceEndsWithNoMatch() {
        val h = Harness(cutEvery = 100, script = emptyList())
        h.session.acceptAudio(ShortArray(0))
        h.session.finish()
        h.await()
        assertThat(h.results).containsExactly(SttResult.Error(SttError.NoMatch))
    }

    @Test
    fun finishFreesTheModels() {
        val h = Harness(cutEvery = 2, script = listOf("a"))
        h.session.acceptAudio(ShortArray(2))
        h.session.finish()
        h.await()
        waitUntil { h.detector.released && h.recognizer.released }
        // Late audio after finish is ignored.
        h.session.acceptAudio(ShortArray(4))
        assertThat(h.recognizer.calls).isEqualTo(1)
    }

    @Test
    fun cancelEmitsNothingAndFreesTheModels() {
        val h = Harness(cutEvery = 2, script = listOf("a", "b"))
        assertThat(h.created.await(5, TimeUnit.SECONDS)).isTrue()
        h.session.cancel()
        h.session.acceptAudio(ShortArray(4))
        h.session.finish()
        waitUntil { h.detector.released && h.recognizer.released }
        assertThat(h.results).isEmpty()
    }

    @Test
    fun pcmIsScaledToFloatsAndLengthIsHonoured() {
        val seen = CopyOnWriteArrayList<FloatArray>()
        val detector =
            object : SpeechDetector {
                override fun accept(samples: FloatArray) {
                    seen += samples
                }

                override fun flush() = Unit

                override fun reset() = Unit

                override val speechDetected = false

                override fun nextSegment(): FloatArray? = null

                override fun release() = Unit
            }
        val done = CountDownLatch(1)
        val session =
            SegmentedDictation(
                listener = { done.countDown() },
                create = { SpeechComponents(detector, FakeRecognizer(emptyList())) },
            )
        session.acceptAudio(shortArrayOf(16384, -32768, 99), length = 2)
        session.finish()
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(seen.single().toList()).containsExactly(0.5f, -1f).inOrder()
    }

    @Test
    fun aFailingModelEndsTheSessionWithEngineError() {
        val results = CopyOnWriteArrayList<SttResult>()
        val done = CountDownLatch(1)
        val session =
            SegmentedDictation(
                listener = {
                    results += it
                    done.countDown()
                },
                create = { error("cannot load") },
            )
        session.acceptAudio(ShortArray(4))
        session.finish()
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(results).containsExactly(SttResult.Error(SttError.Engine))
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertThat(condition()).isTrue()
    }

    @Test
    fun isBusyWhileItStillDecodesAndNotAfterwards() {
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        val recognizer =
            object : SegmentRecognizer {
                override fun recognize(segment: FloatArray): String {
                    started.countDown()
                    gate.await(5, TimeUnit.SECONDS)
                    return "slow"
                }

                override fun release() = Unit
            }
        val done = CountDownLatch(1)
        val session =
            SegmentedDictation(
                listener = { if (it is SttResult.Final) done.countDown() },
                create = { SpeechComponents(FakeDetector(cutEvery = 4), recognizer) },
            )
        assertThat(session.busy).isTrue() // still loading the models
        session.acceptAudio(ShortArray(4))
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
        session.finish()
        assertThat(session.busy).isTrue()
        gate.countDown()
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (session.busy && System.nanoTime() < deadline) Thread.sleep(5)
        assertThat(session.busy).isFalse()
    }

    // --- Live words, splitting, progress (a direct executor and a fake clock: no threads) ---

    /** A detector the test drives: [speech] is whatever it says, [segments] wait to be taken. */
    private class ScriptedDetector : SpeechDetector {
        var speech = false
        val segments = ArrayDeque<FloatArray>()
        val accepted = ArrayList<Int>()
        var resets = 0

        override fun accept(samples: FloatArray) {
            accepted += samples.size
        }

        override fun flush() = Unit

        override fun reset() {
            resets++
        }

        override val speechDetected: Boolean
            get() = speech

        override fun nextSegment() = segments.removeFirstOrNull()

        override fun release() = Unit
    }

    private class Direct(
        recognize: (FloatArray) -> String,
        phraseLimitMillis: Long = 7000,
        liveUpdateMillis: Long = SttConfig.LIVE_AUTO,
        val detector: ScriptedDetector = ScriptedDetector(),
    ) {
        var now = 0L
        val results = ArrayList<SttResult>()
        val decoded = ArrayList<Int>()
        val session =
            SegmentedDictation(
                listener = { results += it },
                create = {
                    SpeechComponents(
                        detector,
                        object : SegmentRecognizer {
                            override fun recognize(segment: FloatArray): String {
                                decoded += segment.size
                                return recognize(segment)
                            }

                            override fun release() = Unit
                        },
                    )
                },
                executor = MoreExecutors.newDirectExecutorService(),
                phraseLimitMillis = phraseLimitMillis,
                liveUpdateMillis = liveUpdateMillis,
                clock = { now },
            )

        /** [ms] of loud audio at the current time, then the clock moves on by [ms]. */
        fun chunk(ms: Int, atMs: Long = now) {
            now = atMs
            session.acceptAudio(ShortArray(ms * 16) { 8000 })
            now = atMs + ms
        }

        val partials
            get() = results.filterIsInstance<SttResult.Partial>()

        val progress
            get() = results.filterIsInstance<SttResult.Progress>()
    }

    @Test
    fun liveWordsAreDecodedAboutEverySecondAndAreNotStable() {
        var n = 0
        val h = Direct({ "live ${++n}" })
        h.detector.speech = true
        repeat(8) { h.chunk(200) } // 0..1400 ms
        // First at 800 ms (700 ms after speech began, the decode is instant), next 600 ms later.
        assertThat(h.partials)
            .containsExactly(
                SttResult.Partial("live 1", stablePrefixLength = 0),
                SttResult.Partial("live 2", stablePrefixLength = 0),
            )
            .inOrder()
        assertThat(h.decoded).containsExactly(16000, 25600).inOrder()
    }

    @Test
    fun aSlowLiveDecodeBacksTheNextOneOffAndNeverQueues() {
        var calls = 0
        lateinit var h: Direct
        h =
            Direct({
                calls++
                h.now += 1000
                "word $calls"
            })
        h.detector.speech = true
        repeat(5) { h.chunk(200) } // the decode at 800 ms ends at 1800 ms
        assertThat(calls).isEqualTo(1)
        // 2 x 1000 ms: not before 3800 ms, however much audio comes.
        for (t in 1800..3600 step 200) h.chunk(200, t.toLong())
        assertThat(calls).isEqualTo(1)
        h.chunk(200, 3800)
        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun aFastLiveDecodeIsRepeatedNoSoonerThanHalfASecond() {
        var calls = 0
        val h =
            Direct({
                calls++
                "w"
            })
        h.detector.speech = true
        repeat(5) { h.chunk(200) } // decode at 800
        h.chunk(200, 1000)
        h.chunk(200, 1200)
        assertThat(calls).isEqualTo(1)
        h.chunk(200, 1300)
        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun liveWordsFollowTheCommittedText() {
        var phase = 0
        val h = Direct({ if (phase == 0) "one two" else "three" })
        h.detector.speech = true
        repeat(5) { h.chunk(200) }
        // The pause: the VAD hands the segment over and the phrase is committed.
        h.detector.speech = false
        h.detector.segments += FloatArray(16000)
        h.chunk(200)
        phase = 1
        h.detector.speech = true
        for (t in 1200..2400 step 200) h.chunk(200, t.toLong())
        assertThat(h.partials)
            .containsExactly(
                SttResult.Partial("one two", stablePrefixLength = 0),
                SttResult.Partial("one two", stablePrefixLength = 7, endsSegment = true),
                SttResult.Partial("one two three", stablePrefixLength = 7),
            )
            .inOrder()
    }

    @Test
    fun theFinalDecodeReplacesTheLiveWords() {
        var final = false
        val h = Direct({ if (final) "hello world" else "hello wor" })
        h.detector.speech = true
        repeat(5) { h.chunk(200) }
        final = true
        h.detector.speech = false
        h.detector.segments += FloatArray(16000)
        h.chunk(200)
        h.session.finish()
        assertThat(h.partials.map { it.text }).containsExactly("hello wor", "hello world").inOrder()
        assertThat(h.results.last()).isEqualTo(SttResult.Final("hello world"))
    }

    @Test
    fun anEmptyFinalDecodeKeepsWhatWasShownLive() {
        var final = false
        val h = Direct({ if (final) "" else "seen live" })
        h.detector.speech = true
        repeat(5) { h.chunk(200) }
        final = true
        h.detector.segments += FloatArray(16000)
        h.chunk(200)
        h.session.finish()
        assertThat(h.results.last()).isEqualTo(SttResult.Final("seen live"))
    }

    @Test
    fun aLongSegmentThatDecodesToNothingIsSplitAtItsQuietestPoint() {
        val samples = 16000
        // 6 s of loud audio with 100 ms of near-silence at 3.2 s, off the exact middle.
        val segment = FloatArray(6 * samples) { 0.5f }
        for (i in (3.2 * samples).toInt() until (3.3 * samples).toInt()) segment[i] = 0.001f
        var calls = 0
        val h = Direct({ if (it.size > 4 * samples) "" else if (calls++ == 0) "left" else "right" })
        h.detector.segments += segment
        h.session.acceptAudio(ShortArray(1600))
        h.session.finish()
        assertThat(h.decoded).hasSize(3)
        assertThat(h.decoded[0]).isEqualTo(6 * samples)
        assertThat(h.decoded[1] + h.decoded[2]).isEqualTo(6 * samples)
        assertThat(h.decoded[1]).isAtLeast((3.2 * samples).toInt())
        assertThat(h.decoded[1]).isAtMost((3.3 * samples).toInt())
        assertThat(h.results.last()).isEqualTo(SttResult.Final("left right"))
    }

    @Test
    fun aShortSegmentIsNeverSplit() {
        val h = Direct({ "" })
        h.detector.segments += FloatArray(3 * 16000)
        h.session.finish()
        assertThat(h.decoded).hasSize(1)
    }

    @Test
    fun speechWithoutAnyTextIsUnclearButBriefNoiseIsNoMatch() {
        val unclear = Direct({ "" })
        unclear.detector.segments += FloatArray(2 * 16000)
        unclear.session.finish()
        assertThat(unclear.results.last()).isEqualTo(SttResult.Error(SttError.Unclear))

        val noise = Direct({ "" })
        noise.detector.segments += FloatArray(8000)
        noise.session.finish()
        assertThat(noise.results.last()).isEqualTo(SttResult.Error(SttError.NoMatch))
    }

    @Test
    fun progressIsReportedFiveTimesASecondWithThePhraseLength() {
        val h = Direct({ "" })
        repeat(3) { h.chunk(200) } // silence: no phrase
        h.detector.speech = true
        repeat(2) { h.chunk(200) }
        assertThat(h.progress)
            .containsExactly(
                SttResult.Progress(0, 7000, false),
                SttResult.Progress(0, 7000, false),
                SttResult.Progress(0, 7000, false),
                // The 300 ms of audio kept from before the speech began count too.
                SttResult.Progress(500, 7000, true),
                SttResult.Progress(700, 7000, true),
            )
            .inOrder()
    }

    @Test
    fun aPhraseThatReachesTheLimitIsCutAtItsQuietestPointAndTheVadStartsOver() {
        val limit = 1000L
        val loud = ShortArray(16000) { 8000 }
        // Quiet 100 ms at 700 ms: in the second half, so that is where the cut goes.
        for (i in 11200 until 12800) loud[i] = 0
        val h = Direct({ "first part" }, phraseLimitMillis = limit)
        h.detector.speech = true
        h.session.acceptAudio(loud.copyOfRange(0, 8000))
        h.now = 500
        h.session.acceptAudio(loud.copyOfRange(8000, 16000))
        assertThat(h.partials)
            .containsExactly(
                SttResult.Partial("first part", stablePrefixLength = 10, endsSegment = true)
            )
        assertThat(h.detector.resets).isEqualTo(1)
        // The rest goes back to the VAD, and the ring starts from it.
        assertThat(h.detector.accepted.last()).isAtLeast(3000)
        assertThat(h.detector.accepted.last()).isAtMost(5000)
        assertThat(h.progress.last().phraseMillis).isAtLeast(180L)
        assertThat(h.progress.last().phraseMillis).isAtMost(320L)
    }

    @Test
    fun aFixedLiveIntervalIsKeptAndRaisedWhenDecodingCannotKeepUp() {
        var calls = 0
        lateinit var h: Direct
        var took = 0L
        h =
            Direct(
                {
                    calls++
                    h.now += took
                    "w"
                },
                liveUpdateMillis = 1200,
            )
        h.detector.speech = true
        for (t in 0..1000 step 200) h.chunk(200, t.toLong())
        assertThat(calls).isEqualTo(0) // not before 1200 ms, however fast the decode
        h.chunk(200, 1200)
        assertThat(calls).isEqualTo(1)
        took = 900 // the next decode is slow: twice that, 1800 ms, beats the 1200 ms setting
        for (t in 1400..2400 step 200) h.chunk(200, t.toLong())
        assertThat(calls).isEqualTo(2)
        for (t in 3300..4800 step 200) h.chunk(200, t.toLong())
        assertThat(calls).isEqualTo(2)
        h.chunk(200, 5100)
        assertThat(calls).isEqualTo(3)
    }

    @Test
    fun liveWordsOffDecodesOnlyAfterThePause() {
        var calls = 0
        val h =
            Direct(
                {
                    calls++
                    "done"
                },
                liveUpdateMillis = SttConfig.LIVE_OFF,
            )
        h.detector.speech = true
        repeat(20) { h.chunk(200) }
        assertThat(calls).isEqualTo(0)
        assertThat(h.partials).isEmpty()
        h.detector.segments += FloatArray(16000)
        h.chunk(200)
        assertThat(h.partials).containsExactly(SttResult.Partial("done", 4, endsSegment = true))
    }

    @Test
    fun sessionTuningClampsToWhatTheModelCanDo() {
        val high =
            SessionTuning.from(
                SttConfig(
                    phraseLimitMillis = 60_000,
                    pauseMillis = 9_000,
                    liveUpdateMillis = 99_999,
                )
            )
        assertThat(high.phraseLimitMillis).isEqualTo(8_000)
        assertThat(high.pauseSeconds).isEqualTo(1.5f)
        assertThat(high.liveUpdateMillis).isEqualTo(2_000)
        val low =
            SessionTuning.from(
                SttConfig(phraseLimitMillis = 100, pauseMillis = 0, liveUpdateMillis = -5)
            )
        assertThat(low.phraseLimitMillis).isEqualTo(3_000)
        assertThat(low.pauseSeconds).isEqualTo(0.3f)
        assertThat(low.liveUpdateMillis).isEqualTo(SttConfig.LIVE_OFF)
    }

    // --- Noise, forced cuts, bounds, duty ---

    @Test
    fun aClickThatTheVadDropsIsNeitherDecodedNorCutAndSpeechAfterItStillWorks() {
        val h = Direct({ "Thank you" })
        h.detector.speech = true
        h.chunk(200) // the click: the VAD reports speech ...
        h.detector.speech = false // ... and then none, with no segment
        repeat(40) { h.chunk(200) } // 8 s: more than the phrase limit
        assertThat(h.decoded).isEmpty()
        assertThat(h.partials).isEmpty()
        assertThat(h.detector.resets).isEqualTo(0)
        assertThat(h.progress.takeLast(20).all { it.phraseMillis == 0L && !it.speaking }).isTrue()
        // Real speech afterwards: its live copy starts from the pre-roll kept from before.
        h.detector.speech = true
        repeat(5) { h.chunk(200) }
        assertThat(h.decoded).containsExactly(LiveTuning.PRE_ROLL_SAMPLES + 5 * 3200)
    }

    @Test
    fun aClickAloneEndsWithNoMatch() {
        val h = Direct({ "Thank you" })
        h.detector.speech = true
        h.chunk(200)
        h.detector.speech = false
        repeat(20) { h.chunk(200) }
        h.session.finish()
        assertThat(h.decoded).isEmpty()
        assertThat(h.results.last()).isEqualTo(SttResult.Error(SttError.NoMatch))
    }

    @Test
    fun liveWordsAreNotStandInsForACutPartThatDecodesToNothing() {
        var mode = "live"
        val h = Direct({ if (mode == "live") "hello there" else "" }, phraseLimitMillis = 2000)
        h.detector.speech = true
        repeat(9) { h.chunk(200) } // live words are shown meanwhile
        assertThat(h.partials.map { it.text }).contains("hello there")
        mode = "empty"
        h.chunk(200) // 2 s: the phrase is cut, the part before decodes to nothing
        assertThat(h.partials.filter { it.endsSegment }).isEmpty()
        h.detector.segments += FloatArray(4000) // what the VAD makes of the rest: empty too
        h.session.finish()
        // Nothing of the old live words is committed again.
        assertThat(h.partials.filter { it.endsSegment }).isEmpty()
        assertThat(h.results.last()).isEqualTo(SttResult.Error(SttError.Unclear))
    }

    @Test
    fun liveWordsLongerThanASegmentCanHoldAreNotUsedForIt() {
        var final = false
        val h = Direct({ if (final) "" else "internationalization characteristics understanding" })
        h.detector.speech = true
        repeat(5) { h.chunk(200) }
        assertThat(h.partials).hasSize(1)
        final = true
        h.detector.segments += FloatArray(8000) // 0.5 s cannot have held all that
        h.chunk(200)
        assertThat(h.partials.filter { it.endsSegment }).isEmpty()
    }

    @Test
    fun theRecognizerIsNeverFedMoreThanNineSecondsAtTheEightSecondLimit() {
        val limit = 9 * 16000
        val h = Direct({ "w" }, phraseLimitMillis = 8000)
        h.chunk(400) // idle: the pre-roll
        h.detector.speech = true
        repeat(60) { h.chunk(200) } // 12 s of unbroken speech
        h.detector.segments += FloatArray(12 * 16000) // a VAD segment that came out too long
        h.chunk(200)
        h.detector.segments += FloatArray(25 * 16000)
        h.session.finish()
        assertThat(h.decoded).isNotEmpty()
        assertThat(h.decoded.max()).isAtMost(limit)
        assertThat(h.detector.resets).isAtLeast(1)
    }

    @Test
    fun aSplitDropsAWordRepeatedByAMidWordCut() {
        assertThat(joinHalves("so the brothel", "brothel owner")).isEqualTo("so the brothel owner")
        assertThat(joinHalves("hello.", "Hello again")).isEqualTo("hello. again")
        assertThat(joinHalves("one two", "three")).isEqualTo("one two three")
        assertThat(joinHalves("", "three")).isEqualTo("three")
        val samples = 16000
        val segment = FloatArray(6 * samples) { 0.5f }
        for (i in (3.2 * samples).toInt() until (3.3 * samples).toInt()) segment[i] = 0.001f
        var n = 0
        val h =
            Direct({
                if (it.size > 4 * samples) "" else if (n++ == 0) "the brothel" else "brothel"
            })
        h.detector.segments += segment
        h.session.finish()
        // "brothel" comes out of both halves; it is said once.
        assertThat(h.results.last()).isEqualTo(SttResult.Final("the brothel"))
    }

    @Test
    fun liveDecodingIsSkippedWhileItUsesTooMuchOfTheLastSeconds() {
        val schedule = LiveSchedule(0)
        schedule.restart(0)
        schedule.decoded(16000, 0, 3000) // 3 s of work: the next is 3 s later
        assertThat(schedule.due(100_000, 3500)).isFalse() // interval not over
        assertThat(schedule.due(100_000, 6000)).isFalse() // interval over, but 50% busy
        assertThat(schedule.due(100_000, 6700)).isTrue() // 38% of the last 6 s
    }

    @Test
    fun theAutoIntervalGrowsToThreeSecondsForASlowPhone() {
        val schedule = LiveSchedule(0)
        schedule.restart(0)
        schedule.decoded(16000, 0, 1400) // 2 x 1.4 s
        assertThat(schedule.due(100_000, 1400 + 2799)).isFalse()
        assertThat(schedule.due(100_000, 1400 + 2800)).isTrue()
        val slower = LiveSchedule(0)
        slower.decoded(16000, 0, 10)
        slower.decoded(16000, 20_000, 22_000)
        assertThat(slower.due(100_000, 22_000 + 2999)).isFalse()
        assertThat(slower.due(100_000, 22_000 + 3000)).isTrue()
    }

    @Test
    fun cancelDuringALiveDecodeEmitsNothing() {
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val results = CopyOnWriteArrayList<SttResult>()
        val released = java.util.concurrent.atomic.AtomicBoolean()
        val times = java.util.concurrent.ConcurrentLinkedQueue(listOf(0L, 800L, 800L))
        val detector = ScriptedDetector().apply { speech = true }
        val session =
            SegmentedDictation(
                listener = { results += it },
                create = {
                    SpeechComponents(
                        detector,
                        object : SegmentRecognizer {
                            override fun recognize(segment: FloatArray): String {
                                started.countDown()
                                gate.await(5, TimeUnit.SECONDS)
                                return "live words"
                            }

                            override fun release() {
                                released.set(true)
                            }
                        },
                    )
                },
                clock = { times.poll() ?: 1_000_000L },
            )
        session.acceptAudio(ShortArray(16000))
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
        session.cancel()
        gate.countDown()
        waitUntil { released.get() }
        assertThat(results).isEmpty()
    }

    @Test
    fun stopWaitsBehindAtMostOneLiveDecode() {
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val done = CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        // The clock reads 0, 800, 800 for the first decode, and a late time afterwards: any later
        // live decode would be due, so it would show.
        val times = java.util.concurrent.ConcurrentLinkedQueue(listOf(0L, 800L, 800L))
        val detector = ScriptedDetector().apply { speech = true }
        val session =
            SegmentedDictation(
                listener = { if (it is SttResult.Final || it is SttResult.Error) done.countDown() },
                create = {
                    SpeechComponents(
                        detector,
                        object : SegmentRecognizer {
                            override fun recognize(segment: FloatArray): String {
                                calls.incrementAndGet()
                                started.countDown()
                                gate.await(5, TimeUnit.SECONDS)
                                return "w"
                            }

                            override fun release() = Unit
                        },
                    )
                },
                clock = { times.poll() ?: 1_000_000L },
            )
        session.acceptAudio(ShortArray(16000))
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
        session.acceptAudio(ShortArray(3200)) // queued behind the decode
        session.acceptAudio(ShortArray(3200))
        session.finish()
        gate.countDown()
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(calls.get()).isEqualTo(1)
    }
}
