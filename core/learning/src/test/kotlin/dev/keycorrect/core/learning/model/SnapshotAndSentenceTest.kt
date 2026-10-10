package dev.keycorrect.core.learning.model

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.learning.LearningUpdate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test

class SnapshotAndSentenceTest {
    @Test
    fun publishedSnapshotsAreImmutable() {
        val m = PersonalModel(testConfig())
        m.applyAt(0, *Array(6) { used("hello", "say") })
        val s1 = m.snapshot()
        val p1 = s1.userLogProb(listOf("say"), "hello")!!
        m.applyAt(1, *Array(60) { used("hello", "say") }, used("newword", w = 5f))
        m.applyAt(2, LearningUpdate.BlockWord("hello"))
        assertThat(s1.userLogProb(listOf("say"), "hello")).isEqualTo(p1)
        assertThat(s1.userLogProb(emptyList(), "newword")).isNull()
        assertThat(s1.isBlocked("hello")).isFalse()
        val s2 = m.snapshot()
        assertThat(s2.version).isGreaterThan(s1.version)
        assertThat(s2.isBlocked("hello")).isTrue()
        assertThat(s2.userLogProb(emptyList(), "newword")).isNotNull()
        // The live view always reads the newest snapshot.
        assertThat(m.personalization.isBlocked("hello")).isTrue()
    }

    @Test
    fun publishingIsThrottledButUrgentUpdatesPassThrough() {
        val cfg = PersonalModelConfig(publishEveryUpdates = 100, publishMinIntervalMillis = 10_000)
        val m = PersonalModel(cfg)
        m.applyAt(0, used("a")) // first apply publishes
        val v0 = m.snapshot().version
        m.applyAt(100, used("b"), used("c"))
        assertThat(m.snapshot().version).isEqualTo(v0)
        m.applyAt(200, LearningUpdate.BlockWord("x")) // urgent
        assertThat(m.snapshot().version).isGreaterThan(v0)
        val v1 = m.snapshot().version
        m.applyAt(300, *Array(100) { used("d") }) // count threshold
        assertThat(m.snapshot().version).isGreaterThan(v1)
        val v2 = m.snapshot().version
        m.applyAt(400, used("e"))
        assertThat(m.snapshot().version).isEqualTo(v2)
        m.applyAt(20_000, used("f")) // interval elapsed
        assertThat(m.snapshot().version).isGreaterThan(v2)
        m.applyAt(20_001, used("g"))
        val v3 = m.snapshot().version
        m.publishNow()
        assertThat(m.snapshot().version).isGreaterThan(v3)
    }

    @Test
    fun concurrentReadersNeverSeeTornState() {
        val m = PersonalModel(testConfig())
        m.applyAt(0, *Array(10) { used("steady", "base") })
        val stop = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val ready = CountDownLatch(3)
        val readers =
            List(3) {
                Thread {
                    ready.countDown()
                    var last = 0L
                    try {
                        while (!stop.get()) {
                            val s = m.snapshot()
                            check(s.version >= last)
                            last = s.version
                            val p = s.userLogProb(listOf("base"), "steady")
                            check(p == null || p <= 0f)
                            s.correctionBias("a", "b", "x|y")
                            s.touchOffset("k", 'a')
                            s.touchStatistics()
                            m.personalization.rankerScore(feat())
                        }
                    } catch (t: Throwable) {
                        failure.set(t)
                    }
                }
            }
        readers.forEach { it.start() }
        ready.await()
        for (i in 1..3000) {
            m.applyAt(
                i.toLong(),
                used("w${i % 50}", "base"),
                LearningUpdate.CorrectionEvidence("t${i % 7}", "u", "b|f", 0.5f),
                LearningUpdate.TouchEvidence(
                    "k",
                    listOf(dev.keycorrect.core.learning.TouchSample('a', 0.1f, 0f)),
                ),
            )
        }
        stop.set(true)
        readers.forEach { it.join(5_000) }
        assertThat(failure.get()).isNull()
    }

    // ---- sentence buffer ----

    @Test
    fun sentencesAssembleInOrderPerSession() {
        val b = SentenceBuffer()
        b.recordSentenceWord(1, "Hello", false, 10)
        b.recordSentenceWord(2, "Other", false, 11)
        b.recordSentenceWord(1, "world", true, 12)
        b.recordSentenceWord(2, "thing", true, 13)
        b.recordSentenceWord(1, "Still", false, 14)
        assertThat(b.recentSentences(20).map { it.text })
            .containsExactly("Hello world", "Other thing")
            .inOrder()
        b.closeSession(1, 15)
        assertThat(b.recentSentences(20).map { it.text }.last()).isEqualTo("Still")
        assertThat(b.recentSentences(20, limit = 1)).hasSize(1)
    }

    @Test
    fun sentencesExpireAfterSevenDays() {
        val b = SentenceBuffer()
        b.recordSentenceWord(1, "old", true, 0)
        b.recordSentenceWord(1, "fresh", true, 6 * DAY_MS)
        assertThat(b.recentSentences(6 * DAY_MS).map { it.text })
            .containsExactly("old", "fresh")
            .inOrder()
        assertThat(b.recentSentences(7 * DAY_MS + 1).map { it.text }).containsExactly("fresh")
        b.recordSentenceWord(1, "later", true, 14 * DAY_MS) // writing prunes too
        assertThat(b.size()).isEqualTo(1)
    }

    @Test
    fun sentenceBufferCapsClearAndDisable() {
        val b = SentenceBuffer(SentenceBufferConfig(maxSentences = 3, maxSentenceWords = 2))
        for (i in 1..10) b.recordSentenceWord(1, "w$i", true, i.toLong())
        assertThat(b.recentSentences(20).map { it.text })
            .containsExactly("w8", "w9", "w10")
            .inOrder()
        b.recordSentenceWord(1, "a", false, 30)
        b.recordSentenceWord(1, "b", false, 31) // hits maxSentenceWords -> closes
        assertThat(b.recentSentences(40).last().text).isEqualTo("a b")
        b.clear()
        assertThat(b.size()).isEqualTo(0)

        val chars = SentenceBuffer(SentenceBufferConfig(maxTotalChars = 10))
        chars.recordSentenceWord(1, "123456", true, 1)
        chars.recordSentenceWord(1, "abcdef", true, 2)
        assertThat(chars.recentSentences(3).map { it.text }).containsExactly("abcdef")

        val off = SentenceBuffer(SentenceBufferConfig(enabled = false))
        off.recordSentenceWord(1, "x", true, 1)
        assertThat(off.size()).isEqualTo(0)
    }

    @Test
    fun sentenceRowsRoundTripAndAreForgettable() {
        val b = SentenceBuffer()
        b.recordSentenceWord(1, "a", true, 100)
        b.recordSentenceWord(2, "b", true, 200)
        val rows = b.exportRows()
        val c = SentenceBuffer()
        c.importRows(rows)
        assertThat(c.exportRows()).isEqualTo(rows)
        assertThat(c.forgetSince(150)).isEqualTo(1)
        assertThat(c.recentSentences(300).map { it.text }).containsExactly("a")
    }

    @Test
    fun modelClearWipesEverything() {
        val m = PersonalModel(testConfig())
        m.applyAt(0, *Array(6) { used("hello") }, LearningUpdate.BlockWord("x"))
        m.recordSentenceWord(1, "hi", true, 0)
        m.clear()
        assertThat(m.snapshot().userLogProb(emptyList(), "hello")).isNull()
        assertThat(m.snapshot().isBlocked("x")).isFalse()
        assertThat(m.sentences.size()).isEqualTo(0)
        assertThat(m.export().ngrams).isEmpty()
    }
}
