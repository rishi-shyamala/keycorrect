package dev.keycorrect.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.keycorrect.core.learning.classifier.OutcomeClassifier
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The learning thread's cost: classifying word events and applying them to a PersonalModel. */
@RunWith(AndroidJUnit4::class)
class LearningBenchmark {
    @get:Rule val benchmarkRule = BenchmarkRule()

    /**
     * One iteration classifies and applies [BATCH] word events to a model that has already absorbed
     * thousands of events (so caps, decay and publishing are exercised), then publishes. The same
     * batch is re-applied every iteration: steady-state reinforcement, not first-time inserts.
     */
    @Test
    fun applyWordEvents() {
        val classifier = OutcomeClassifier()
        val model = BenchLearning.newTrainedModel()
        val events = BenchLearning.events(BATCH, startMillis = BenchLearning.TRAIN_END_MILLIS)
        benchmarkRule.measureRepeated {
            var now = BenchLearning.TRAIN_END_MILLIS
            for (e in events) {
                now = e.timeMillis
                model.apply(classifier.classify(e), now)
            }
            model.publishNow(now)
        }
    }

    private companion object {
        const val BATCH = 200
    }
}
