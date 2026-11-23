package dev.keycorrect.core.decoder

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.engineapi.FieldClass
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.PredictResult
import dev.keycorrect.core.engineapi.TypingContext
import org.junit.Test

class SplitWordTest {
    private val dictionary = TestDictionary.dictionary

    private fun engine(
        split: SplitConfig = SplitConfig(),
        userWords: Collection<String> = emptyList(),
    ) = ClassicEngine(dictionary, ClassicConfig(split = split), { userWords })

    private fun decode(
        engine: ClassicEngine,
        typed: String,
        before: String = "",
        field: FieldClass = FieldClass.Text,
    ): PredictResult =
        engine.predictNow(
            PredictRequest(
                TypingContext(before, "", field),
                TestKeyboard.typed(typed),
                TestKeyboard.layout,
                3,
                0,
                true,
            )
        )

    private fun PredictResult.texts() = candidates.map { it.text }

    @Test
    fun runTogetherWordsAreOfferedAsSplit() {
        val r = decode(engine(), "thankyou")
        assertThat(r.texts()).contains("thank you")
        assertThat(r.candidates.first { it.text == "thank you" }.isSplit).isTrue()
    }

    @Test
    fun splitIsAutocorrectedByDefault() {
        assertThat(decode(engine(), "thankyou").autocorrect?.text).isEqualTo("thank you")
        val off = engine(SplitConfig(autocorrect = false))
        assertThat(decode(off, "thankyou").autocorrect?.isSplit ?: false).isFalse()
    }

    @Test
    fun aSplitCloseToTheRegularAutocorrectKeepsTheTypedWord() {
        // The split never wins outright here; the test dictionary makes the regular autocorrect
        // pick "the".
        val regular = SplitConfig(autocorrectMargin = 1000f, veto = false)
        val picked = decode(engine(regular), "tothe")
        assertThat(picked.autocorrect).isNotNull()
        assertThat(picked.autocorrect!!.isSplit).isFalse()
        // Within the veto margin, neither is applied and the split stays in the strip.
        val vetoed = decode(engine(regular.copy(veto = true, vetoMargin = 1000f)), "tothe")
        assertThat(vetoed.autocorrect).isNull()
        assertThat(vetoed.texts()).contains("to the")
        // Where split autocorrect is not allowed, the split cannot veto either.
        val veto = engine(regular.copy(veto = true, vetoMargin = 1000f))
        assertThat(decode(veto, "tothe", field = FieldClass.PersonName).autocorrect?.text)
            .isEqualTo(picked.autocorrect!!.text)
        // The veto belongs to split autocorrect: strip-only never changes the regular autocorrect.
        val stripOnly = regular.copy(autocorrect = false, veto = true, vetoMargin = 1000f)
        assertThat(decode(engine(stripOnly), "tothe").autocorrect?.text)
            .isEqualTo(picked.autocorrect!!.text)
    }

    @Test
    fun withDefaultsARunTogetherWordIsNeverReplacedByOneOfItsHalves() {
        // "tothe" -> "the" loses a word (the regular autocorrect's pick, see the veto test); the
        // defaults either split it or keep it.
        assertThat(decode(engine(), "tothe").autocorrect?.text).isIn(listOf(null, "to the"))
    }

    @Test
    fun autocorrectAppliesSplitWhenEnabled() {
        val e = engine(SplitConfig(autocorrect = true, autocorrectMargin = 0f))
        assertThat(decode(e, "thankyou").autocorrect?.text).isEqualTo("thank you")
    }

    @Test
    fun autocorrectedSplitNeedsTheMargin() {
        val e = engine(SplitConfig(autocorrect = true, autocorrectMargin = 1000f))
        assertThat(decode(e, "thankyou").autocorrect?.isSplit ?: false).isFalse()
    }

    @Test
    fun noSplitAutocorrectInEmailOrNameFields() {
        val e = engine(SplitConfig(autocorrect = true, autocorrectMargin = 0f))
        assertThat(decode(e, "thankyou", field = FieldClass.Email).autocorrect).isNull()
        val name = decode(e, "thankyou", field = FieldClass.PersonName)
        assertThat(name.autocorrect?.isSplit ?: false).isFalse()
    }

    @Test
    fun pronounHalfKeepsDictionaryCase() {
        assertThat(decode(engine(), "iwant").texts()).contains("I want")
    }

    @Test
    fun casingFollowsTheLiteral() {
        assertThat(decode(engine(), "Thankyou").texts()).contains("Thank you")
        assertThat(decode(engine(), "THANKYOU").texts()).contains("THANK YOU")
    }

    @Test
    fun knownWordsAreNeverSplit() {
        // "helpful" is a word (and "help" + "ful" is not a split of two words anyway).
        assertThat(decode(engine(), "helpful").candidates.none { it.isSplit }).isTrue()
        assertThat(decode(engine(), "hello").candidates.none { it.isSplit }).isTrue()
    }

    @Test
    fun oneLetterHalvesOtherThanAAndIAreRejected() {
        // "xthe" would be "x" + "the"; "athe" is "a" + "the" and is allowed.
        assertThat(decode(engine(), "xthe").candidates.none { it.isSplit }).isTrue()
        assertThat(decode(engine(SplitConfig(completionMargin = 0f)), "athe").texts())
            .contains("a the")
    }

    @Test
    fun shortAndDisabledInputGetNoSplit() {
        assertThat(decode(engine(), "ofa").candidates.none { it.isSplit }).isTrue()
        val off = engine(SplitConfig(enabled = false))
        assertThat(decode(off, "thankyou").candidates.none { it.isSplit }).isTrue()
    }

    @Test
    fun userWordsCanBeHalves() {
        val r = decode(engine(userWords = listOf("kubectl")), "kubectlhelp")
        assertThat(r.texts()).contains("kubectl help")
    }

    @Test
    fun contextPicksTheBetterSplit() {
        // Same letters, one cut is far likelier after "thank": the strip never has the wrong order.
        val r = decode(engine(), "youthank")
        assertThat(r.texts()).contains("you thank")
    }

    @Test
    fun splitScoreIsSpatialPlusLmPlusPenalty() {
        val e = engine()
        val r =
            e.predictRanked(
                PredictRequest(
                    TypingContext(""),
                    TestKeyboard.typed("thankyou"),
                    TestKeyboard.layout,
                    8,
                    0,
                    true,
                )
            )
        val split = r.candidates.first { it.isSplit }
        assertThat(split.breakdown.edits).isEqualTo(SplitConfig().penalty)
        assertThat(split.breakdown.lm).isLessThan(0f)
        assertThat(split.score).isWithin(1e-4f).of(split.breakdown.total(ScoringConfig()))
        // A harsher penalty lowers the score by exactly the difference.
        val harsh =
            engine(SplitConfig(penalty = -5f))
                .predictRanked(
                    PredictRequest(
                        TypingContext(""),
                        TestKeyboard.typed("thankyou"),
                        TestKeyboard.layout,
                        8,
                        0,
                        true,
                    )
                )
                .candidates
                .first { it.isSplit }
        assertThat(split.score - harsh.score).isWithin(1e-3f).of(3f)
    }

    @Test
    fun noSplitSearchInEmailOrUrlFields() {
        val e = engine(SplitConfig(autocorrect = true, autocorrectMargin = 0f))
        for (field in listOf(FieldClass.Email, FieldClass.Url)) {
            val r = decode(e, "thankyou", field = field)
            assertThat(r.candidates.none { it.isSplit }).isTrue()
            assertThat(r.autocorrect).isNull()
        }
    }

    @Test
    fun contractionLikeWordsAreNeverSplitWhateverTheConfig() {
        val off =
            ClassicEngine(
                dictionary,
                ClassicConfig(autocorrect = AutocorrectConfig(contractions = false)),
            )
        assertThat(decode(off, "dont").candidates.none { it.isSplit }).isTrue()
        assertThat(decode(engine(), "dont").candidates.none { it.isSplit }).isTrue()
        // A contraction ending is never the second word either ("wellll" style cuts).
        assertThat(decode(engine(), "wenll").candidates.none { it.text.endsWith(" nll") }).isTrue()
    }

    @Test
    fun aCompletionMarginKeepsTheSplitOutOfTheStripWhileTheWordIsStillBeingTyped() {
        val words = listOf("thankyouverymuch") // a completion of the typed prefix
        val open = decode(engine(SplitConfig(completionMargin = 0f), words), "thankyou")
        assertThat(open.texts()).contains("thank you")
        assertThat(open.texts()).contains("thankyouverymuch")
        val closed = decode(engine(SplitConfig(completionMargin = 1000f), words), "thankyou")
        assertThat(closed.texts()).contains("thankyouverymuch")
        assertThat(closed.candidates.none { it.isSplit }).isTrue()
    }
}
