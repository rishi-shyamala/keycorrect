package dev.keycorrect.tools.eval

import dev.keycorrect.core.decoder.ClassicConfig
import dev.keycorrect.core.decoder.ClassicEngine
import dev.keycorrect.core.dictionary.Dictionary
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val options =
        try {
            Options.parse(args.toList())
        } catch (e: IllegalArgumentException) {
            System.err.println("error: ${e.message}")
            exitProcess(2)
        }
    if (options.help) {
        println(Options.USAGE)
        return
    }
    try {
        when (options.mode) {
            Options.Mode.Learn -> LearnMain(options).run()
            Options.Mode.Misspell -> MisspellMain(options).run()
            Options.Mode.Swipe -> SwipeMain(options).run()
            Options.Mode.Eval -> EvalMain(options).run()
        }
    } catch (e: IllegalArgumentException) {
        System.err.println("error: ${e.message}")
        exitProcess(2)
    }
}

/** Wires options to corpus, typist, evaluator and the output files. */
class EvalMain(private val o: Options) {
    private val keyboard = EvalKeyboard()
    private val dictionary: Dictionary = mapDictionary(o.dict)
    private val evaluator = Evaluator(keyboard, o.typist, o.seed, o.threads)
    private val baseConfig = ConfigOverrides.apply(ClassicConfig(), o.config)
    private val episode =
        EpisodeOptions(
            oracleContext = o.oracleContext,
            completion = o.completion,
            nextWord = o.nextWord,
            phraseWords = o.phraseWords,
            failureLimit = o.failures,
        )

    /** One engine per call; wrapped by the requested what-if experiments. */
    private fun factory(config: ClassicConfig): () -> Predictor = {
        val engine = ClassicEngine(dictionary, config, random = kotlin.random.Random(o.seed))
        WhatIfPredictor(Predictor { engine.predictNow(it) }, dictionary, o.whatIf)
    }

    private val rerankArm: RerankArm? =
        if (o.rerankModel == null) null else RerankArm(o, evaluator, episode, ::classicPredictor)

    private fun classicPredictor(): Predictor = factory(baseConfig)()

    @Suppress("LongMethod") // linear driver
    fun run() {
        require(o.rerankModel == null || !o.sweep) { "--rerank cannot be combined with --sweep" }
        Files.createDirectories(o.out)
        val started = System.nanoTime()
        val raw =
            Corpus.sample(
                requireFile(o.heldout, "held-out sentences"),
                o.sentences,
                o.offset,
                o.seed,
                o.split,
            )
        val sentences = raw.map { Corpus.parse(it, dictionary, o.names) }
        val header = header(sentences.size)
        println(header)

        val json = LinkedHashMap<String, Any>()
        json["options"] = optionsMap()
        val report = StringBuilder(header).append('\n')

        var sweepSection: String? = null
        var recommended: Map<String, String>? = null
        if (o.sweep) {
            val (text, rec, sweepJson) = runSweep()
            sweepSection = text
            recommended = rec
            json["sweep"] = sweepJson
            println(text)
            report.append(text).append('\n')
        }

        val configs = LinkedHashMap<String, ClassicConfig>()
        configs[if (o.sweep) "default" else "run"] = baseConfig
        if (recommended != null)
            configs["recommended"] = ConfigOverrides.apply(baseConfig, recommended)
        for ((name, config) in configs) {
            val result =
                evaluator.evaluate(sentences, episode, factory(config), o.rerankModel != null)
            val latency = latencyFor(sentences, config)
            val title = "Results: $name" + (if (name == "recommended") " ${recommended}" else "")
            val text = Report.text(title, result.metrics, o.nextWord, latency)
            println(text)
            report.append(text).append('\n')
            val entry = LinkedHashMap<String, Any>(Report.metricsMap(result.metrics, o.nextWord))
            if (latency.isNotEmpty())
                entry["latency"] = latency.mapValues { Report.latencyMap(it.value) }
            if (name == "recommended") entry["overrides"] = recommended!!
            json[name] = entry
            if (o.failures > 0) {
                val file = if (configs.size == 1) "failures.csv" else "failures_$name.csv"
                Files.writeString(o.out.resolve(file), Report.failuresCsv(result.failures))
            }
            if (rerankArm != null && name == "run") {
                val rerank = rerankArm.run(sentences, result)
                println(rerank.text)
                report.append(rerank.text).append('\n')
                json["rerank"] = rerank.json
                Files.writeString(o.out.resolve("rerank_sweep.csv"), rerank.sweepCsv)
                for ((file, csv) in rerank.failuresCsv) Files.writeString(o.out.resolve(file), csv)
            }
        }
        val seconds = (System.nanoTime() - started) / 1e9
        json["elapsedSeconds"] = seconds
        Files.writeString(o.out.resolve("results.json"), Json.write(json) + "\n")
        Files.writeString(o.out.resolve("report.txt"), report.toString())
        println(
            String.format("done in %.1f s; wrote %s", seconds, o.out.toAbsolutePath().normalize())
        )
    }

    private fun header(sentenceCount: Int): String {
        val t = o.typist
        return buildString {
            append(
                "KeyCorrect eval: $sentenceCount sentences (${o.split.name.lowercase()} split, offset ${o.offset}, seed ${o.seed}), "
            )
            append(
                "typist sigma ${t.sigmaX}x${t.sigmaY} key size, typo rates omit/insert/transpose "
            )
            append("${t.omissionRate}/${t.insertionRate}/${t.transpositionRate}, ")
            append(
                "context ${if (o.oracleContext) "oracle" else "committed"}, names ${o.names.name.lowercase()}\n"
            )
            if (o.whatIf.isNotEmpty())
                append("WHAT-IF experiments: ${o.whatIf.joinToString { it.flag }}\n")
            append(
                "keyboard ${keyboard.widthPx.toInt()}x${keyboard.heightPx.toInt()} px, ${keyboard.layout.keys.size} keys; "
            )
            append(
                "dictionary ${o.dict.fileName} (${dictionary.wordCount} words); threads ${o.threads}"
            )
            if (o.config.isNotEmpty()) append("\nconfig overrides: ${o.config}")
            append('\n')
        }
    }

    private fun optionsMap(): Map<String, Any> =
        linkedMapOf(
            "sentences" to o.sentences,
            "split" to o.split.name.lowercase(),
            "offset" to o.offset,
            "seed" to o.seed,
            "typist" to
                linkedMapOf(
                    "sigmaX" to o.typist.sigmaX,
                    "sigmaY" to o.typist.sigmaY,
                    "omissionRate" to o.typist.omissionRate,
                    "insertionRate" to o.typist.insertionRate,
                    "transpositionRate" to o.typist.transpositionRate,
                ),
            "oracleContext" to o.oracleContext,
            "whatIf" to o.whatIf.map { it.flag },
            "names" to o.names.name.lowercase(),
            "configOverrides" to o.config,
            "phraseWords" to o.phraseWords,
            "keyboard" to
                linkedMapOf("widthPx" to keyboard.widthPx, "heightPx" to keyboard.heightPx),
            "label" to o.label,
        ) +
            (if (o.rerankModel == null) emptyMap()
            else
                mapOf(
                    "rerank" to
                        linkedMapOf(
                            "model" to o.rerankModel.fileName.toString(),
                            "lmWeights" to o.lmWeights,
                            "pools" to o.pools,
                            "contextWords" to o.rerankContextWords,
                            "deadlineMs" to o.rerankDeadlineMs,
                        )
                ))

    private fun latencyFor(
        sentences: List<ParsedSentence>,
        config: ClassicConfig,
    ): Map<String, LatencyResult> {
        if (o.latencySentences <= 0) return emptyMap()
        val sample = sentences.take(o.latencySentences)
        val out = LinkedHashMap<String, LatencyResult>()
        val settings =
            linkedMapOf(
                "configured" to config,
                // Artificially slower: twice the pool and expansion budget and a wider beam.
                "wide search" to
                    config.copy(
                        search =
                            config.search.copy(
                                poolSize = config.search.poolSize * 2,
                                maxExpansions = config.search.maxExpansions * 2,
                                beamWidth = config.search.beamWidth + 4f,
                            )
                    ),
            )
        for ((name, c) in settings) {
            val engine = ClassicEngine(dictionary, c, random = kotlin.random.Random(o.seed))
            out[name] =
                measureLatency(
                    sample,
                    keyboard,
                    o.typist,
                    o.seed,
                    episode,
                    Predictor { engine.predictNow(it) },
                )
        }
        return out
    }

    private data class SweepOutcome(
        val text: String,
        val recommended: Map<String, String>?,
        val json: Map<String, Any>,
    )

    private fun runSweep(): SweepOutcome {
        val raw = Corpus.sample(o.heldout, o.sweepSentences, 0, o.seed, Split.Dev)
        val sentences = raw.map { Corpus.parse(it, dictionary, o.names) }
        val grid = Sweep.grid(o.sweepGrid)
        val options =
            episode.copy(completion = false, nextWord = false, phraseWords = 0, failureLimit = 0)
        val points = ArrayList<SweepPoint>()
        val t0 = System.nanoTime()
        for ((i, overrides) in grid.withIndex()) {
            val result =
                evaluator.evaluate(
                    sentences,
                    options,
                    factory(ConfigOverrides.apply(baseConfig, overrides)),
                )
            points.add(SweepPoint(overrides, result.metrics))
            if (System.console() != null) {
                System.err.print(
                    "\rsweep ${i + 1}/${grid.size}  (${((System.nanoTime() - t0) / 1e9).toInt()} s)"
                )
            }
        }
        if (System.console() != null) System.err.println()
        val defaultPoint = points.firstOrNull {
            ConfigOverrides.apply(baseConfig, it.overrides) == baseConfig
        }
        val recommended = Sweep.recommend(points, o.targetFalseCorrections)
        val text = buildString {
            append(
                "== Sweep: ${grid.size} points x ${sentences.size} sentences (dev split), no completion/next-word ==\n"
            )
            append("grid: ${o.sweepGrid}\n")
            append(Sweep.table(points, defaultPoint, recommended))
            append("target: false corrections <= ${o.targetFalseCorrections} per 1000 words -> ")
            append(
                recommended?.let { "recommended ${it.overrides}" } ?: "no point reaches the target"
            )
            append('\n')
        }
        Files.writeString(o.out.resolve("sweep.csv"), Sweep.csv(points))
        val json =
            linkedMapOf<String, Any>(
                "grid" to o.sweepGrid,
                "sentences" to sentences.size,
                "targetFalseCorrectionsPer1000" to o.targetFalseCorrections,
                "points" to
                    points.map {
                        linkedMapOf(
                            "overrides" to it.overrides,
                            "wordAccuracy" to it.accuracy,
                            "correctionRate" to it.stats.correctionRate,
                            "falseCorrectionsPer1000" to it.falsePer1000,
                            "falseCorrectionsInDictionaryPer1000" to it.falseInDictPer1000,
                            "wrongFixesPer1000" to it.wrongFixPer1000,
                            "top3Recall" to it.stats.top3Recall,
                        )
                    },
                "pareto" to Sweep.pareto(points).map { it.overrides },
                "recommended" to (recommended?.overrides ?: emptyMap<String, String>()),
            )
        return SweepOutcome(text, recommended?.overrides, json)
    }

    companion object {
        fun mapDictionary(path: Path): Dictionary {
            requireFile(path, "dictionary")
            return FileChannel.open(path, StandardOpenOption.READ).use { ch ->
                Dictionary.open(ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()))
            }
        }

        fun requireFile(path: Path, what: String): Path {
            require(Files.isRegularFile(path)) {
                "$what not found: ${path.toAbsolutePath()} (run from the repository root; held-out data is git-ignored, see docs/DATA_SOURCES.md)"
            }
            return path
        }
    }
}
