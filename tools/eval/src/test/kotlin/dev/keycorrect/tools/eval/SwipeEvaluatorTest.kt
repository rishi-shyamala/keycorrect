package dev.keycorrect.tools.eval

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.engineapi.Candidate
import dev.keycorrect.core.engineapi.CandidateSource
import dev.keycorrect.core.engineapi.GestureRequest
import dev.keycorrect.core.engineapi.PredictResult
import org.junit.Test

class SwipeEvaluatorTest {
    private val keyboard = EvalKeyboard()
    private val generator = SwipeGenerator(keyboard, SwipeConfig())

    private fun result(vararg words: String): PredictResult {
        val c = words.mapIndexed { i, w -> Candidate(w, -i.toFloat(), CandidateSource.Dictionary) }
        return PredictResult(c, null, c.firstOrNull())
    }

    private fun sentences(vararg s: String) = s.map { Corpus.parse(it, null, NameMode.Skip) }

    private fun evaluator(oracle: Boolean = false) =
        SwipeEvaluator(keyboard, generator, seed = 7, oracleContext = oracle)

    /** Answers call i with script[i]; records the requests it saw. */
    private class Script(private val script: List<List<String>>) : GestureDecoder {
        val requests = ArrayList<GestureRequest>()

        override fun decode(request: GestureRequest): PredictResult {
            val words = script[requests.size]
            requests.add(request)
            val c = words.mapIndexed { i, w ->
                Candidate(w, -i.toFloat(), CandidateSource.Dictionary)
            }
            return PredictResult(c, null, c.firstOrNull())
        }
    }

    @Test
    fun countsTop1Top3NoCandidateAndWrongCommits() {
        val decoder =
            Script(
                listOf(
                    listOf("the"), // top-1
                    listOf("cap", "Cat", "car"), // top-3 only (case-insensitive)
                    listOf(), // no candidate
                    listOf("mat", "sit", "set"), // miss
                    listOf("dog", "x", "y"), // top-1
                )
            )
        val r = evaluator().evaluate(sentences("The cat sat. Dog"), decoder)
        // The sentence has 4 words: the, cat, sat, Dog -- the fifth scripted answer is unused.
        assertThat(r.words).isEqualTo(4)
        assertThat(r.top1).isEqualTo(1)
        assertThat(r.top3).isEqualTo(2)
        assertThat(r.noCandidate).isEqualTo(1)
        assertThat(r.wrongCommits).isEqualTo(2)
        assertThat(r.top1Pct).isWithin(1e-9).of(25.0)
        assertThat(r.latency.count).isEqualTo(4)
        assertThat(r.misses.map { it.target }).containsExactly("cat", "sat", "Dog").inOrder()
        assertThat(r.misses[0].top3Hit).isTrue()
        assertThat(r.misses[1].top).isEmpty()
    }

    @Test
    fun skipsNumbersNamesAndHyphenatedTokens() {
        val decoder = Script(List(5) { listOf("x") })
        val r = evaluator().evaluate(sentences("Tom has 3 well-known cats"), decoder)
        // Only "has" and "cats" are all letters; Tom is a skipped name.
        assertThat(r.words).isEqualTo(2)
        assertThat(decoder.requests).hasSize(2)
    }

    @Test
    fun contextIsTheCommittedTextOrTheTruth() {
        val answers = listOf(listOf("hi"), listOf("there"), listOf("bob"))
        val committed = Script(answers)
        evaluator().evaluate(sentences("Hello there friend"), committed)
        assertThat(committed.requests.map { it.context.before })
            .containsExactly("", "hi ", "hi there ")
            .inOrder()
        val oracle = Script(answers)
        evaluator(oracle = true).evaluate(sentences("Hello there friend"), oracle)
        assertThat(oracle.requests.map { it.context.before })
            .containsExactly("", "Hello ", "Hello there ")
            .inOrder()
    }

    @Test
    fun noCandidateCommitsTheTrueWord() {
        val decoder = Script(listOf(listOf(), listOf("a")))
        evaluator().evaluate(sentences("Hello there"), decoder)
        assertThat(decoder.requests[1].context.before).isEqualTo("Hello ")
    }

    @Test
    fun gesturesAreDeterministicAndIndependentOfTheEngine() {
        val a = Script(List(3) { listOf("zzz") })
        val b = Script(List(3) { listOf("hello") })
        evaluator().evaluate(sentences("Hello there friend"), a)
        evaluator().evaluate(sentences("Hello there friend"), b)
        assertThat(a.requests.map { it.gesture }).isEqualTo(b.requests.map { it.gesture })
        assertThat(a.requests[0].gesture).isNotEqualTo(a.requests[1].gesture)
        assertThat(a.requests[0].layout).isSameInstanceAs(keyboard.layout)
        assertThat(a.requests[0].maxCandidates).isEqualTo(3)
    }

    @Test
    fun warmupIsNotScoredAndMissLimitCapsTheFile() {
        val calls = ArrayList<Int>()
        val decoder = GestureDecoder {
            calls.add(1)
            result("nope")
        }
        val r =
            evaluator()
                .evaluate(
                    sentences("one two", "three four"),
                    decoder,
                    warmupSentences = 1,
                    missLimit = 1,
                )
        assertThat(calls).hasSize(2 + 4)
        assertThat(r.words).isEqualTo(4)
        assertThat(r.misses).hasSize(1)
        assertThat(r.missesCsv().lines().filter { it.isNotEmpty() }).hasSize(2)
    }

    @Test
    fun reportListsTheHeadlineNumbers() {
        val r = evaluator().evaluate(sentences("go on"), GestureDecoder { result("go") })
        val text = r.text("Results: swipe")
        assertThat(text).contains("words evaluated   2")
        assertThat(text).contains("top-1             50.00 %")
        assertThat(text).contains("p99")
        assertThat(Json.write(r.toMap())).contains("\"top3\"")
    }
}
