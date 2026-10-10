package dev.keycorrect.core.decoder

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.dictionary.Dictionary
import dev.keycorrect.core.dictionary.DictionaryData
import dev.keycorrect.core.dictionary.DictionaryMetadata
import dev.keycorrect.core.dictionary.DictionaryWriter
import dev.keycorrect.core.dictionary.WordEntry
import dev.keycorrect.core.engineapi.CandidateSource
import dev.keycorrect.core.engineapi.FieldClass
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.PredictResult
import dev.keycorrect.core.engineapi.TypingContext
import dev.keycorrect.core.learning.RankingFeatures
import java.nio.ByteBuffer
import kotlin.random.Random
import org.junit.Test

/** docs/PLAN.md §6.3 row 22: no runner-up margin for unknown literals; random pick on a tie. */
class TieRuleTest {
    /** "tset" is one transposition from "test" and one deletion from "set". */
    private fun tieDictionary(setCount: Long): Dictionary {
        val counts =
            linkedMapOf(
                "the" to 50_000L,
                "of" to 40_000L,
                "test" to 2_000L,
                "set" to setCount,
                "zebra" to 1L,
                "quixotic" to 1L,
            )
        val data =
            DictionaryData(
                DictionaryMetadata("en-US", "2026-10-09", 1, "tie test"),
                counts.map { (w, c) -> WordEntry.fromCount(w, c, 1_000_000L, 0) },
                emptyList(),
                emptyList(),
            )
        return Dictionary.open(ByteBuffer.wrap(DictionaryWriter.write(data, true)))
    }

    private fun decode(engine: ClassicEngine, typed: String = "tset"): PredictResult =
        engine.predictNow(
            PredictRequest(TypingContext(), TestKeyboard.typed(typed), TestKeyboard.layout)
        )

    // set's count that makes "set" and "test" score within ~0.01 of each other.
    private val tied = tieDictionary(14_778)

    @Test
    fun nearTiedCandidatesForANonWordAreStillCorrected() {
        val r = decode(ClassicEngine(tied, random = Random(1)))
        assertThat(r.literal?.text).isEqualTo("tset")
        assertThat(r.candidates.map { it.text }).containsAtLeast("test", "set")
        assertThat(r.autocorrect?.text).isIn(listOf("test", "set"))
        val other = listOf("test", "set").first { it != r.autocorrect?.text }
        assertThat(r.autocorrectRunnerUp?.text).isEqualTo(other)
    }

    @Test
    fun exactTieUsesTheInjectedRandomDeterministically() {
        fun picks(seed: Int): List<String> {
            val e = ClassicEngine(tied, random = Random(seed))
            return List(40) { decode(e).autocorrect!!.text }
        }
        assertThat(picks(7)).isEqualTo(picks(7))
        val p = picks(7)
        assertThat(p.toSet()).containsExactly("test", "set")
        assertThat(picks(8)).isNotEqualTo(p)
    }

    @Test
    fun closeButNotTiedPicksTheBestAndExposesTheRunnerUp() {
        // set at ~0.4 below test: outside tieEpsilon, inside the old 0.7 margin.
        val e = ClassicEngine(tieDictionary(12_000), random = Random(3))
        repeat(10) {
            val r = decode(e)
            assertThat(r.autocorrect?.text).isEqualTo("test")
            assertThat(r.autocorrectRunnerUp?.text).isEqualTo("set")
        }
    }

    @Test
    fun clearWinnerHasNoRunnerUp() {
        val r = decode(ClassicEngine(tieDictionary(1_000)))
        assertThat(r.autocorrect?.text).isEqualTo("test")
        assertThat(r.autocorrectRunnerUp).isNull()
    }

    @Test
    fun otherGatesStillApplyToTies() {
        val e = ClassicEngine(tied, random = Random(1))
        // Field, caps and the autocorrect switch all keep the literal.
        val email =
            e.predictNow(
                PredictRequest(
                    TypingContext(field = FieldClass.Email),
                    TestKeyboard.typed("tset"),
                    TestKeyboard.layout,
                )
            )
        assertThat(email.autocorrect).isNull()
        assertThat(decode(e, "TSET").autocorrect).isNull()
        val off =
            e.predictNow(
                PredictRequest(
                    TypingContext(),
                    TestKeyboard.typed("tset"),
                    TestKeyboard.layout,
                    autocorrectAllowed = false,
                )
            )
        assertThat(off.autocorrect).isNull()
        // A user word is a known word: never corrected.
        val withUser = ClassicEngine(tied, userWords = { listOf("tset") })
        assertThat(decode(withUser).autocorrect).isNull()
    }

    @Test
    fun ruleCanBeSwitchedOffToRestoreTheMargin() {
        val cfg = ClassicConfig(autocorrect = AutocorrectConfig(nonWordTieRule = false))
        assertThat(decode(ClassicEngine(tied, cfg)).autocorrect).isNull()
        // A clear winner is unaffected.
        assertThat(decode(ClassicEngine(tieDictionary(1_000), cfg)).autocorrect?.text)
            .isEqualTo("test")
    }

    // -------------------------------------------------------------- policy level

    private fun cand(text: String, score: Float, edits: Int = 1) =
        RankedCandidate(
            text,
            score,
            ScoreBreakdown(0f, 0f, 0f, 0f, edits, 0, CandidateSource.Dictionary),
            0,
        )

    private fun input(
        known: Boolean,
        vararg c: RankedCandidate,
        literalScore: Float = -20f,
        delta: Float = 0f,
    ) =
        AutocorrectInput(
            literal = "tset",
            literalScore = literalScore,
            literalIsDictionaryWord = known,
            literalIsUserWord = false,
            candidates = c.toList(),
            field = FieldClass.Text,
            allowed = true,
            thresholdDelta = delta,
        )

    @Test
    fun knownLiteralKeepsTheRunnerUpMargin() {
        val policy = AutocorrectPolicy()
        val close = input(true, cand("a", -5f), cand("b", -5.3f))
        assertThat(policy.decide(close, Random(1)).chosen).isNull()
        val clear = input(true, cand("a", -5f), cand("b", -6f))
        assertThat(policy.decide(clear, Random(1)).chosen?.text).isEqualTo("a")
        val unknown = input(false, cand("a", -5f), cand("b", -5.3f))
        val d = policy.decide(unknown, Random(1))
        assertThat(d.chosen?.text).isEqualTo("a")
        assertThat(d.runnerUp?.text).isEqualTo("b")
    }

    @Test
    fun randomPickNeedsAnEligibleRunnerUp() {
        val policy = AutocorrectPolicy()
        // The runner-up needs 3 edits (> maxEdits): never chosen, whatever the random says.
        repeat(20) {
            val d =
                policy.decide(
                    input(false, cand("a", -5f), cand("b", -5.01f, edits = 3)),
                    Random(it),
                )
            assertThat(d.chosen?.text).isEqualTo("a")
        }
        // Without a random, exact ties go to the first.
        assertThat(policy.choose(input(false, cand("a", -5f), cand("b", -5f)))?.text).isEqualTo("a")
    }

    @Test
    fun thresholdDeltaShiftsTheGateButRealWordsOnlyGetStricter() {
        val policy = AutocorrectPolicy()
        val gap = 20f - 5f
        val base = input(false, cand("a", -5f))
        assertThat(policy.choose(base)).isNotNull()
        // Required = 2.5 + delta.
        assertThat(policy.choose(input(false, cand("a", -5f), delta = gap - 2.5f + 0.1f))).isNull()
        assertThat(policy.choose(input(false, cand("a", -5f), delta = gap - 2.5f - 0.1f)))
            .isNotNull()
        // Real word: required 6 (+ positive deltas only).
        val real = input(true, cand("a", -5f), literalScore = -11f)
        assertThat(policy.choose(real)).isNotNull()
        assertThat(policy.choose(input(true, cand("a", -5f), literalScore = -11f, delta = -3f)))
            .isNotNull()
        assertThat(policy.choose(input(true, cand("a", -5f), literalScore = -11f, delta = 1f)))
            .isNull()
    }

    @Test
    fun defaultTieBreakIsSeededPerWordAndContext() {
        // Without an injected Random, re-decoding the same literal + context always agrees,
        // across engine instances, so the separator's autocorrect matches the strip.
        fun pick(before: String): String {
            val r =
                ClassicEngine(tied)
                    .predictNow(
                        PredictRequest(
                            TypingContext(before = before),
                            TestKeyboard.typed("tset"),
                            TestKeyboard.layout,
                        )
                    )
            // The tie branch is really reached: a runner-up within the tie epsilon exists, so the
            // pick is the seeded random one rather than a clear winner.
            assertThat(r.autocorrectRunnerUp).isNotNull()
            assertThat(setOf(r.autocorrect!!.text, r.autocorrectRunnerUp!!.text))
                .containsExactly("test", "set")
            return r.autocorrect!!.text
        }
        for (ctx in listOf("", "a ", "so the ", "hello there ")) {
            val first = pick(ctx)
            repeat(10) { assertThat(pick(ctx)).isEqualTo(first) }
        }
    }

    @Test
    fun tryFeaturesForReturnsNullWhileTheLockIsHeld() {
        val engine = ClassicEngine(tied)
        val request =
            PredictRequest(TypingContext(), TestKeyboard.typed("tset"), TestKeyboard.layout)
        assertThat(engine.tryFeaturesFor(request, "tset")).isNotNull()
        assertThat(engine.tryFeaturesFor(request, "tset"))
            .isEqualTo(engine.featuresFor(request, "tset"))
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val holder = Thread {
            engine.predictRanked(request) {
                entered.countDown()
                release.await()
                false
            }
        }
        holder.start()
        try {
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
            val start = System.nanoTime()
            assertThat(engine.tryFeaturesFor(request, "tset")).isNull()
            assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(500)
        } finally {
            release.countDown()
            holder.join(5_000)
        }
        assertThat(engine.tryFeaturesFor(request, "tset")).isNotNull()
    }

    @Test
    fun tryFeaturesForDistinguishesNotInPoolFromBusy() {
        val engine = ClassicEngine(tied)
        val request =
            PredictRequest(TypingContext(), TestKeyboard.typed("tset"), TestKeyboard.layout)
        assertThat(engine.tryFeaturesFor(request, "zzzzzz"))
            .isSameInstanceAs(RankingFeatures.NOT_DECODABLE)
        // featuresFor keeps its null for "not among the candidates".
        assertThat(engine.featuresFor(request, "zzzzzz")).isNull()
    }
}
