package dev.keycorrect.benchmark

import android.text.InputType
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.keycorrect.core.engineapi.Candidate
import dev.keycorrect.core.engineapi.CandidateSource
import dev.keycorrect.core.engineapi.PredictResult
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.layout.KeyboardGeometry
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.feature.keyboard.KeyboardController
import dev.keycorrect.feature.keyboard.prediction.Predictor
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The keyboard's own per-key work on the UI thread (controller, composing, strip), with a predictor
 * that answers instantly. One measured iteration types the whole of [TEXT] (about 500 keys), so the
 * reported time divided by the key count is the mean cost of one key press.
 */
@RunWith(AndroidJUnit4::class)
class KeystrokeBenchmark {
    @get:Rule val benchmarkRule = BenchmarkRule()

    @Test
    fun cannedPredictor() {
        val editor = BenchEditor()
        val backend = BenchBackend(canned())
        var now = 1_000L
        val controller =
            KeyboardController(
                editor,
                KeyboardComposer(BuiltInLayouts.repository()),
                benchEnvironment(backend),
                clock = { now },
            )
        val profile = FieldProfile.from(InputType.TYPE_CLASS_TEXT, 0)
        controller.onStartInput(profile, KeyboardSettings())
        controller.onGeometry(KeyboardGeometry(controller.layer, 1080f, 520f))
        val keys = TEXT.map { if (it == ' ') KeyAction.Space else KeyAction.Text("$it") }

        benchmarkRule.measureRepeated {
            runWithMeasurementDisabled {
                editor.reset()
                controller.onStartInput(profile, KeyboardSettings())
            }
            for (key in keys) {
                now += 120
                controller.onKey(key)
            }
        }
        assertTrue(editor.length > 0)
    }

    private fun canned() = Predictor { request, _ ->
        val typed = request.composing.text
        PredictResult(
            listOf(
                Candidate(typed + "s", 0f, CandidateSource.Dictionary),
                Candidate(typed + "ed", -1f, CandidateSource.Dictionary),
                Candidate(typed + "ing", -2f, CandidateSource.Dictionary),
            ),
            Candidate(typed, -3f, CandidateSource.Literal),
            null,
            sequence = request.sequence,
        )
    }

    private companion object {
        /** Plain prose with a few typos, the same text as the desktop KeystrokeBenchmarkTest. */
        val TEXT =
            """
            i was thinking about the weekend and whether we should go hiking or stay home and
            read a good book instead. the weather looks nice on saturday but sunday might be
            rainy so we could plan the trip for the morning and come back before lunch. teh
            trail near the lake is not too long and the views are beautiful in the fall. let me
            know what you think and if you want to invite anyone else from work. i will bring
            some snacks and water and we can stop for coffee on the way bakc into town.
            """
                .trimIndent()
                .replace('\n', ' ')
    }
}
