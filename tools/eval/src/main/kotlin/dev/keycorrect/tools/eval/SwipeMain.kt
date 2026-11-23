package dev.keycorrect.tools.eval

import dev.keycorrect.core.decoder.ClassicConfig
import dev.keycorrect.core.decoder.ClassicEngine
import dev.keycorrect.core.learning.classifier.ClassifierConfig
import dev.keycorrect.core.learning.model.PersonalModelConfig
import dev.keycorrect.core.swipenet.SwipeEncoder
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
        o.realSwipes?.let {
            runReal(it)
            return
        }
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
                neuralLine() +
                "dictionary ${o.dict.fileName} (${dictionary.wordCount} words)" +
                (if (o.config.isNotEmpty()) "\nconfig overrides: ${o.config}" else "") +
                "\n"
        println(header)

        val engine = engine()
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

    /**
     * `--real FILE`: recorded swipes (docs/EVAL.md, "Real swipes"); output to `<out>/swipe-real/`.
     */
    private fun runReal(file: java.nio.file.Path) {
        val out = o.out.resolve("swipe-real")
        Files.createDirectories(out)
        val started = System.nanoTime()
        val set =
            RealSwipes.load(file, o.realLayout, keyboard, o.realLimit) {
                val node = dictionary.findNode(it.replace('’', '\'').lowercase())
                node >= 0 && dictionary.isTerminal(node)
            }
        if (o.learn) {
            runLearn(file, set)
            return
        }
        val header = realHeader(file, set)
        println(header)

        val engine = engine()
        val evaluator = SwipeEvaluator(keyboard, SwipeGenerator(keyboard), o.seed)
        o.neuralDump?.let { dump(it, set, engine, evaluator) }
        val (all, known) =
            evaluator.evaluateReal(
                set.swipes,
                GestureDecoder { engine.decodeGestureNow(it) },
                warmup = REAL_WARMUP_SWIPES,
                missLimit = o.failures,
            )
        val text =
            all.text("Results: real swipes, all") +
                "\n" +
                known.text("Results: real swipes of dictionary words")
        println(text)
        val rerank =
            if (o.rerankModel == null) ""
            else SwipeRerankArm(o, engine, evaluator, REAL_WARMUP_SWIPES).run(set).also(::println)

        val seconds = (System.nanoTime() - started) / 1e9
        val json = realJson(file, set, all, known, seconds)
        Files.writeString(out.resolve("results.json"), Json.write(json) + "\n")
        Files.writeString(out.resolve("report.txt"), header + "\n" + text + rerank)
        if (o.failures > 0) Files.writeString(out.resolve("swipe_misses.csv"), all.missesCsv())
        println(
            String.format(
                Locale.ROOT,
                "done in %.1f s; wrote %s",
                seconds,
                out.toAbsolutePath().normalize(),
            )
        )
    }

    /** The engine under test: SHARK² alone, or fused with the encoder under `--neural`. */
    private fun engine(): ClassicEngine = engine(openEncoder())

    private fun engine(encoder: SwipeEncoder?): ClassicEngine =
        ClassicEngine(
            dictionary,
            baseConfig,
            random = kotlin.random.Random(o.seed),
            swipeEncoder = { encoder },
        )

    private fun openEncoder(): SwipeEncoder? =
        if (o.neural) {
            SwipeEncoder.openDesktop(
                EvalMain.requireFile(
                        o.swipenetLib,
                        "swipenet library (tools/swipenet/build.sh host)",
                    )
                    .toAbsolutePath()
                    .toString(),
                EvalMain.requireFile(
                        o.swipenetModel,
                        "swipenet model (tools/data/fetch-swipe-model.sh)",
                    )
                    .toFile(),
            )
        } else {
            null
        }

    /**
     * `--real FILE --learn`: the swipe-learning replay (see [SwipeReplay]); output to
     * `<out>/swipe-learn/`. The decoders are the ones the other real-swipe runs use.
     */
    private fun runLearn(file: java.nio.file.Path, set: RealSwipeSet) {
        val out = o.out.resolve("swipe-learn")
        Files.createDirectories(out)
        val started = System.nanoTime()
        val encoder = openEncoder()
        val replay =
            SwipeReplay(
                keyboard,
                SwipeEvaluator(keyboard, SwipeGenerator(keyboard), o.seed),
                engine(encoder),
                {
                    SwipeLearner(
                        dictionary,
                        baseConfig,
                        { encoder },
                        o.seed,
                        ConfigOverrides.applyFlat(PersonalModelConfig(), o.modelConfig),
                        ConfigOverrides.applyFlat(ClassifierConfig(), o.classifierConfig),
                    )
                },
            )
        val result = replay.run(set.swipes, o.learnMinSwipes)
        val header =
            "KeyCorrect swipe-learning replay: ${file.fileName}, ${set.rows} rows read" +
                (if (o.realLimit > 0) " (limit ${o.realLimit})" else "") +
                "; ${set.swipes.size} swipes in ${result.sessionsSeen} sessions\n" +
                "sessions kept (>= ${o.learnMinSwipes} swipes): ${result.sessions}; " +
                "early (learning) swipes ${result.early}; scored late-half swipes " +
                "${result.scored} (${result.scoredDictionary} dictionary words)\n" +
                "each session is one user with a fresh engine; context: the swipe's sentence " +
                "before it (oracle); single thread\n" +
                neuralLine() +
                "dictionary ${o.dict.fileName} (${dictionary.wordCount} words)" +
                (if (o.config.isNotEmpty()) "\nconfig overrides: ${o.config}" else "") +
                "\n"
        val text = result.text()
        println(header + "\n" + text)
        val seconds = (System.nanoTime() - started) / 1e9
        val json = LinkedHashMap<String, Any>()
        json["options"] =
            linkedMapOf(
                "real" to file.toString(),
                "realLimit" to o.realLimit,
                "learnMinSwipes" to o.learnMinSwipes,
                "neural" to o.neural,
                "configOverrides" to o.config,
                "label" to o.label,
            )
        json["results"] = result.toMap()
        json["elapsedSeconds"] = seconds
        Files.writeString(out.resolve("report.txt"), header + "\n" + text)
        Files.writeString(out.resolve("report.json"), Json.write(json) + "\n")
        println(
            String.format(
                Locale.ROOT,
                "done in %.1f s; wrote %s",
                seconds,
                out.toAbsolutePath().normalize(),
            )
        )
    }

    private fun neuralLine(): String =
        if (o.neural) {
            "decoder: SHARK² fused with the neural encoder ${o.swipenetModel.fileName}\n"
        } else {
            ""
        }

    /**
     * `--neural-dump`: every candidate's score parts per swipe, for tuning the fusion weights
     * offline. Columns: swipe index, whether the target is in the dictionary, whether this
     * candidate is the target, then SHARK²'s channel, the neural log-likelihood and the LM term.
     */
    private fun dump(
        file: java.nio.file.Path,
        set: RealSwipeSet,
        engine: ClassicEngine,
        evaluator: SwipeEvaluator,
    ) {
        Files.newBufferedWriter(file).use { w ->
            w.write("swipe,in_dict,is_target,len,channel,neural,lm\n")
            set.swipes.forEachIndexed { i, s ->
                val target = s.target.replace('’', '\'').lowercase()
                for (f in engine.gestureFusionFeatures(evaluator.request(s))) {
                    val hit = f.text.replace('’', '\'').lowercase() == target
                    w.write(
                        "$i,${if (s.inDictionary) 1 else 0},${if (hit) 1 else 0}," +
                            "${f.text.count { it.isLetter() }},${f.channel},${f.neural},${f.lm}\n"
                    )
                }
            }
        }
        println("wrote ${file.toAbsolutePath().normalize()}")
    }

    private fun realHeader(file: java.nio.file.Path, set: RealSwipeSet): String =
        "KeyCorrect real-swipe eval: ${file.fileName}, ${set.rows} rows read" +
            (if (o.realLimit > 0) " (limit ${o.realLimit})" else "") +
            "; ${set.swipes.size} swipes evaluated, ${set.taps} taps and " +
            "${set.unswipeable} unswipeable words skipped\n" +
            String.format(
                Locale.ROOT,
                "layout map: affine fit of %s onto the eval keyboard, residual %.3f key widths\n",
                o.realLayout.fileName,
                set.map.rmsKeyWidths,
            ) +
            "context: the swipe's sentence before it (oracle); single thread\n" +
            neuralLine() +
            "dictionary ${o.dict.fileName} (${dictionary.wordCount} words)" +
            (if (o.config.isNotEmpty()) "\nconfig overrides: ${o.config}" else "") +
            "\n"

    private fun realJson(
        file: java.nio.file.Path,
        set: RealSwipeSet,
        all: SwipeResult,
        known: SwipeResult,
        seconds: Double,
    ): Map<String, Any> {
        val json = LinkedHashMap<String, Any>()
        json["options"] =
            linkedMapOf(
                "real" to file.toString(),
                "realLayout" to o.realLayout.toString(),
                "realLimit" to o.realLimit,
                "neural" to o.neural,
                "configOverrides" to o.config,
                "label" to o.label,
            )
        json["input"] =
            linkedMapOf(
                "rows" to set.rows,
                "swipes" to set.swipes.size,
                "taps" to set.taps,
                "unswipeable" to set.unswipeable,
                "layoutResidualKeyWidths" to set.map.rmsKeyWidths,
            )
        json["results"] = all.toMap()
        json["resultsInDictionary"] = known.toMap()
        json["elapsedSeconds"] = seconds
        return json
    }

    private fun optionsMap(): Map<String, Any> =
        linkedMapOf(
            "sentences" to o.sentences,
            "split" to o.split.name.lowercase(),
            "offset" to o.offset,
            "seed" to o.seed,
            "swipeNoise" to o.swipeNoise,
            "neural" to o.neural,
            "oracleContext" to o.oracleContext,
            "configOverrides" to o.config,
            "label" to o.label,
        )

    private companion object {
        const val WARMUP_SENTENCES = 30
        const val REAL_WARMUP_SWIPES = 500
    }
}
