package dev.keycorrect.core.learning.classifier

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.learning.CommitMethod
import dev.keycorrect.core.learning.LearningEvent
import dev.keycorrect.core.learning.LearningUpdate
import dev.keycorrect.core.learning.LearningUpdate.CorrectionEvidence
import dev.keycorrect.core.learning.LearningUpdate.NewWordEvidence
import dev.keycorrect.core.learning.LearningUpdate.Preference
import dev.keycorrect.core.learning.LearningUpdate.WordUsed
import dev.keycorrect.core.learning.PhraseOutcome
import dev.keycorrect.core.learning.RevertKind
import dev.keycorrect.core.learning.UserAction
import org.junit.Test

class OutcomeClassifierPropertiesTest {
    private val cfg = ClassifierConfig()
    private val classifier = OutcomeClassifier(cfg)

    private fun corpus(): List<LearningEvent> {
        val shown = listOf(seen("the", 0), late("then", 1, 3), seen("they", 2))
        val events = mutableListOf<LearningEvent>()
        for (m in CommitMethod.values()) {
            for (known in listOf(true, false)) {
                events +=
                    committed(
                        "teh",
                        "the",
                        m,
                        shown,
                        pickedRank = 0,
                        known = known,
                        touches = touches("teh"),
                    )
                events += committed("", "the", m, known = known)
                events += committed("iPhone", m = m, known = known)
            }
            events += committed("tset", "test", m, tieRunnerUp = "set")
        }
        for (k in RevertKind.values()) {
            events += reverted("tset", "test", k)
            events += reverted("tset", "test", k, replacement = "set")
        }
        events += replaced("recieve", "receive")
        events += replaced("a", "b", picked = true, millis = 0)
        events += PhraseOutcome(1, profile, emptyList(), listOf("a", "b"), 5, "c")
        events += PhraseOutcome(1, profile, emptyList(), emptyList(), 0, null)
        events += UserAction(1, profile, UserAction.Kind.NeverSuggest, "")
        return events
    }

    private fun committed(typed: String, m: CommitMethod, known: Boolean) =
        committed(typed, typed, m, known = known)

    private fun weights(u: LearningUpdate): List<Float> =
        when (u) {
            is WordUsed -> listOf(u.weight)
            is NewWordEvidence -> listOf(u.weight)
            is CorrectionEvidence -> listOf(u.delta)
            is Preference -> listOf(u.weight)
            is LearningUpdate.TouchEvidence -> u.samples.flatMap { listOf(it.dx, it.dy) }
            else -> emptyList()
        }

    @Test
    fun allWeightsAreFinite() {
        for (e in corpus()) {
            for (u in classifier.classify(e)) {
                assertThat(weights(u).all { it.isFinite() }).isTrue()
            }
        }
    }

    @Test
    fun negativeMassNeverExceedsCap_evenWithTightCaps() {
        for (cap in listOf(0f, 0.25f, 1f, 2.5f)) {
            val c = OutcomeClassifier(cfg.copy(maxNegativeMassPerEvent = cap))
            for (e in corpus()) {
                val mass =
                    c.classify(e).sumOf { u ->
                        when (u) {
                            is WordUsed -> maxOf(0f, -u.weight)
                            is CorrectionEvidence -> maxOf(0f, -u.delta)
                            is Preference -> maxOf(0f, u.weight)
                            else -> 0f
                        }.toDouble()
                    }
                assertThat(mass).isAtMost(cap + 1e-4)
            }
        }
    }

    @Test
    fun voiceScalesEveryWeightedUpdateByExactlyVoiceScale() {
        val shown = listOf(seen("kubernetes", 0), seen("kubectl", 1))
        for (known in listOf(true, false)) {
            val typed =
                classifier.classify(
                    committed("kubecti", "kubectl", CommitMethod.Voice, shown, known = known)
                )
            val base =
                classifier.classify(
                    committed("kubectl", "kubectl", CommitMethod.Gesture, shown, known = known)
                )
            assertThat(typed.map { weights(it).map { w -> w / cfg.voiceScale } })
                .containsExactlyElementsIn(base.map { weights(it) })
        }
    }

    @Test
    fun deterministic_andInputNotMutated() {
        for (e in corpus()) assertThat(classifier.classify(e)).isEqualTo(classifier.classify(e))
    }

    @Test
    fun noUpdateReferencesBlankWords() {
        for (e in corpus()) {
            for (u in classifier.classify(e)) {
                when (u) {
                    is WordUsed -> assertThat(u.word).isNotEmpty()
                    is NewWordEvidence -> assertThat(u.word).isNotEmpty()
                    is LearningUpdate.BlockWord -> assertThat(u.word).isNotEmpty()
                    else -> {}
                }
            }
        }
    }

    @Test
    fun editDistance_basics() {
        assertThat(editDistance("teh", "the")).isEqualTo(1)
        assertThat(editDistance("kitten", "sitting")).isEqualTo(3)
        assertThat(editDistance("", "abc")).isEqualTo(3)
    }
}
