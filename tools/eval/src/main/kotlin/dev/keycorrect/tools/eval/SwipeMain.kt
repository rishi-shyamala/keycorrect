package dev.keycorrect.tools.eval

import dev.keycorrect.core.decoder.ClassicConfig
import dev.keycorrect.core.decoder.ClassicEngine
import java.nio.file.Files
import java.util.Locale

/**
 * `--mode swipe`: synthetic gestures for the words of test-split sentences, decoded by the engine
 * one by one on a single thread (see [SwipeEvaluator]). Output goes to `<out>/swipe/`.
 */
class SwipeMain(private val o: Options) {
    private val keyboard = EvalKeyboard()
    private val dictionary = EvalMain.mapDictionary(o.dict)
    private val baseConfig = ConfigOverrides.apply(ClassicConfig(), o.config)

    fun run() {
        val out = o.out.resolve("swipe")
        Files.createDirectories(out)
        val started = System.nanoTime()
        val raw =
            Corpus.sample(
                EvalMain.requireFile(o.heldout, "held-out sentences"),
                o.sentences,
                o.offset,
                o.seed,
                o.split,
            )
        val sentences = raw.map { Corpus.parse(it, dictionary, o.names) }
        val swipe = SwipeConfig(noise = o.swipeNoise)
        val header =
            "KeyCorrect swipe eval: ${sentences.size} sentences (${o.split.name.lowercase()} " +
                "split, offset ${o.offset}, seed ${o.seed}), swipe noise ${o.swipeNoise} key " +
                "widths, context ${if (o.oracleContext) "oracle" else "committed"}, " +
                "${swipe.sampleIntervalMs} ms sampling, single thread\n" +
                "dictionary ${o.dict.fileName} (${dictionary.wordCount} words)" +
                (if (o.config.isNotEmpty()) "\nconfig overrides: ${o.config}" else "") +
                "\n"
        println(header)

        val engine = ClassicEngine(dictionary, baseConfig, random = kotlin.random.Random(o.seed))
        val evaluator =
            SwipeEvaluator(keyboard, SwipeGenerator(keyboard, swipe), o.seed, o.oracleContext)
        val result =
            evaluator.evaluate(
                sentences,
                GestureDecoder { engine.decodeGestureNow(it) },
                warmupSentences = WARMUP_SENTENCES,
                missLimit = o.failures,
            )
        val text = result.text("Results: swipe")
        println(text)

        val seconds = (System.nanoTime() - started) / 1e9
        val json = LinkedHashMap<String, Any>()
        json["options"] = optionsMap()
        json["results"] = result.toMap()
        json["elapsedSeconds"] = seconds
        Files.writeString(out.resolve("results.json"), Json.write(json) + "\n")
        Files.writeString(out.resolve("report.txt"), header + "\n" + text)
        if (o.failures > 0) Files.writeString(out.resolve("swipe_misses.csv"), result.missesCsv())
        println(
            String.format(
                Locale.ROOT,
                "done in %.1f s; wrote %s",
                seconds,
                out.toAbsolutePath().normalize(),
            )
        )
    }

    private fun optionsMap(): Map<String, Any> =
        linkedMapOf(
            "sentences" to o.sentences,
            "split" to o.split.name.lowercase(),
            "offset" to o.offset,
            "seed" to o.seed,
            "swipeNoise" to o.swipeNoise,
            "oracleContext" to o.oracleContext,
            "configOverrides" to o.config,
            "label" to o.label,
        )

    private companion object {
        const val WARMUP_SENTENCES = 30
    }
}
