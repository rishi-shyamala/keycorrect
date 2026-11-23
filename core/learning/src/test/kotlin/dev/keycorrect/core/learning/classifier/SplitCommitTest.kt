package dev.keycorrect.core.learning.classifier

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.learning.CommitMethod
import dev.keycorrect.core.learning.LearningUpdate.AutocorrectOutcome
import dev.keycorrect.core.learning.LearningUpdate.CorrectionEvidence
import dev.keycorrect.core.learning.LearningUpdate.NewWordEvidence
import dev.keycorrect.core.learning.LearningUpdate.WordUsed
import dev.keycorrect.core.learning.RevertKind
import org.junit.Test

/** A missing-space split ("ofthe" -> "of the"): evidence for the pair, never a word. */
class SplitCommitTest {
    private val classifier = OutcomeClassifier()

    private fun split(
        method: CommitMethod,
        typed: String = "ofthe",
        joined: String = "of the",
        known: Boolean = true,
    ) = committed(typed, joined, method, known = known).copy(isSplit = true)

    @Test
    fun aKeptSplitConfirmsThePairAndTheOutcome() {
        val out = classifier.classify(split(CommitMethod.Autocorrected))
        assertThat(
                out.filterIsInstance<CorrectionEvidence>().single().let { it.typed to it.target }
            )
            .isEqualTo("ofthe" to "of the")
        assertThat(out.filterIsInstance<CorrectionEvidence>().single().delta).isGreaterThan(0f)
        assertThat(out).contains(AutocorrectOutcome(reverted = false))
    }

    @Test
    fun aPickedSplitConfirmsThePair() {
        val ev = classifier.classify(split(CommitMethod.SuggestionPicked)).single()
        assertThat(ev).isInstanceOf(CorrectionEvidence::class.java)
        assertThat((ev as CorrectionEvidence).delta).isGreaterThan(0f)
    }

    @Test
    fun theJoinedTextIsNeverLearnedAsAWord() {
        for (m in listOf(CommitMethod.Autocorrected, CommitMethod.SuggestionPicked)) {
            val out = classifier.classify(split(m, known = false))
            assertThat(out.filterIsInstance<WordUsed>()).isEmpty()
            assertThat(out.filterIsInstance<NewWordEvidence>()).isEmpty()
        }
    }

    @Test
    fun otherMethodsAndOddShapesLearnNothing() {
        assertThat(classifier.classify(split(CommitMethod.Typed))).isEmpty()
        assertThat(classifier.classify(split(CommitMethod.Autocorrected, joined = "a b c")))
            .isEmpty()
    }

    @Test
    fun aRevertedSplitIsTheOrdinaryRevert() {
        val out = classifier.classify(reverted("ofthe", "of the", RevertKind.ImmediateBackspace))
        assertThat(out.filterIsInstance<CorrectionEvidence>().single().delta).isLessThan(0f)
    }
}
