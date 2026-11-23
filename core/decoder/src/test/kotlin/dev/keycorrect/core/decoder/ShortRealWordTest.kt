package dev.keycorrect.core.decoder

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.engineapi.CandidateSource
import dev.keycorrect.core.engineapi.FieldClass
import org.junit.Test

/** [AutocorrectConfig.shortRealWordMargin]: short real-word typos one edit away ("go" -> "to"). */
class ShortRealWordTest {
    private val policy = AutocorrectPolicy()

    private fun cand(text: String, score: Float, edits: Int = 1) =
        RankedCandidate(
            text,
            score,
            ScoreBreakdown(0f, 0f, 0f, 0f, edits, 0, CandidateSource.Dictionary),
            0,
        )

    private fun chosen(literal: String, top: RankedCandidate, delta: Float = 0f) =
        policy
            .decide(
                AutocorrectInput(
                    literal = literal,
                    literalScore = -10f,
                    literalIsDictionaryWord = true,
                    literalIsUserWord = false,
                    candidates = listOf(top, cand("zz", -30f)),
                    field = FieldClass.Text,
                    allowed = true,
                    thresholdDelta = delta,
                ),
                null,
            )
            .chosen
            ?.text

    @Test
    fun aShortRealWordOneEditAwayNeedsTheSmallerMargin() {
        // Beats the literal by 4.5: enough for "go" (short margin 4), not for the general 6.
        assertThat(chosen("go", cand("to", -5.5f))).isEqualTo("to")
        assertThat(chosen("gave", cand("have", -5.5f))).isEqualTo("have")
        assertThat(chosen("go", cand("to", -6.5f))).isNull()
    }

    @Test
    fun longerWordsAndTwoEditCandidatesKeepTheRealWordMargin() {
        assertThat(chosen("going", cand("doing", -5.5f))).isNull()
        assertThat(chosen("go", cand("to", -5.5f, edits = 2))).isNull()
        assertThat(chosen("going", cand("doing", -3.5f))).isEqualTo("doing")
    }

    @Test
    fun anAddedApostropheKeepsTheRealWordMargin() {
        assertThat(chosen("were", cand("we're", -5.5f))).isNull()
    }

    @Test
    fun aLearnedPositiveThresholdShiftStillAddsToTheShortMargin() {
        assertThat(chosen("go", cand("to", -5.5f), delta = 1f)).isNull()
    }

    @Test
    fun equalMarginsTurnTheRuleOff() {
        val off = AutocorrectPolicy(AutocorrectConfig(shortRealWordMargin = 6f))
        val input =
            AutocorrectInput(
                "go",
                -10f,
                true,
                false,
                listOf(cand("to", -5.5f)),
                FieldClass.Text,
                true,
            )
        assertThat(off.decide(input, null).chosen).isNull()
    }
}
