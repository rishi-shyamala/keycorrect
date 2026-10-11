package dev.keycorrect.tools.eval

import dev.keycorrect.core.decoder.ClassicConfig
import dev.keycorrect.core.decoder.ClassicEngine
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.TypingContext
import java.nio.file.Files
import java.util.Locale

/**
 * `--mode misspell`: how well the engine corrects cognitive misspellings (the Wikipedia list of
 * common misspellings, typed with key-centre touches and optional touch noise, with and without a
 * short neutral context), reported together with the spatial-noise sentence run (accuracy and false
 * corrections at typist sigma 0.25 / 0.35) as one joint objective. `--sweep` searches a config grid
 * for the best misspelling accuracy subject to the spatial constraints.
 */
class MisspellMain(private val o: Options) {
    private val keyboard = EvalKeyboard()
    private val dictionary = EvalMain.mapDictionary(o.dict)
    private val baseConfig = ConfigOverrides.apply(ClassicConfig(), o.config)
    private val misspellEvaluator = MisspellEvaluator(keyboard, o.seed, o.threads)
    private val conditions = MisspellEvaluator.conditions(o.misspellSigma, o.misspellDraws)
    private val spatialEpisode =
        EpisodeOptions(completion = false, nextWord = false, phraseWords = 0, failureLimit = 0)

    /** Metrics of one config: misspelling slices plus the spatial runs per typist sigma. */
    private class Joint(
        val misspell: MisspellResult,
        val spatial: List<Pair<Float, Metrics>>,
    ) {
        /** Mean accuracy over the key-centre conditions of the non-word entries (the objective). */
        val objective: Double
            get() = misspell.conditions.take(2).map { it.nonWord.accuracy }.average()

        fun spatialAt(sigma: Float): Metrics? = spatial.firstOrNull { it.first == sigma }?.second
    }

    private fun predictorFactory(config: ClassicConfig): () -> Predictor = {
        val engine = ClassicEngine(dictionary, config, random = kotlin.random.Random(o.seed))
        Predictor { engine.predictNow(it) }
    }

    private fun measure(
        config: ClassicConfig,
        entries: List<MisspellEntry>,
        sentences: List<ParsedSentence>,
        failureLimit: Int,
    ): Joint {
        val m =
            misspellEvaluator.evaluate(entries, conditions, predictorFactory(config), failureLimit)
        val spatial =
            o.spatialSigmas.map { s ->
                val typist = o.typist.copy(sigmaX = s, sigmaY = s)
                s to
                    Evaluator(keyboard, typist, o.seed, o.threads)
                        .evaluate(sentences, spatialEpisode, predictorFactory(config))
                        .metrics
            }
        return Joint(m, spatial)
    }

    /** Prints the ranked candidates of each probe word (key-centre taps) with their score parts. */
    private fun probe() {
        val engine = ClassicEngine(dictionary, baseConfig, random = kotlin.random.Random(o.seed))
        val typist =
            Typist(
                keyboard,
                TypistConfig(
                    sigmaX = 0f,
                    sigmaY = 0f,
                    omissionRate = 0f,
                    insertionRate = 0f,
                    transpositionRate = 0f,
                ),
            )
        for (word in o.probe) {
            val typed = typist.type(word.lowercase(), java.util.Random(1))
            val r =
                engine.predictRanked(
                    PredictRequest(
                        TypingContext(o.probeContext),
                        typed.input,
                        keyboard.layout,
                        8,
                        autocorrectAllowed = true,
                    )
                )
            println(
                "== $word (context '${o.probeContext}')  literal score ${fmt(r.literal?.score)}"
            )
            println("   autocorrect -> ${r.autocorrect?.text}")
            for (c in r.candidates) {
                val b = c.breakdown
                println(
                    String.format(
                        Locale.ROOT,
                        "   %-14s total %7.2f  spatial %6.2f  edits %6.2f (%d)  lm %6.2f  +%d chars",
                        c.text,
                        c.score,
                        b.spatial,
                        b.edits,
                        b.editCount,
                        b.lm,
                        b.completionChars,
                    )
                )
            }
        }
    }

    private fun fmt(v: Float?) = v?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "-"

    fun run() {
        if (o.probe.isNotEmpty()) return probe()
        val started = System.nanoTime()
        Files.createDirectories(o.out)
        val out = o.out.resolve("misspell")
        Files.createDirectories(out)
        val lines = Files.readAllLines(EvalMain.requireFile(o.misspellFile, "misspellings list"))
        val parsed = MisspellList.parse(lines, dictionary)
        val entries = MisspellList.select(parsed.entries, o.split)
        val report = StringBuilder()
        fun emit(text: String) {
            println(text)
            report.append(text).append('\n')
        }
        val st = parsed.stats
        emit(
            "KeyCorrect misspelling eval: ${entries.size} of ${st.entries} usable entries " +
                "(${o.split.name.lowercase()} split), seed ${o.seed}, dictionary ${o.dict.fileName}"
        )
        emit(
            "list: ${st.lines} `->` lines; dropped ${st.droppedBadTyped} (non-letter / <2 chars), " +
                "${st.droppedNoDictionaryCorrection} (no single-word dictionary correction), " +
                "${st.droppedDuplicate} (duplicate); ${st.entries} kept of which " +
                "${st.realWordEntries} are real-word misspellings (reported apart), " +
                "${st.multiCorrection} have several accepted corrections"
        )
        if (o.config.isNotEmpty()) emit("config overrides: ${o.config}")

        val json = LinkedHashMap<String, Any>()
        json["options"] =
            linkedMapOf(
                "split" to o.split.name.lowercase(),
                "seed" to o.seed,
                "entries" to entries.size,
                "configOverrides" to o.config,
                "misspellSigma" to o.misspellSigma,
                "draws" to o.misspellDraws,
                "contexts" to MisspellEvaluator.NEUTRAL_CONTEXTS,
            )
        json["list"] =
            linkedMapOf(
                "lines" to st.lines,
                "entries" to st.entries,
                "realWord" to st.realWordEntries,
                "multiCorrection" to st.multiCorrection,
                "droppedBadTyped" to st.droppedBadTyped,
                "droppedNoDictionaryCorrection" to st.droppedNoDictionaryCorrection,
                "droppedDuplicate" to st.droppedDuplicate,
            )

        var recommended: Map<String, String>? = null
        if (o.sweep) {
            val (text, rec, sweepJson) = runSweep(parsed.entries)
            emit(text)
            recommended = rec
            json["sweep"] = sweepJson
        }

        val sentences = sentences(o.split, o.sentences)
        val configs = LinkedHashMap<String, ClassicConfig>()
        configs[if (o.sweep) "default" else "run"] = baseConfig
        if (recommended != null) {
            configs["recommended"] = ConfigOverrides.apply(baseConfig, recommended)
        }
        for ((name, config) in configs) {
            val joint = measure(config, entries, sentences, o.misspellFailures)
            emit(
                "\n== Results: $name" + (if (name == "recommended") " $recommended" else "") + " =="
            )
            emit(text(joint, sentences.size))
            json[name] = jsonOf(joint)
            val file =
                if (configs.size == 1) "misspell_failures.csv" else "misspell_failures_$name.csv"
            Files.writeString(out.resolve(file), failuresCsv(joint.misspell.failures))
        }
        val seconds = (System.nanoTime() - started) / 1e9
        json["elapsedSeconds"] = seconds
        Files.writeString(out.resolve("misspell.json"), Json.write(json) + "\n")
        Files.writeString(out.resolve("misspell_report.txt"), report.toString())
        println(
            String.format(
                Locale.ROOT,
                "done in %.1f s; wrote %s",
                seconds,
                out.toAbsolutePath().normalize(),
            )
        )
    }

    private fun sentences(split: Split, count: Int): List<ParsedSentence> {
        if (o.spatialSigmas.isEmpty()) return emptyList()
        return Corpus.sample(
                EvalMain.requireFile(o.heldout, "held-out sentences"),
                count,
                o.offset,
                o.seed,
                split,
            )
            .map { Corpus.parse(it, dictionary, o.names) }
    }

    // ------------------------------------------------------------------ report

    private fun pct(v: Double) = String.format(Locale.ROOT, "%6.2f%%", 100 * v)

    private fun text(j: Joint, sentenceCount: Int): String = buildString {
        append("Non-word misspellings (autocorrect applied == a listed correction):\n")
        append(
            String.format(
                Locale.ROOT,
                "  %-34s %7s %8s %8s %8s %8s %8s\n",
                "condition",
                "trials",
                "accuracy",
                "top-3",
                "abstain",
                "wrong",
                "top-1",
            )
        )
        for (c in j.misspell.conditions) {
            appendRow(c.condition.name, c.nonWord)
        }
        val real = j.misspell.conditions.first().realWord
        if (real.trials > 0) {
            append("Real-word misspellings (the typed word is itself in the dictionary):\n")
            for (c in j.misspell.conditions) appendRow(c.condition.name, c.realWord)
        }
        append(
            "\nBy slip category, non-word entries (accuracy / top-3 / wrong), key-centre taps:\n"
        )
        append(
            String.format(
                Locale.ROOT,
                "  %-34s %6s | %8s %8s %8s | %8s %8s %8s\n",
                "category",
                "n",
                "none:acc",
                "top-3",
                "wrong",
                "ctx:acc",
                "top-3",
                "wrong",
            )
        )
        val none = j.misspell.conditions[0].byCategory
        val ctx = j.misspell.conditions[1].byCategory
        val names = MisspellCategory.ORDER.filter { it in none }
        for (name in names) {
            val a = none.getValue(name)
            val b = ctx.getValue(name)
            append(
                String.format(
                    Locale.ROOT,
                    "  %-34s %6d | %8s %8s %8s | %8s %8s %8s\n",
                    name,
                    a.trials,
                    pct(a.accuracy),
                    pct(a.top3Recall),
                    pct(a.wrongRate),
                    pct(b.accuracy),
                    pct(b.top3Recall),
                    pct(b.wrongRate),
                )
            )
        }
        if (j.spatial.isNotEmpty()) {
            append(
                "\nSpatial-noise run ($sentenceCount sentences, typist cognitive typos 1% each):\n"
            )
            for ((sigma, m) in j.spatial) {
                val s = m.all
                append(
                    String.format(
                        Locale.ROOT,
                        "  typist sigma %.2f: accuracy %s  correction %s  false corrections/1000 %.2f  top-3 %s\n",
                        sigma,
                        pct(s.wordAccuracy),
                        pct(s.correctionRate),
                        s.falseCorrectionsPer1000,
                        pct(s.top3Recall),
                    )
                )
            }
        }
    }

    private fun StringBuilder.appendRow(name: String, s: MisspellStats) {
        append(
            String.format(
                Locale.ROOT,
                "  %-34s %7d %8s %8s %8s %8s %8s\n",
                name,
                s.trials,
                pct(s.accuracy),
                pct(s.top3Recall),
                pct(s.abstainRate),
                pct(s.wrongRate),
                pct(s.top1Rate),
            )
        )
    }

    private fun statsMap(s: MisspellStats): Map<String, Any> =
        linkedMapOf(
            "trials" to s.trials,
            "accuracy" to s.accuracy,
            "top3Recall" to s.top3Recall,
            "top1Rate" to s.top1Rate,
            "abstainRate" to s.abstainRate,
            "wrongCorrectionRate" to s.wrongRate,
        )

    private fun jsonOf(j: Joint): Map<String, Any> =
        linkedMapOf(
            "conditions" to
                j.misspell.conditions.associate { c ->
                    c.condition.name to
                        linkedMapOf(
                            "nonWord" to statsMap(c.nonWord),
                            "realWord" to statsMap(c.realWord),
                            "byCategory" to c.byCategory.mapValues { statsMap(it.value) },
                        )
                },
            "spatial" to
                j.spatial.associate { (sigma, m) ->
                    "typistSigma$sigma" to
                        linkedMapOf(
                            "wordAccuracy" to m.all.wordAccuracy,
                            "correctionRate" to m.all.correctionRate,
                            "falseCorrectionsPer1000" to m.all.falseCorrectionsPer1000,
                            "top3Recall" to m.all.top3Recall,
                        )
                },
        )

    private fun failuresCsv(rows: List<MisspellFailure>): String = buildString {
        append("condition,context,typed,literal,applied,top3,corrections,category,real_word\n")
        for (r in rows) {
            append(
                listOf(
                        r.condition,
                        r.context.trim(),
                        r.entry.typed,
                        r.literal,
                        r.applied ?: "",
                        r.top.joinToString("|"),
                        r.entry.corrections.joinToString("|"),
                        r.entry.category,
                        r.entry.realWord.toString(),
                    )
                    .joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" }
            )
            append('\n')
        }
    }

    // ------------------------------------------------------------------ sweep

    private data class SweepRow(val overrides: Map<String, String>, val joint: Joint) {
        fun spatialAcc(sigma: Float) = joint.spatialAt(sigma)?.all?.wordAccuracy ?: 0.0

        fun spatialFc(sigma: Float) = joint.spatialAt(sigma)?.all?.falseCorrectionsPer1000 ?: 0.0
    }

    private data class SweepOutcome(
        val text: String,
        val recommended: Map<String, String>?,
        val json: Map<String, Any>,
    )

    private fun runSweep(all: List<MisspellEntry>): SweepOutcome {
        val dev = MisspellList.select(all, Split.Dev)
        val sentences = sentences(Split.Dev, o.sweepSentences)
        val grid = Sweep.grid(o.sweepGrid)
        val primary = o.spatialSigmas.firstOrNull()
        val rows = ArrayList<SweepRow>()
        val t0 = System.nanoTime()
        val baseline = measure(baseConfig, dev, sentences, 0)
        for ((i, overrides) in grid.withIndex()) {
            val cfg = ConfigOverrides.apply(baseConfig, overrides)
            rows.add(SweepRow(overrides, measure(cfg, dev, sentences, 0)))
            System.err.print(
                "\rmisspell sweep ${i + 1}/${grid.size}  (${((System.nanoTime() - t0) / 1e9).toInt()} s)"
            )
        }
        System.err.println()
        val baseAcc = primary?.let { baseline.spatialAt(it)?.all?.wordAccuracy } ?: 0.0
        val baseFc = primary?.let { baseline.spatialAt(it)?.all?.falseCorrectionsPer1000 } ?: 0.0
        // The false-correction budget is the target, or the baseline's own rate when that is higher
        // (the corpus' unknown proper names set a floor the decoder cannot be blamed for).
        val fcLimit = maxOf(o.targetFalseCorrections, baseFc)
        fun feasible(r: SweepRow): Boolean =
            primary == null ||
                (r.spatialAcc(primary) >= baseAcc - o.maxSpatialDrop / 100.0 &&
                    r.spatialFc(primary) <= fcLimit + 1e-9)
        val best = rows.filter(::feasible).maxByOrNull { it.joint.objective }
        val text = buildString {
            append(
                "== Joint sweep: ${grid.size} points, ${dev.size} dev entries, ${sentences.size} dev sentences ==\n"
            )
            append("grid: ${o.sweepGrid}\n")
            append(
                String.format(
                    Locale.ROOT,
                    "baseline (current config): misspell objective %s, spatial sigma %s: accuracy %s, FC/1000 %.2f\n",
                    pct(baseline.objective),
                    primary,
                    pct(baseAcc),
                    primary?.let { baseline.spatialAt(it)?.all?.falseCorrectionsPer1000 } ?: 0.0,
                )
            )
            append(
                "constraint: spatial accuracy at sigma $primary >= baseline - ${o.maxSpatialDrop} points, " +
                    "false corrections <= ${String.format(Locale.ROOT, "%.2f", fcLimit)}/1000 (max of --target-fc and the baseline)\n"
            )
            append(
                String.format(
                    Locale.ROOT,
                    "  %-72s %8s %8s %8s %8s %8s %8s %6s\n",
                    "point",
                    "objective",
                    "none",
                    "ctx",
                    "wrong",
                    "sp.acc",
                    "sp.FC",
                    "ok",
                )
            )
            for (r in rows.sortedByDescending { it.joint.objective }) {
                val c = r.joint.misspell.conditions
                append(
                    String.format(
                        Locale.ROOT,
                        "  %-72s %8s %8s %8s %8s %8s %8.2f %6s %s\n",
                        r.overrides.entries.joinToString(" ") {
                            "${it.key.substringAfter('.')}=${it.value}"
                        },
                        pct(r.joint.objective),
                        pct(c[0].nonWord.accuracy),
                        pct(c[1].nonWord.accuracy),
                        pct(c[1].nonWord.wrongRate),
                        primary?.let { pct(r.spatialAcc(it)) } ?: "",
                        primary?.let { r.spatialFc(it) } ?: 0.0,
                        if (feasible(r)) "yes" else "no",
                        if (r === best) "<recommended>" else "",
                    )
                )
            }
            append("-> ${best?.let { "recommended ${it.overrides}" } ?: "no feasible point"}\n")
        }
        val csv = StringBuilder()
        val keys = rows.flatMap { it.overrides.keys }.distinct()
        csv.append(keys.joinToString(","))
            .append(",objective,none_acc,ctx_acc,ctx_wrong,ctx_top3,noisy_acc")
        for (s in o.spatialSigmas) csv.append(",spatial_acc_$s,spatial_fc_$s")
        csv.append('\n')
        for (r in rows) {
            val c = r.joint.misspell.conditions
            csv.append(keys.joinToString(",") { r.overrides[it] ?: "" })
            csv.append(
                String.format(
                    Locale.ROOT,
                    ",%.5f,%.5f,%.5f,%.5f,%.5f,%.5f",
                    r.joint.objective,
                    c[0].nonWord.accuracy,
                    c[1].nonWord.accuracy,
                    c[1].nonWord.wrongRate,
                    c[1].nonWord.top3Recall,
                    c.getOrNull(2)?.nonWord?.accuracy ?: 0.0,
                )
            )
            for (s in o.spatialSigmas) {
                csv.append(
                    String.format(Locale.ROOT, ",%.5f,%.3f", r.spatialAcc(s), r.spatialFc(s))
                )
            }
            csv.append('\n')
        }
        Files.writeString(o.out.resolve("misspell").resolve("misspell_sweep.csv"), csv.toString())
        val json =
            linkedMapOf<String, Any>(
                "grid" to o.sweepGrid,
                "devEntries" to dev.size,
                "devSentences" to sentences.size,
                "recommended" to (best?.overrides ?: emptyMap<String, String>()),
            )
        return SweepOutcome(text, best?.overrides, json)
    }
}
