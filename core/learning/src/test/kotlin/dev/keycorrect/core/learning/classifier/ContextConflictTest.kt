package dev.keycorrect.core.learning.classifier

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.learning.CommitMethod
import dev.keycorrect.core.learning.LearningUpdate.BaseTuneEvidence
import dev.keycorrect.core.learning.LearningUpdate.CorrectionEvidence
import dev.keycorrect.core.learning.LearningUpdate.Preference
import dev.keycorrect.core.learning.LearningUpdate.WordUsed
import dev.keycorrect.core.learning.ShownSuggestion
import org.junit.Test

/** Context replacement and the conflict signals that feed the built-in tune (N-25, N-26). */
class ContextConflictTest {
    private val cfg = ClassifierConfig()
    private val classifier = OutcomeClassifier(cfg)

    private fun prediction(text: String, rank: Int) =
        ShownSuggestion(text, rank, shownAtChars = 0, visibleMillis = 800)

    private fun pickedHelp(known: Boolean = true) =
        committed(
            "hel",
            "help",
            CommitMethod.SuggestionPicked,
            listOf(seen("hell", 0), seen("help", 1)),
            1,
            known = known,
        )

    @Test
    fun contextReplacedEmitsPersonalWeightsAndATuneButNoSpellingEvidence() {
        val out = classifier.classify(committed("on", "in", CommitMethod.ContextReplaced))
        assertThat(out.filterIsInstance<CorrectionEvidence>()).isEmpty()
        assertThat(out.filterIsInstance<Preference>()).isEmpty()
        assertThat(out)
            .containsExactly(
                WordUsed(ctx, "in", cfg.contextReplacedWeight),
                WordUsed(ctx, "on", -cfg.contextReplacedOriginalWeight),
                BaseTuneEvidence("the", "in", "on", cfg.contextReplacedTuneWeight),
            )
    }

    @Test
    fun contextReplacedAtASentenceStartHasNoTune() {
        val e = committed("on", "in", CommitMethod.ContextReplaced).copy(context = emptyList())
        assertThat(classifier.classify(e).filterIsInstance<BaseTuneEvidence>()).isEmpty()
    }

    @Test
    fun pickingBelowTheTopSuggestionTunesTheBaseAndAddsPersonalWeight() {
        val out = classifier.classify(pickedHelp())
        assertThat(out)
            .contains(BaseTuneEvidence("the", "help", "hell", cfg.pickedOverTopTuneWeight))
        assertThat(out)
            .contains(WordUsed(ctx, "help", cfg.suggestionPickedWeight + cfg.conflictExtraWeight))
    }

    @Test
    fun aPredictionPickedBelowTheTopTunesTheBase() {
        val out =
            classifier.classify(
                committed(
                    "",
                    "keys",
                    CommitMethod.PredictionPicked,
                    listOf(prediction("home", 0), prediction("keys", 1)),
                    1,
                )
            )
        assertThat(out)
            .contains(BaseTuneEvidence("the", "keys", "home", cfg.pickedOverTopTuneWeight))
    }

    @Test
    fun pickingTheTopSuggestionIsNoConflict() {
        val out =
            classifier.classify(
                committed(
                    "hel",
                    "hell",
                    CommitMethod.SuggestionPicked,
                    listOf(seen("hell", 0), seen("help", 1)),
                    0,
                )
            )
        assertThat(out.filterIsInstance<BaseTuneEvidence>()).isEmpty()
    }

    @Test
    fun anUnknownPickedWordIsNoConflict() {
        val out = classifier.classify(pickedHelp(known = false))
        assertThat(out.filterIsInstance<BaseTuneEvidence>()).isEmpty()
    }

    @Test
    fun aPickAtASentenceStartHasNoTune() {
        val e = pickedHelp().copy(context = emptyList())
        assertThat(classifier.classify(e).filterIsInstance<BaseTuneEvidence>()).isEmpty()
    }

    @Test
    fun typingPastTheShownPredictionsTunesTheBase() {
        val out =
            classifier.classify(
                committed("keys", shown = listOf(prediction("home", 0), prediction("world", 1)))
            )
        assertThat(out)
            .contains(BaseTuneEvidence("the", "keys", "home", cfg.typedPastPredictionTuneWeight))
        assertThat(out)
            .contains(WordUsed(ctx, "keys", cfg.typedWordWeight + cfg.conflictExtraWeight))
    }

    @Test
    fun typingAPredictedWordOrWithoutPredictionsIsNoConflict() {
        val predicted =
            classifier.classify(
                committed("home", shown = listOf(prediction("home", 0), prediction("world", 1)))
            )
        assertThat(predicted.filterIsInstance<BaseTuneEvidence>()).isEmpty()
        val none = classifier.classify(committed("keys"))
        assertThat(none.filterIsInstance<BaseTuneEvidence>()).isEmpty()
        // Suggestions that appeared mid-word are not predictions.
        val midWord = classifier.classify(committed("keys", shown = listOf(seen("keyed", 0))))
        assertThat(midWord.filterIsInstance<BaseTuneEvidence>()).isEmpty()
    }

    @Test
    fun typingAnUnknownWordPastPredictionsIsNoConflict() {
        val out =
            classifier.classify(
                committed("zorp", shown = listOf(prediction("home", 0)), known = false)
            )
        assertThat(out.filterIsInstance<BaseTuneEvidence>()).isEmpty()
    }
}
