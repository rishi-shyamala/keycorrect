package dev.keycorrect.core.decoder.rerank

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
        lmWeight: Float = NeuralReranker.LM_WEIGHT,
        deadlineMs: Long = NeuralReranker.RERANK_DEADLINE_MS,
        maxTexts: Int = NeuralReranker.MAX_TEXTS,
        contextWords: Int = NeuralReranker.CONTEXT_WORDS,
        poolSize: Int = 0,
        scorer: CandidateScorer,
    ) =
        NeuralReranker(
            scorer,
            pool,
            { strength },
            lmWeight = lmWeight,
            deadlineMs = deadlineMs,
            maxTexts = maxTexts,
            contextWords = contextWords,
            poolSize = poolSize,
        )

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

    @Test
    fun theTunablesAreConstructorParameters() {
        val seen = mutableListOf<Triple<String, List<String>, Long>>()
        val r =
            reranker(lmWeight = 2f, deadlineMs = 900, maxTexts = 2, contextWords = 3) { ctx, c, t ->
                seen += Triple(ctx, c, t)
                floatArrayOf(-1f, -3f)
            }
        var bonus: Map<String, Float>? = null
        r.run(request(before = "a b c d e ")) {
            bonus = it.lmBonus
            redecoded
        }
        assertThat(seen.single().first).isEqualTo("c d e")
        assertThat(seen.single().second).containsExactly("helli", "hello").inOrder()
        assertThat(seen.single().third).isEqualTo(900L)
        assertThat(bonus!!["helli"]).isWithin(1e-5f).of(2f * 1f)
        assertThat(bonus!!["hello"]).isWithin(1e-5f).of(2f * -1f)
    }

    private val wide = result("helli", "hello", "help", "hell", "hallo", "hull")

    @Test
    fun aPoolRedecodesWiderFirstAndScoresTheWiderResult() {
        val requests = mutableListOf<PredictRequest>()
        var texts: List<String>? = null
        val r =
            reranker(poolSize = 8) { _, c, _ ->
                texts = c
                FloatArray(c.size) { -it.toFloat() }
            }
        val out = r.run {
            requests += it
            if (it.lmBonus.isEmpty()) wide else redecoded
        }
        assertThat(out).isSameInstanceAs(redecoded)
        assertThat(requests).hasSize(2)
        // First the wide decode without a bonus, then the final one with the original size.
        assertThat(requests[0].maxCandidates).isEqualTo(8)
        assertThat(requests[0].lmBonus).isEmpty()
        assertThat(requests[1].maxCandidates).isEqualTo(request().maxCandidates)
        assertThat(requests[1].lmBonus.keys)
            .containsExactly("helli", "hello", "help", "hell", "hallo", "hull")
        assertThat(texts)
            .containsExactly("helli", "hello", "help", "hell", "hallo", "hull")
            .inOrder()
    }

    @Test
    fun noPoolScoresOnlyTheClassicResultAndDecodesOnce() {
        var texts: List<String>? = null
        val requests = mutableListOf<PredictRequest>()
        val r =
            reranker(poolSize = 0) { _, c, _ ->
                texts = c
                FloatArray(c.size)
            }
        r.run {
            requests += it
            redecoded
        }
        assertThat(texts).containsExactly("helli", "hello", "help").inOrder()
        assertThat(requests).hasSize(1)
    }

    @Test
    fun aPoolNotWiderThanTheRequestAddsNoDecode() {
        val requests = mutableListOf<PredictRequest>()
        val r = reranker(poolSize = request().maxCandidates) { _, c, _ -> FloatArray(c.size) }
        r.run {
            requests += it
            redecoded
        }
        assertThat(requests).hasSize(1)
    }

    @Test
    fun aPoolIsStillCappedAtMaxTexts() {
        var texts: List<String>? = null
        val r =
            reranker(poolSize = 8, maxTexts = 4) { _, c, _ ->
                texts = c
                FloatArray(c.size)
            }
        r.run { if (it.lmBonus.isEmpty()) wide else redecoded }
        assertThat(texts).containsExactly("helli", "hello", "help", "hell").inOrder()
    }

    @Test
    fun aPoolDecodeThatTurnsStaleStopsBeforeTheScorer() {
        val calls = AtomicInteger()
        val r =
            reranker(poolSize = 8) { _, c, _ ->
                calls.incrementAndGet()
                FloatArray(c.size)
            }
        var stale = false
        val out =
            r.run(stale = { stale }) {
                stale = true
                wide
            }
        assertThat(out).isNull()
        assertThat(calls.get()).isEqualTo(0)
    }

    @Test
    fun aPoolDoesNotDecodeWhileTheScorerIsStillBusy() {
        val release = CountDownLatch(1)
        val r =
            reranker(poolSize = 8) { _, c, _ ->
                release.await(5, TimeUnit.SECONDS)
                FloatArray(c.size)
            }
        assertThat(r.run { wide }).isNull() // deadline; the call keeps running
        val decodes = AtomicInteger()
        assertThat(
                r.run {
                    decodes.incrementAndGet()
                    wide
                }
            )
            .isNull()
        release.countDown()
        assertThat(decodes.get()).isEqualTo(0)
    }

    // ------------------------------------------------------------------ phrases (GENERATE)

    private val flatScorer = CandidateScorer { _, c, _ -> FloatArray(c.size) { -it.toFloat() } }

    private fun phraseReranker(enabled: Boolean, generator: PhraseGenerator) =
        NeuralReranker(flatScorer, pool, { 1f }, generator, phrasesEnabled = { enabled })

    @Test
    fun aGeneratedPhraseCompletesTheTopSuggestion() {
        var seen: Pair<String, String>? = null
        val r =
            phraseReranker(true) { context, top, _ ->
                    seen = context to top
                    " world, how are you"
                }
                .run()
        assertThat(seen).isEqualTo("thank you" to "redecoded")
        assertThat(r!!.phrase!!.text).isEqualTo("redecoded world")
        assertThat(r.phrase!!.isPhrase).isTrue()
        assertThat(r.phrase!!.source).isEqualTo(CandidateSource.Engine)
    }

    @Test
    fun phrasesOffOrNoAnswerKeepTheRerankedResult() {
        val calls = AtomicInteger()
        val off = phraseReranker(false) { _, _, _ -> "x".also { calls.incrementAndGet() } }.run()
        assertThat(off).isSameInstanceAs(redecoded)
        assertThat(calls.get()).isEqualTo(0)
        val none = phraseReranker(true) { _, _, _ -> null }.run()
        assertThat(none).isSameInstanceAs(redecoded)
        val junk = phraseReranker(true) { _, _, _ -> "!!! 42" }.run()
        assertThat(junk!!.phrase).isNull()
    }

    @Test
    fun phraseWordsStopAtPunctuationAndCapAtThree() {
        assertThat(NeuralReranker.phraseWords(" you are so kind today")).isEqualTo("you are so")
        assertThat(NeuralReranker.phraseWords("you. Then")).isEqualTo("you")
        assertThat(NeuralReranker.phraseWords("don't worry")).isEqualTo("don't worry")
        assertThat(NeuralReranker.phraseWords("  ")).isNull()
        assertThat(NeuralReranker.phraseWords("2 cats")).isNull()
    }
}
