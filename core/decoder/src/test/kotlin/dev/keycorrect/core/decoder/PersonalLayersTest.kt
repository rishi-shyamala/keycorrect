package dev.keycorrect.core.decoder

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.engineapi.ComposingInput
import dev.keycorrect.core.engineapi.FieldClass
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.PredictResult
import dev.keycorrect.core.engineapi.TypingContext
import dev.keycorrect.core.learning.CommitMethod
import dev.keycorrect.core.learning.DeviceClass
import dev.keycorrect.core.learning.InputProfile
import dev.keycorrect.core.learning.LearningUpdate
import dev.keycorrect.core.learning.Personalization
import dev.keycorrect.core.learning.RankingFeatures
import dev.keycorrect.core.learning.WordCommitted
import dev.keycorrect.core.learning.classifier.OutcomeClassifier
import dev.keycorrect.core.learning.model.PersonalModel
import dev.keycorrect.core.learning.model.PersonalModelConfig
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random
import org.junit.Test

/**
 * The two personal layers (N-26): the personal n-gram weight that grows with the evidence in the
 * context, and the built-in tune overlay; plus a scenario through the real classifier and model.
 */
class PersonalLayersTest {
    private val dictionary = TestDictionary.dictionary
    private val plain = ClassicEngine(dictionary)

    private class Fake(
        private val userLp: (String) -> Float? = { null },
        private val evidence: Float = 0f,
        private val tune: (String, String) -> Float = { _, _ -> 0f },
    ) : Personalization {
        override fun userLogProb(context: List<String>, word: String) = userLp(word)

        override fun userContextEvidence(context: List<String>) = evidence

        override fun baseTune(prevWord: String, word: String) = tune(prevWord, word)

        override fun correctionBias(typed: String, candidate: String, contextBucket: String) = 0f

        override fun isSuppressed(typed: String, candidate: String) = false

        override fun isBlocked(word: String) = false

        override fun preferredCasing(key: String): String? = null

        override fun touchOffset(touchKey: String, char: Char): Pair<Float, Float>? = null

        override val autocorrectThresholdDelta = 0f

        override fun rankerScore(features: RankingFeatures) = 0f

        override val phraseGateDelta = 0f
    }

    private fun engine(p: Personalization) =
        ClassicEngine(dictionary, ClassicConfig(), personalization = { p }, random = Random(1))

    private fun typed(before: String) =
        PredictRequest(
            TypingContext(before, "", FieldClass.Text),
            TestKeyboard.typed("helo"),
            TestKeyboard.layout,
            12,
            0,
        )

    private fun heldLm(e: ClassicEngine, before: String = "they ") =
        e.predictRanked(typed(before)).candidates.first { it.text == "held" }.breakdown

    private fun baseP(): Float {
        val b = heldLm(plain).lm
        return exp(b.toDouble()).toFloat()
    }

    private fun expected(w: Float, userP: Float) = ln(w * userP + (1 - w) * baseP())

    @Test
    fun noContextEvidenceKeepsTheFixedWeight() {
        val u = heldLm(engine(Fake({ if (it == "held") ln(0.9f) else null })))
        assertThat(u.lm).isWithin(1e-4f).of(expected(0.25f, 0.9f))
    }

    @Test
    fun strongContextEvidenceApproachesTheMaximumWeight() {
        val u = heldLm(engine(Fake({ if (it == "held") ln(0.9f) else null }, evidence = 1e6f)))
        assertThat(u.lm).isWithin(1e-3f).of(expected(0.55f, 0.9f))
    }

    @Test
    fun theWeightIsAMixOfTheTwoAtEvidenceEqualToK() {
        // n = K = 4: confidence 0.5, weight 0.25 + 0.3 * 0.5 = 0.40.
        val u = heldLm(engine(Fake({ if (it == "held") ln(0.9f) else null }, evidence = 4f)))
        assertThat(u.lm).isWithin(1e-4f).of(expected(0.40f, 0.9f))
    }

    @Test
    fun theWeightIsConfigurable() {
        val cfg =
            ClassicConfig(
                user = UserDataConfig(userNgramWeightMin = 0.1f, userNgramWeightMax = 0.1f)
            )
        val e =
            ClassicEngine(
                dictionary,
                cfg,
                personalization = { Fake({ if (it == "held") ln(0.9f) else null }, 50f) },
                random = Random(1),
            )
        assertThat(heldLm(e).lm).isWithin(1e-4f).of(expected(0.1f, 0.9f))
    }

    @Test
    fun theBaseTuneNudgesTheBuiltInScoreAndCountsAsPersonal() {
        val base = heldLm(plain)
        val tuned =
            heldLm(engine(Fake(tune = { prev, w -> if (prev == "they" && w == "held") 0.5f else 0f })))
        assertThat(tuned.lm).isWithin(1e-4f).of(base.lm + 0.5f)
        assertThat(tuned.userLm).isWithin(1e-4f).of(0.5f)
        // Another context is not tuned.
        val other = heldLm(engine(Fake(tune = { p, w -> if (p == "they" && w == "held") 0.5f else 0f })), "you ")
        assertThat(other.lm).isWithin(1e-4f).of(heldLm(plain, "you ").lm)
    }

    @Test
    fun withoutPersonalizationNothingIsTuned() {
        val a = plain.predictNow(typed("they "))
        val b = engine(Personalization.None).predictNow(typed("they "))
        assertThat(b).isEqualTo(a)
    }

    // ------------------------------------------------------------------ scenario

    private val profile = InputProfile(DeviceClass.Phone, false, "qwerty@54dp", FieldClass.Text)
    private val classifier = OutcomeClassifier()

    private fun replaced(context: List<String>, original: String, replacement: String, t: Long) =
        WordCommitted(
            timeMillis = t,
            profile = profile,
            context = context,
            typed = original,
            committed = replacement,
            method = CommitMethod.ContextReplaced,
        )

    private fun nextWords(e: ClassicEngine, before: String): List<String> =
        e.predictNow(
                PredictRequest(
                    TypingContext(before, "", FieldClass.Text),
                    ComposingInput.Empty,
                    TestKeyboard.layout,
                    8,
                    0,
                )
            )
            .texts()

    private fun PredictResult.texts() = candidates.map { it.text }

    private fun trainedModel(replacements: Int): PersonalModel {
        val model = PersonalModel(PersonalModelConfig().eager())
        var t = 1_000L
        // Background typing, so the personal n-gram has enough mass to speak at all.
        val words = listOf("the", "and", "was", "see", "keys", "home", "friend", "water", "world")
        repeat(40) { i ->
            model.apply(listOf(LearningUpdate.WordUsed(emptyList(), words[i % words.size], 1f)), t++)
        }
        repeat(replacements) {
            val ups =
                classifier.classify(replaced(listOf("Sharks", "live"), "in", "on", t))
            model.apply(ups, t++)
        }
        model.publishNow(t)
        return model
    }

    @Test
    fun aContextReplacementReordersTheNextWordsInThatContextOnly() {
        val before = nextWords(plain, "Sharks live ")
        assertThat(before.indexOf("in")).isAtLeast(0)
        // The base prefers "in": "on" is below it or not even offered.
        assertThat(before.indexOf("on").let { if (it < 0) Int.MAX_VALUE else it })
            .isGreaterThan(before.indexOf("in"))

        val model = trainedModel(replacements = 2)
        val e = engine(model.snapshot())
        val after = nextWords(e, "Sharks live ")
        assertThat(after).contains("on")
        assertThat(after.indexOf("on")).isLessThan(after.indexOf("in").let { if (it < 0) Int.MAX_VALUE else it })

        // Elsewhere the order is what the built-in model says.
        val put = nextWords(plain, "Put it ")
        assertThat(nextWords(e, "Put it ").take(3)).isEqualTo(put.take(3))
    }

    @Test
    fun theSpellingOfTheReplacedWordIsNeverCorrected() {
        val model = trainedModel(replacements = 2)
        val e = engine(model.snapshot())
        // Typing "in" is still "in": the contextual preference is no correction memory.
        val r = e.predictNow(typed("Sharks live ").copy(composing = TestKeyboard.typed("in")))
        assertThat(r.autocorrect).isNull()
    }
}
