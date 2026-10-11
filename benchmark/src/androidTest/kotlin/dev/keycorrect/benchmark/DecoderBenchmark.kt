package dev.keycorrect.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.keycorrect.core.decoder.ClassicEngine
import dev.keycorrect.core.learning.model.PersonalModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One measured iteration decodes the whole fixed batch of [BenchInputs.requests] (about 200
 * inputs), so the reported time divided by the batch size is the mean cost of one decode.
 */
@RunWith(AndroidJUnit4::class)
class DecoderBenchmark {
    @get:Rule val benchmarkRule = BenchmarkRule()

    /** Defeats dead-code elimination of the results. */
    private var sink = 0

    @Test
    fun composing() {
        val engine = ClassicEngine(BenchDictionary.dictionary)
        val requests = BenchInputs.requests
        benchmarkRule.measureRepeated {
            var n = 0
            for (r in requests) n += engine.predictNow(r).candidates.size
            sink = n
        }
        assertTrue(sink > 0)
    }

    @Test
    fun composingPersonalized() {
        val model = PersonalModel()
        BenchLearning.train(model, BenchLearning.TRAIN_WORDS)
        checkNotNull(model.snapshot().touchOffset(BenchLearning.PROFILE.touchKey, 'e')) {
            "the simulated learning produced no touch offsets"
        }
        val engine =
            ClassicEngine(
                BenchDictionary.dictionary,
                personalization = { model.snapshot() },
                touchKey = { BenchLearning.PROFILE.touchKey },
            )
        val requests = BenchInputs.requests
        benchmarkRule.measureRepeated {
            var n = 0
            for (r in requests) n += engine.predictNow(r).candidates.size
            sink = n
        }
        assertTrue(sink > 0)
    }
}
