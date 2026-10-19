package dev.keycorrect.core.decoder

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.engineapi.ComposingInput
import dev.keycorrect.core.engineapi.FieldClass
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.PredictResult
import dev.keycorrect.core.engineapi.TypingContext
import org.junit.Test

/**
 * [PredictRequest.lmBonus]: added to the fused score before ranking and the autocorrect decision.
 */
class LmBonusTest {
    private val engine = ClassicEngine(TestDictionary.dictionary)

    private fun request(
        composing: ComposingInput,
        before: String = "",
        bonus: Map<String, Float> = emptyMap(),
        maxCandidates: Int = 3,
    ) =
        PredictRequest(
            TypingContext(before, "", FieldClass.Text),
            composing,
            TestKeyboard.layout,
            maxCandidates,
            lmBonus = bonus,
        )

    private fun PredictResult.texts() = candidates.map { it.text }

    @Test
    fun bonusCanFlipTheTopCandidateAndTheAutocorrect() {
        val plain = engine.predictNow(request(TestKeyboard.typed("helli")))
        assertThat(plain.autocorrect?.text).isEqualTo("hello")
        val other = plain.texts().first { it != "hello" }

        val flipped =
            engine.predictNow(request(TestKeyboard.typed("helli"), bonus = mapOf(other to 50f)))
        assertThat(flipped.texts().first()).isEqualTo(other)
        assertThat(flipped.autocorrect?.text).isEqualTo(other)
    }

    @Test
    fun bonusIsAddedToTheScoreOfTheMatchingCandidateOnly() {
        val plain = engine.predictNow(request(TestKeyboard.typed("helli")))
        val boosted =
            engine.predictNow(request(TestKeyboard.typed("helli"), bonus = mapOf("hello" to 2f)))
        val before = plain.candidates.first { it.text == "hello" }.score
        val after = boosted.candidates.first { it.text == "hello" }.score
        assertThat(after).isWithin(1e-4f).of(before + 2f)
        val untouched = plain.candidates.first { it.text != "hello" }
        assertThat(boosted.candidates.first { it.text == untouched.text }.score)
            .isEqualTo(untouched.score)
    }

    @Test
    fun bonusOnTheLiteralSuppressesAnAutocorrect() {
        val plain = engine.predictNow(request(TestKeyboard.typed("helli")))
        assertThat(plain.autocorrect).isNotNull()
        val kept =
            engine.predictNow(request(TestKeyboard.typed("helli"), bonus = mapOf("helli" to 50f)))
        assertThat(kept.literal?.text).isEqualTo("helli")
        assertThat(kept.autocorrect).isNull()
    }

    @Test
    fun bonusOnACandidateCanTriggerACorrectionOfARealWord() {
        val plain = engine.predictNow(request(TestKeyboard.typed("form")))
        assertThat(plain.autocorrect).isNull()
        assertThat(plain.texts()).contains("from")
        val fixed =
            engine.predictNow(request(TestKeyboard.typed("form"), bonus = mapOf("from" to 50f)))
        assertThat(fixed.autocorrect?.text).isEqualTo("from")
    }

    @Test
    fun bonusReordersNextWordPredictions() {
        val plain =
            engine.predictNow(request(ComposingInput.Empty, "thank you ", maxCandidates = 8))
        assertThat(plain.candidates.size).isAtLeast(2)
        val last = plain.texts().last()
        val boosted =
            engine.predictNow(
                request(ComposingInput.Empty, "thank you ", mapOf(last to 50f), maxCandidates = 8)
            )
        assertThat(boosted.texts().first()).isEqualTo(last)
        assertThat(boosted.texts()).containsExactlyElementsIn(plain.texts())
    }

    @Test
    fun emptyBonusGivesIdenticalResults() {
        val inputs =
            listOf("helli", "hello", "teh", "form", "dont", "frend", "wherre", "taht", "th", "")
        for (word in inputs) {
            val composing = if (word.isEmpty()) ComposingInput.Empty else TestKeyboard.typed(word)
            val before = if (word.isEmpty()) "thank you " else "i said "
            val implicit =
                engine.predictNow(
                    PredictRequest(
                        TypingContext(before, "", FieldClass.Text),
                        composing,
                        TestKeyboard.layout,
                    )
                )
            val explicit = engine.predictNow(request(composing, before, emptyMap()))
            assertThat(explicit).isEqualTo(implicit)
        }
    }

    @Test
    fun unknownKeysAndNonFiniteBonusesChangeNothing() {
        val plain = engine.predictNow(request(TestKeyboard.typed("helli")))
        val same =
            engine.predictNow(
                request(
                    TestKeyboard.typed("helli"),
                    bonus =
                        mapOf(
                            "zzzz" to 9f,
                            "hello" to Float.NaN,
                            "helli" to Float.POSITIVE_INFINITY,
                        ),
                )
            )
        assertThat(same).isEqualTo(plain)
    }
}
