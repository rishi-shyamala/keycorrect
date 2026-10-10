package dev.keycorrect.core.decoder

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.dictionary.Dictionary
import dev.keycorrect.core.dictionary.DictionaryData
import dev.keycorrect.core.dictionary.DictionaryMetadata
import dev.keycorrect.core.dictionary.DictionaryWriter
import dev.keycorrect.core.dictionary.WordEntry
import dev.keycorrect.core.engineapi.ComposingInput
import dev.keycorrect.core.engineapi.FieldClass
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.PredictResult
import dev.keycorrect.core.engineapi.TypingContext
import dev.keycorrect.core.learning.Personalization
import dev.keycorrect.core.learning.RankingFeatures
import dev.keycorrect.core.learning.contextBucket
import java.nio.ByteBuffer
import kotlin.math.ln
import kotlin.random.Random
import org.junit.Test

/** The decoder's use of [Personalization] (docs/PLAN.md §6.1), with a hand-made fake. */
class PersonalizationTest {
    private val dictionary = TestDictionary.dictionary

    private class Fake(
        val userLp: (List<String>, String) -> Float? = { _, _ -> null },
        val bias: (String, String, String) -> Float = { _, _, _ -> 0f },
        val suppressed: (String, String) -> Boolean = { _, _ -> false },
        val blocked: (String) -> Boolean = { false },
        val casing: (String) -> String? = { null },
        val touch: (String, Char) -> Pair<Float, Float>? = { _, _ -> null },
        val ranker: (RankingFeatures) -> Float = { 0f },
        override val autocorrectThresholdDelta: Float = 0f,
        override val phraseGateDelta: Float = 0f,
    ) : Personalization {
        override fun userLogProb(context: List<String>, word: String) = userLp(context, word)

        override fun correctionBias(typed: String, candidate: String, contextBucket: String) =
            bias(typed, candidate, contextBucket)

        override fun isSuppressed(typed: String, candidate: String) = suppressed(typed, candidate)

        override fun isBlocked(word: String) = blocked(word)

        override fun preferredCasing(key: String) = casing(key)

        override fun touchOffset(touchKey: String, char: Char) = touch(touchKey, char)

        override fun rankerScore(features: RankingFeatures) = ranker(features)
    }

    private fun engine(
        p: Personalization,
        dict: Dictionary = dictionary,
        touchKey: String? = null,
        config: ClassicConfig = ClassicConfig(),
    ) =
        ClassicEngine(
            dict,
            config,
            personalization = { p },
            touchKey = { touchKey },
            random = Random(1),
        )

    private fun request(
        composing: ComposingInput,
        before: String = "",
        maxCandidates: Int = 3,
        maxPhraseWords: Int = 0,
    ) =
        PredictRequest(
            TypingContext(before, "", FieldClass.Text),
            composing,
            TestKeyboard.layout,
            maxCandidates,
            maxPhraseWords,
        )

    private fun PredictResult.texts() = candidates.map { it.text }

    private fun typed(s: String, before: String = "", n: Int = 3) =
        request(TestKeyboard.typed(s), before, n)

    private val plain = ClassicEngine(dictionary)

    // ------------------------------------------------------------------ language model

    @Test
    fun noneBehavesLikeNoPersonalization() {
        val a = plain.predictNow(typed("helli"))
        val b = engine(Personalization.None).predictNow(typed("helli"))
        assertThat(b).isEqualTo(a)
    }

    @Test
    fun userNgramBoostsAWordAndIsInterpolated() {
        val before = plain.predictNow(typed("helo", n = 12))
        assertThat(before.texts().first()).isNotEqualTo("held")
        val ctx = mutableListOf<List<String>>()
        val p =
            Fake(
                userLp = { c, w ->
                    ctx.add(c)
                    if (w == "held") ln(0.9f) else null
                }
            )
        val after = engine(p).predictNow(typed("helo", before = "they ", n = 12))
        assertThat(after.texts().first()).isEqualTo("held")
        assertThat(ctx.first()).containsExactly("they")
        // score = ln(0.25 * 0.9 + 0.75 * P_base): at most ln(0.25*0.9) above... and never below
        // base.
        val base = plain.predictRanked(typed("helo", before = "they ", n = 12))
        val boosted = engine(p).predictRanked(typed("helo", before = "they ", n = 12))
        val b = base.candidates.first { it.text == "held" }.breakdown.lm
        val u = boosted.candidates.first { it.text == "held" }.breakdown
        assertThat(u.lm).isGreaterThan(b)
        assertThat(u.lm)
            .isWithin(1e-4f)
            .of(ln(0.25f * 0.9f + 0.75f * Math.exp(b.toDouble()).toFloat()))
        assertThat(u.userLm).isWithin(1e-4f).of(u.lm - b)
    }

    @Test
    fun userNgramShapesNextWordPredictionsAndPhrases() {
        val req = request(ComposingInput.Empty, before = "thank you ", maxCandidates = 3)
        val base = plain.predictNow(req)
        assertThat(base.texts()).doesNotContain("have")
        // Interpolated with weight 0.25, a certain user word scores about ln(0.25) = -1.4: it
        // enters the strip without displacing the dictionary's strong favourites.
        val p = Fake(userLp = { _, w -> if (w == "have") 0f else null })
        val r = engine(p).predictNow(req)
        assertThat(r.texts()).contains("have")
        assertThat(r.candidates.first { it.text == "have" }.score).isAtMost(ln(0.25f) + 0.5f)
    }

    @Test
    fun phraseGateDeltaTightensAndLoosensPhrases() {
        val req = request(ComposingInput.Empty, before = "thank you ", maxPhraseWords = 3)
        assertThat(plain.predictNow(req).phrase).isNotNull()
        assertThat(engine(Fake(phraseGateDelta = 20f)).predictNow(req).phrase).isNull()
    }

    // ------------------------------------------------------------------ correction memory

    private fun tieDict(setCount: Long): Dictionary {
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
                DictionaryMetadata("en-US", "2026-10-09", 1, "p"),
                counts.map { (w, c) -> WordEntry.fromCount(w, c, 1_000_000L, 0) },
                emptyList(),
                emptyList(),
            )
        return Dictionary.open(ByteBuffer.wrap(DictionaryWriter.write(data, true)))
    }

    @Test
    fun oneLearnedPreferenceFlipsATie() {
        val dict = tieDict(12_000) // test wins by ~0.4
        assertThat(engine(Personalization.None, dict).predictNow(typed("tset")).autocorrect?.text)
            .isEqualTo("test")
        val seen = mutableListOf<String>()
        val p =
            Fake(
                bias = { t, c, b ->
                    seen.add(b)
                    if (t == "tset" && c == "set") 0.5f else 0f
                }
            )
        val r = engine(p, dict).predictNow(typed("tset", before = "a Test "))
        assertThat(r.autocorrect?.text).isEqualTo("set")
        assertThat(r.autocorrectRunnerUp?.text).isEqualTo("test")
        assertThat(seen.toSet()).containsExactly(contextBucket(listOf("Test"), "Text"))
    }

    @Test
    fun suppressedPairIsNeverAutocorrectedButStaysInTheStrip() {
        val dict = tieDict(12_000)
        val p = Fake(suppressed = { t, c -> t == "tset" && c == "test" })
        val r = engine(p, dict).predictNow(typed("tset"))
        assertThat(r.texts()).contains("test")
        assertThat(r.autocorrect?.text).isEqualTo("set")
        val both = Fake(suppressed = { t, _ -> t == "tset" })
        val none = engine(both, dict).predictNow(typed("tset"))
        assertThat(none.texts()).containsAtLeast("test", "set")
        assertThat(none.autocorrect).isNull()
    }

    @Test
    fun blockedWordsAreNeverSuggested() {
        val p = Fake(blocked = { it == "hello" || it == "very" })
        val r = engine(p).predictNow(typed("helli", n = 8))
        assertThat(r.texts()).doesNotContain("hello")
        assertThat(r.autocorrect?.text).isNotEqualTo("hello")
        val next =
            engine(p)
                .predictNow(request(ComposingInput.Empty, before = "thank you ", maxCandidates = 8))
        assertThat(next.texts()).doesNotContain("very")
        assertThat(plain.predictNow(typed("helli", n = 8)).texts()).contains("hello")
    }

    // ------------------------------------------------------------------ casing

    @Test
    fun preferredCasingOverridesLowercaseLiteralsOnly() {
        val p = Fake(casing = { if (it == "hello") "HeLLo" else null })
        val lower = engine(p).predictNow(typed("helli"))
        assertThat(lower.texts()).contains("HeLLo")
        assertThat(lower.autocorrect?.text).isEqualTo("HeLLo")
        val capital = engine(p).predictNow(typed("Helli"))
        assertThat(capital.texts()).contains("Hello")
        assertThat(capital.texts()).doesNotContain("HeLLo")
    }

    // ------------------------------------------------------------------ touch

    @Test
    fun touchOffsetShiftsTheKeyCentre() {
        // Taps meant for 'o' land on the neighbouring 'i' key (one key width to the left).
        val input = TestKeyboard.typed("helli")
        val req = request(input, maxCandidates = 6)
        val keys = mutableListOf<Pair<String, Char>>()
        val p =
            Fake(
                touch = { k, c ->
                    keys.add(k to c)
                    if (c == 'o') -1f to 0f else null
                }
            )
        val base = plain.predictRanked(req).candidates.first { it.text == "hello" }.breakdown
        val learned =
            engine(p, touchKey = "Phone/port/qwerty")
                .predictRanked(req)
                .candidates
                .first { it.text == "hello" }
                .breakdown
        assertThat(learned.spatial).isGreaterThan(base.spatial + 0.3f)
        assertThat(keys.map { it.first }.toSet()).containsExactly("Phone/port/qwerty")
        // Without a touch key the offsets are not applied.
        val noKey =
            engine(p, touchKey = null).predictRanked(req).candidates.first { it.text == "hello" }
        assertThat(noKey.breakdown.spatial).isEqualTo(base.spatial)
    }

    // ------------------------------------------------------------------ thresholds

    @Test
    fun thresholdDeltaChangesTheDecision() {
        val r = plain.predictNow(typed("helli"))
        val gap = r.candidates.first().score - r.literal!!.score
        assertThat(r.autocorrect?.text).isEqualTo("hello")
        val bolder = engine(Fake(autocorrectThresholdDelta = gap - 2.5f + 0.2f))
        assertThat(bolder.predictNow(typed("helli")).autocorrect).isNull()
        val ok = engine(Fake(autocorrectThresholdDelta = gap - 2.5f - 0.2f))
        assertThat(ok.predictNow(typed("helli")).autocorrect?.text).isEqualTo("hello")
    }

    // ------------------------------------------------------------------ ranker

    @Test
    fun rankerScoreReordersCandidates() {
        val req = typed("hel", n = 6)
        val base = plain.predictRanked(req).candidates
        val third = base[2]
        val p = Fake(ranker = { if (it.rank == 2) 50f else 0f })
        val r = engine(p).predictRanked(req).candidates
        assertThat(r.first().text).isEqualTo(third.text)
        assertThat(r.first().score)
            .isWithin(1e-3f)
            .of(r.first().breakdown.total(ClassicConfig().scoring) + 50f)
    }

    @Test
    fun featuresForExposesTheBreakdown() {
        val p = Fake(userLp = { _, w -> if (w == "held") ln(0.9f) else null })
        val e = engine(p)
        val req = typed("helo", n = 3)
        val held = e.featuresFor(req, "held")!!
        assertThat(held.isLiteral).isFalse()
        assertThat(held.userLm).isGreaterThan(0f)
        assertThat(held.rank).isAtLeast(0)
        val literal = e.featuresFor(req, "helo")!!
        assertThat(literal.isLiteral).isTrue()
        assertThat(literal.userLm).isEqualTo(0f)
        assertThat(e.featuresFor(req, "nonsenseword")).isNull()
        // Bias shows up as a feature.
        val b = engine(Fake(bias = { _, c, _ -> if (c == "held") 0.5f else 0f }))
        assertThat(b.featuresFor(req, "held")!!.correctionBias).isEqualTo(0.5f)
    }
}
