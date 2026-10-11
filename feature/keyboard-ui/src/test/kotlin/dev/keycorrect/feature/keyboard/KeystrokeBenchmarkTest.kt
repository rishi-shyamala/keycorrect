package dev.keycorrect.feature.keyboard

import android.text.InputType
import dev.keycorrect.core.decoder.ClassicEngine
import dev.keycorrect.core.dictionary.Dictionary
import dev.keycorrect.core.engineapi.Candidate
import dev.keycorrect.core.engineapi.CandidateSource
import dev.keycorrect.core.engineapi.PredictResult
import dev.keycorrect.core.input.FieldProfile
import dev.keycorrect.core.layout.BuiltInLayouts
import dev.keycorrect.core.layout.KeyAction
import dev.keycorrect.core.layout.KeyboardComposer
import dev.keycorrect.core.layout.KeyboardGeometry
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.feature.keyboard.prediction.Predictor
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Per-keystroke cost of the keyboard's own path (controller, composing, strip): what runs on the UI
 * thread for every key. Not a pass/fail test; it prints numbers for `tools/profile/profile.sh` and
 * runs only with `-Pkeycorrect.bench=true` (see the module's build file).
 *
 * - `canned`: a predictor that answers instantly, so only the keyboard's own work is measured.
 * - `real engine`: the shipped dictionary and the real decoder, run inline as in unit tests (on the
 *   device it runs on the decoder thread instead).
 */
class KeystrokeBenchmarkTest {
    private val enabled = System.getProperty("keycorrect.bench") == "true"

    @Test
    fun cannedPredictor() {
        assumeTrue("benchmark: run with -Pkeycorrect.bench=true", enabled)
        bench("canned") {
            Predictor { request, _ ->
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
        }
    }

    @Test
    fun realEngine() {
        assumeTrue("benchmark: run with -Pkeycorrect.bench=true", enabled)
        val file = shippedDictionary()
        assumeTrue("en_US.kcd asset not present", file != null)
        val dictionary =
            FileChannel.open(file!!.toPath(), StandardOpenOption.READ).use {
                Dictionary.open(it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()))
            }
        val engine = ClassicEngine(dictionary)
        bench("real engine") { Predictor { request, stale -> engine.predictNow(request, stale) } }
    }

    private fun bench(label: String, predictor: () -> Predictor) {
        val editor = SimEditor()
        val backend = FakeBackend()
        var now = 1_000L
        val controller =
            KeyboardController(
                editor,
                KeyboardComposer(BuiltInLayouts.repository()),
                testEnv(backend),
                clock = { now },
            )
        backend.predictor = predictor()
        controller.onStartInput(FieldProfile.from(InputType.TYPE_CLASS_TEXT, 0), KeyboardSettings())
        controller.onGeometry(KeyboardGeometry(controller.layer, 1080f, 520f))

        val keys = TEXT.map { if (it == ' ') KeyAction.Space else KeyAction.Text("$it") }
        fun typeAll(times: LongArray?, bytes: LongArray?) {
            editor.text.setLength(0)
            editor.cursor = 0
            controller.onStartInput(
                FieldProfile.from(InputType.TYPE_CLASS_TEXT, 0),
                KeyboardSettings(),
            )
            keys.forEachIndexed { i, key ->
                now += 120
                val b0 = allocated()
                val t0 = System.nanoTime()
                controller.onKey(key)
                val t = System.nanoTime() - t0
                times?.set(i, t)
                bytes?.set(i, allocated() - b0)
            }
        }
        repeat(WARM_UP_ROUNDS) { typeAll(null, null) }
        val times = LongArray(keys.size)
        val bytes = LongArray(keys.size)
        typeAll(times, bytes)
        times.sort()
        fun ms(p: Double) = times[minOf(times.size - 1, (p * times.size).toInt())] / 1e6
        println(
            "KEYSTROKE[%s] keys=%d p50=%.3f ms p95=%.3f ms p99=%.3f ms max=%.3f ms bytes/key=%d"
                .format(
                    label,
                    keys.size,
                    ms(0.50),
                    ms(0.95),
                    ms(0.99),
                    times.last() / 1e6,
                    bytes.sum() / keys.size,
                )
        )
    }

    private fun allocated(): Long =
        (ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean)
            .currentThreadAllocatedBytes

    private fun shippedDictionary(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "app/src/main/assets/dictionaries/en_US.kcd")
            if (f.isFile) return f
            dir = dir.parentFile
        }
        return null
    }

    private companion object {
        const val WARM_UP_ROUNDS = 3

        /** Plain prose with a few typos, so autocorrect, completions and next-word all run. */
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
