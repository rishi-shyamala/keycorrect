package dev.keycorrect.feature.voicesherpa

import com.google.common.truth.Truth.assertThat
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
                SttResult.Partial("hello", 5),
                SttResult.Partial("hello world", 11),
                SttResult.Partial("hello world again", 17),
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
            .containsExactly(SttResult.Partial("hi", 2), SttResult.Final("hi"))
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
}
