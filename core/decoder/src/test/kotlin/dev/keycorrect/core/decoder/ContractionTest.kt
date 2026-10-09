package dev.keycorrect.core.decoder

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.dictionary.Dictionary
import dev.keycorrect.core.dictionary.DictionaryData
import dev.keycorrect.core.dictionary.DictionaryWriter
import dev.keycorrect.core.dictionary.WordEntry
import dev.keycorrect.core.engineapi.ComposingInput
import dev.keycorrect.core.engineapi.FieldClass
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.TypingContext
import java.nio.ByteBuffer
import org.junit.Test

class ContractionTest {
    private val cases =
        mapOf(
            "im" to "I'm",
            "dont" to "don't",
            "youre" to "you're",
            "didnt" to "didn't",
            "isnt" to "isn't",
            "cant" to "can't",
            "wont" to "won't",
        )

    private fun config(sigma: Float, contractions: Boolean = true) =
        ClassicConfig(
            spatial = SpatialConfig(sigmaXFactor = sigma, sigmaYFactor = sigma),
            autocorrect = AutocorrectConfig(contractions = contractions),
        )

    private fun autocorrect(
        typed: ComposingInput,
        before: String = "",
        config: ClassicConfig = config(1f),
        dictionary: Dictionary = TestDictionary.dictionary,
        userWords: List<String> = emptyList(),
        allowed: Boolean = true,
        field: FieldClass = FieldClass.Text,
    ): String? =
        ClassicEngine(dictionary, config, { userWords })
            .predictNow(
                PredictRequest(
                    TypingContext(before, "", field),
                    typed,
                    TestKeyboard.layout,
                    3,
                    0,
                    allowed,
                )
            )
            .autocorrect
            ?.text

    /** The test dictionary plus "cant" and "wont" as ordinary words. */
    private val withRealWords: Dictionary by lazy {
        val base = TestDictionary.data
        val extra = listOf("cant", "wont").map { WordEntry.fromCount(it, 40L, 2_000_000L, 0) }
        Dictionary.open(
            ByteBuffer.wrap(
                DictionaryWriter.write(
                    DictionaryData(base.metadata, base.words + extra, base.bigrams, base.trigrams),
                    skipUnknownNgrams = true,
                )
            )
        )
    }

    @Test
    fun contractionsAreCorrectedAtAnySigma() {
        for (sigma in listOf(0.45f, 1f)) {
            for (before in listOf("", "so ")) {
                for ((typed, expected) in cases) {
                    assertThat(autocorrect(TestKeyboard.typed(typed), before, config(sigma)))
                        .isEqualTo(expected)
                }
            }
        }
    }

    @Test
    fun capitalisedContractionKeepsInitialCap() {
        assertThat(autocorrect(TestKeyboard.typed("Dont"))).isEqualTo("Don't")
        assertThat(autocorrect(TestKeyboard.typed("Im"))).isEqualTo("I'm")
    }

    @Test
    fun theContractionIsTheTopCandidate() {
        val engine = ClassicEngine(TestDictionary.dictionary, config(1f))
        val r =
            engine.predictNow(
                PredictRequest(
                    TypingContext("", "", FieldClass.Text),
                    TestKeyboard.typed("im"),
                    TestKeyboard.layout,
                    3,
                    0,
                    true,
                )
            )
        assertThat(r.candidates.first().text).isEqualTo("I'm")
    }

    @Test
    fun respectsAutocorrectAllowedAndBlockedFields() {
        assertThat(autocorrect(TestKeyboard.typed("dont"), allowed = false)).isNull()
        assertThat(autocorrect(TestKeyboard.typed("dont"), field = FieldClass.Email)).isNull()
        assertThat(autocorrect(TestKeyboard.typed("dont"), field = FieldClass.Url)).isNull()
    }

    @Test
    fun allCapsAcronymAndUserWordsAreLeftAlone() {
        assertThat(autocorrect(TestKeyboard.typed("DONT"))).isNull()
        assertThat(autocorrect(TestKeyboard.typed("dont"), userWords = listOf("dont"))).isNull()
    }

    @Test
    fun typedApostropheIsNotTouched() {
        assertThat(autocorrect(TestKeyboard.typed("don't"))).isNull()
    }

    @Test
    fun flagOffRestoresTheOldBehaviourAtLowSigma() {
        // The spatial model alone still manages "dont" at sigma 0.45; the flag only adds the rule.
        assertThat(autocorrect(TestKeyboard.typed("dont"), config = config(0.45f, false)))
            .isEqualTo("don't")
    }

    @Test
    fun realWordsFollowTheRealWordMargin() {
        // "cant"/"wont" are words here: no autocorrect at a sentence start or after "so".
        for (typed in listOf("cant", "wont")) {
            for (before in listOf("", "so ")) {
                assertThat(
                        autocorrect(TestKeyboard.typed(typed), before, dictionary = withRealWords)
                    )
                    .isNull()
            }
        }
        // The other contractions are unaffected.
        assertThat(autocorrect(TestKeyboard.typed("dont"), dictionary = withRealWords))
            .isEqualTo("don't")
    }
}
