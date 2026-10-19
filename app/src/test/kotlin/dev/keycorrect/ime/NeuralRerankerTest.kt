package dev.keycorrect.ime

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.engineapi.Candidate
import dev.keycorrect.core.engineapi.CandidateSource
import dev.keycorrect.core.engineapi.ComposingInput
import dev.keycorrect.core.engineapi.KeyLayoutModel
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.PredictResult
import dev.keycorrect.core.engineapi.TypingContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Test

class NeuralRerankerTest {
    private val pool = Executors.newSingleThreadExecutor()

    @After
    fun tearDown() {
        pool.shutdownNow()
    }

    private fun dict(text: String) = Candidate(text, -1f, CandidateSource.Dictionary)

    private fun request(
        before: String = "thank you ",
        allowed: Boolean = true,
        bonus: Map<String, Float> = emptyMap(),
    ) =
        PredictRequest(
            TypingContext(before),
            ComposingInput("helli", List(5) { null }),
            KeyLayoutModel(1, emptyList()),
            engineAllowed = allowed,
            lmBonus = bonus,
            sequence = 7,
        )

    private fun result(
        literal: String? = "helli",
        vararg candidates: String = arrayOf("hello", "help"),
    ) =
        PredictResult(
            candidates = candidates.map(::dict),
            literal = literal?.let { Candidate(it, -2f, CandidateSource.Literal) },
            autocorrect = null,
            sequence = 7,
        )

    private val redecoded = PredictResult(listOf(dict("redecoded")), null, null, sequence = 7)

    private fun reranker(
        strength: Float = 1f,
        scorer: CandidateScorer,
    ) = NeuralReranker(scorer, pool, { strength })

    private fun NeuralReranker.run(
        request: PredictRequest = request(),
        result: PredictResult = result(),
        stale: () -> Boolean = { false },
        redecode: (PredictRequest) -> PredictResult = { redecoded },
    ) = refine(request, result, stale, redecode)

    @Test
    fun bonusIsStrengthTimesWeightTimesCentredLogProb() {
        val seen = mutableListOf<Triple<String, List<String>, Long>>()
        val r =
            reranker(strength = 0.5f) { ctx, cands, timeout ->
                seen += Triple(ctx, cands, timeout)
                floatArrayOf(-4f, -2f, -9f) // helli, hello, help: mean -5
            }
        var bonus: Map<String, Float>? = null
        val out = r.run {
            bonus = it.lmBonus
            redecoded
        }
        assertThat(out).isSameInstanceAs(redecoded)
        assertThat(seen.single().second).containsExactly("helli", "hello", "help").inOrder()
        assertThat(seen.single().third).isEqualTo(NeuralReranker.RERANK_DEADLINE_MS)
        assertThat(bonus!!.keys).containsExactly("helli", "hello", "help")
        assertThat(bonus!!["helli"]).isWithin(1e-5f).of(0.5f * 1f * 1f)
        assertThat(bonus!!["hello"]).isWithin(1e-5f).of(0.5f * 3f)
        assertThat(bonus!!["help"]).isWithin(1e-5f).of(0.5f * -4f)
        assertThat(bonus!!.values.sum()).isWithin(1e-5f).of(0f)
    }

    @Test
    fun theRedecodeKeepsTheRestOfTheRequest() {
        val r = reranker { _, c, _ -> FloatArray(c.size) { -it.toFloat() } }
        var seen: PredictRequest? = null
        r.run(request()) {
            seen = it
            redecoded
        }
        assertThat(seen!!.copy(lmBonus = emptyMap())).isEqualTo(request())
    }

    @Test
    fun strengthZeroDoesNothingAndNeverCallsTheScorer() {
        val calls = AtomicInteger()
        val r =
            reranker(strength = 0f) { _, c, _ ->
                calls.incrementAndGet()
                FloatArray(c.size)
            }
        assertThat(r.run()).isNull()
        assertThat(calls.get()).isEqualTo(0)
    }

    @Test
    fun skipsWithoutEngineAllowedOrCandidates() {
        val calls = AtomicInteger()
        val r = reranker { _, c, _ ->
            calls.incrementAndGet()
            FloatArray(c.size)
        }
        assertThat(r.run(request(allowed = false))).isNull()
        assertThat(r.run(result = result(literal = "helli", candidates = emptyArray()))).isNull()
        assertThat(r.run(result = result(literal = null, candidates = emptyArray()))).isNull()
        assertThat(calls.get()).isEqualTo(0)
    }

    @Test
    fun nextWordPredictionsWithoutALiteralAreScored() {
        var texts: List<String>? = null
        val r = reranker { _, c, _ ->
            texts = c
            FloatArray(c.size)
        }
        val out = r.run(result = result(literal = null, "you", "all"))
        assertThat(out).isNotNull()
        assertThat(texts).containsExactly("you", "all").inOrder()
    }

    @Test
    fun textsAreDistinctAndCappedAtSixteen() {
        var texts: List<String>? = null
        val r = reranker { _, c, _ ->
            texts = c
            FloatArray(c.size)
        }
        val many = Array(30) { "w${it % 25}" } // 25 distinct
        r.run(result = result("helli", *many))
        assertThat(texts).hasSize(NeuralReranker.MAX_TEXTS)
        assertThat(texts!!.toSet()).hasSize(NeuralReranker.MAX_TEXTS)
        assertThat(texts!!.first()).isEqualTo("helli")
        r.run(result = result("hello", "hello", "help", "help"))
        assertThat(texts).containsExactly("hello", "help").inOrder()
    }

    @Test
    fun theContextIsTheLastFortyWordsWithoutTrailingSpace() {
        var context: String? = null
        val r = reranker { ctx, c, _ ->
            context = ctx
            FloatArray(c.size)
        }
        val before = (1..100).joinToString(" ") { "w$it" } + "  "
        r.run(request(before = before))
        val words = context!!.split(" ")
        assertThat(words).hasSize(40)
        assertThat(words.first()).isEqualTo("w61")
        assertThat(words.last()).isEqualTo("w100")
        r.run(request(before = "Hi there. "))
        assertThat(context).isEqualTo("Hi there.")
        r.run(request(before = ""))
        assertThat(context).isEqualTo("")
    }

    @Test
    fun noAnswerFromTheScorerKeepsTheClassicResult() {
        assertThat(reranker { _, _, _ -> null }.run()).isNull()
        assertThat(reranker { _, _, _ -> floatArrayOf(1f) }.run()).isNull() // wrong size
        assertThat(reranker { _, c, _ -> FloatArray(c.size) { Float.NaN } }.run()).isNull()
        assertThat(reranker { _, _, _ -> error("boom") }.run()).isNull()
    }

    @Test
    fun givesUpAtTheDeadline() {
        val release = CountDownLatch(1)
        val r = reranker { _, c, _ ->
            release.await(5, TimeUnit.SECONDS)
            FloatArray(c.size)
        }
        val redecodes = AtomicInteger()
        val start = System.nanoTime()
        val out = r.run {
            redecodes.incrementAndGet()
            redecoded
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        release.countDown()
        assertThat(out).isNull()
        assertThat(redecodes.get()).isEqualTo(0)
        assertThat(ms).isAtLeast(NeuralReranker.RERANK_DEADLINE_MS - 5)
        assertThat(ms).isLessThan(400L)
    }

    @Test
    fun givesUpEarlyWhenTheInputMovesOn() {
        val release = CountDownLatch(1)
        val r = reranker { _, c, _ ->
            release.await(5, TimeUnit.SECONDS)
            FloatArray(c.size)
        }
        var polls = 0
        val start = System.nanoTime()
        val out = r.run(stale = { ++polls > 2 })
        val ms = (System.nanoTime() - start) / 1_000_000
        release.countDown()
        assertThat(out).isNull()
        assertThat(ms).isLessThan(NeuralReranker.RERANK_DEADLINE_MS)
    }

    @Test
    fun aResultThatTurnsStaleAfterTheRedecodeIsDropped() {
        val r = reranker { _, c, _ -> FloatArray(c.size) }
        var stale = false
        val out =
            r.run(stale = { stale }) {
                stale = true
                redecoded
            }
        assertThat(out).isNull()
    }

    @Test
    fun onlyOneScorerCallIsInFlight() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val r = reranker { _, c, _ ->
            calls.incrementAndGet()
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            FloatArray(c.size)
        }
        assertThat(r.run()).isNull() // deadline: the call is still running
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue()
        val start = System.nanoTime()
        assertThat(r.run()).isNull() // skipped at once
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(20L)
        assertThat(calls.get()).isEqualTo(1)

        release.countDown()
        val idle = CountDownLatch(1)
        pool.execute { idle.countDown() } // the single scorer thread has finished the old call
        assertThat(idle.await(1, TimeUnit.SECONDS)).isTrue()
        assertThat(r.run()).isSameInstanceAs(redecoded)
        assertThat(calls.get()).isEqualTo(2)
    }

    @Test
    fun aRejectedScorerTaskIsNotAnError() {
        val closed = Executors.newSingleThreadExecutor().also { it.shutdown() }
        val r = NeuralReranker({ _, c, _ -> FloatArray(c.size) }, closed, { 1f })
        assertThat(r.run()).isNull()
    }

    @Test
    fun lastWordsCutsAtWordBoundaries() {
        assertThat(NeuralReranker.lastWords("a b c d", 2)).isEqualTo("c d")
        assertThat(NeuralReranker.lastWords("a  b\n c ", 3)).isEqualTo("a  b\n c")
        assertThat(NeuralReranker.lastWords("   ", 3)).isEqualTo("")
    }
}
