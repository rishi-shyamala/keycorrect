package dev.keycorrect.tools.eval

import dev.keycorrect.core.decoder.rerank.CandidateScorer
import dev.keycorrect.core.decoder.rerank.NeuralReranker
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.PredictResult
import dev.keycorrect.core.llama.LlamaLibrary
import dev.keycorrect.core.llama.LlamaModel
import dev.keycorrect.core.llama.LlamaSession
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Counters of one reranked run: decodes seen, decodes refined, and the scorer's own timings. */
class RerankStats {
    val decodes = AtomicLong()
    val applied = AtomicLong()
    val cacheHits = AtomicLong()
    val noAnswer = AtomicLong()
    private var nanos = LongArray(1 shl 14)
    private var n = 0

    @Synchronized
    fun recordCall(dt: Long) {
        if (n == nanos.size) nanos = nanos.copyOf(n * 2)
        nanos[n++] = dt
    }

    @Synchronized fun latency(): LatencyStats = LatencyStats.of(nanos, n)

    /** Share of recorded scorer calls that took longer than [ms]. */
    @Synchronized
    fun shareOver(ms: Double): Double {
        if (n == 0) return 0.0
        val limit = (ms * 1e6).toLong()
        var over = 0
        for (i in 0 until n) if (nanos[i] > limit) over++
        return over.toDouble() / n
    }
}

/**
 * A [CandidateScorer] on a [LlamaSession]. Only ever called from the one scorer thread, which is
 * also the only thread that touches [session]. With a [cache], a call whose every answer is known
 * returns at once (sweeps; those calls are not timed).
 */
class LlamaScorer(
    private val session: LlamaSession,
    private val stats: RerankStats,
    private val cache: ConcurrentHashMap<String, Float>?,
) : CandidateScorer {
    override fun score(context: String, candidates: List<String>, timeoutMs: Long): FloatArray? {
        if (cache != null) {
            val hit = FloatArray(candidates.size)
            var all = true
            for (i in candidates.indices) {
                val v = cache[context + SEP + candidates[i]]
                if (v == null) {
                    all = false
                    break
                }
                hit[i] = v
            }
            if (all) {
                stats.cacheHits.incrementAndGet()
                return hit
            }
        }
        val t0 = System.nanoTime()
        val scores = session.score(context, candidates, timeoutMs)
        stats.recordCall(System.nanoTime() - t0)
        if (scores == null) {
            stats.noAnswer.incrementAndGet()
        } else if (cache != null) {
            for (i in candidates.indices) cache[context + SEP + candidates[i]] = scores[i]
        }
        return scores
    }

    private companion object {
        const val SEP = '\u0000'
    }
}

/**
 * Sends every decode through the production [NeuralReranker], as the keyboard would with the
 * engine allowed everywhere: the classic result is computed first, then refined (re-decoded with the
 * language-model bonus) when the scorer answers.
 */
class RerankingPredictor(
    private val inner: Predictor,
    private val reranker: NeuralReranker,
    private val stats: RerankStats,
) : Predictor {
    override fun predict(request: PredictRequest): PredictResult {
        val result = inner.predict(request)
        stats.decodes.incrementAndGet()
        val refined =
            reranker.refine(request.copy(engineAllowed = true), result, { false }) {
                inner.predict(it)
            }
        if (refined == null) return result
        stats.applied.incrementAndGet()
        return refined
    }
}

/** Per-word comparison of a reranked run against the classic one. */
class Changes(
    val words: Int,
    /** Committed word differs (case-sensitive). */
    val changed: Int,
    /** Typed wrong, classic missed it, reranked fixed it. */
    val correctionsGained: Int,
    /** Typed wrong, classic fixed it, reranked did not. */
    val correctionsLost: Int,
    /** Typed right, classic changed it wrongly, reranked kept it. */
    val falseCorrectionsAvoided: Int,
    /** Typed right, classic kept it, reranked changed it wrongly. */
    val falseCorrectionsIntroduced: Int,
) {
    val net
        get() = correctionsGained + falseCorrectionsAvoided - correctionsLost - falseCorrectionsIntroduced

    companion object {
        fun of(classic: List<WordDecision>, reranked: List<WordDecision>): Changes {
            require(classic.size == reranked.size) { "arms typed different words" }
            var changed = 0
            var cg = 0
            var cl = 0
            var fa = 0
            var fi = 0
            for (i in classic.indices) {
                val a = classic[i]
                val b = reranked[i]
                check(a.sentence == b.sentence && a.token == b.token) { "arms are misaligned" }
                if (a.committed != b.committed) changed++
                if (a.finalCorrect == b.finalCorrect) continue
                if (a.literalCorrect) {
                    if (b.finalCorrect) fa++ else fi++
                } else {
                    if (b.finalCorrect) cg++ else cl++
                }
            }
            return Changes(classic.size, changed, cg, cl, fa, fi)
        }
    }
}

/** One reranker configuration of a run. */
data class RerankConfig(val lmWeight: Float, val pool: Int, val contextWords: Int) {
    override fun toString() = "lmWeight=$lmWeight pool=$pool contextWords=$contextWords"
}

/** The rerank arm of the tap eval: classic vs the production reranker with a llama scorer. */
class RerankArm(
    private val o: Options,
    private val evaluator: Evaluator,
    private val episode: EpisodeOptions,
    private val classicFactory: () -> Predictor,
) {
    class Outcome(
        val text: String,
        val json: Map<String, Any>,
        val failuresCsv: Map<String, String>,
        val sweepCsv: String,
    )

    fun run(sentences: List<ParsedSentence>, classic: EvalResult): Outcome {
        val modelPath = EvalMain.requireFile(requireNotNull(o.rerankModel), "rerank model")
        val lib = EvalMain.requireFile(o.llamaLib, "llama library (tools/llama/build-host.sh)")
        LlamaLibrary.load(lib.toAbsolutePath().normalize().toString())
        val configs = buildList {
            for (ctx in o.rerankContextWords) {
                for (pool in o.pools.sortedDescending()) {
                    for (w in o.lmWeights) add(RerankConfig(w, pool, ctx))
                }
            }
        }
        val scorerThread = Executors.newSingleThreadExecutor { r ->
            Thread(r, "rerank-scorer").also { it.isDaemon = true }
        }
        val cache = if (o.rerankCache) ConcurrentHashMap<String, Float>() else null
        val text = StringBuilder()
        val json = LinkedHashMap<String, Any>()
        val failures = LinkedHashMap<String, String>()
        val rows = ArrayList<Map<String, Any>>()
        val csv =
            StringBuilder(
                "lm_weight,pool,context_words,word_accuracy,correction_rate,fc_per_1000,top3,top1," +
                    "ksr_completion,corrections_gained,corrections_lost,fc_avoided,fc_introduced," +
                    "changed,scorer_calls,p50_ms,p95_ms,p99_ms,no_answer,applied,decodes\n"
            )
        val summary = StringBuilder()
        try {
            LlamaModel.openFile(modelPath.toAbsolutePath().normalize().toString()).use { model ->
                model.newSession(SESSION_CTX, o.llamaThreads).use { session ->
                    json["model"] = modelPath.fileName.toString()
                    json["modelDescription"] = model.description
                    json["deadlineMs"] = o.rerankDeadlineMs
                    json["llamaThreads"] = o.llamaThreads
                    json["cache"] = o.rerankCache
                    // Warm the model once so the first config's latency isn't the page-in.
                    warm(scorerThread, session)
                    summary.append(
                        "== Rerank arm: ${modelPath.fileName} (${model.description}), deadline " +
                            "${o.rerankDeadlineMs} ms, ${o.llamaThreads} llama threads, " +
                            "scorer calls ${if (cache != null) "cached across configs" else "uncached"} ==\n"
                    )
                    summary.append(tableHeader())
                    summary.append(tableRow("classic", classic.metrics.all, null, null))
                    for (config in configs) {
                        val stats = RerankStats()
                        val scorer = LlamaScorer(session, stats, cache)
                        val factory: () -> Predictor = {
                            RerankingPredictor(
                                classicFactory(),
                                NeuralReranker(
                                    scorer,
                                    scorerThread,
                                    { 1f },
                                    lmWeight = config.lmWeight,
                                    deadlineMs = o.rerankDeadlineMs,
                                    contextWords = config.contextWords,
                                    poolSize = config.pool,
                                ),
                                stats,
                            )
                        }
                        val t0 = System.nanoTime()
                        val result = evaluator.evaluate(sentences, episode, factory, true)
                        scorerThread.submit {}.get() // a late answer must finish before the next run
                        val seconds = (System.nanoTime() - t0) / 1e9
                        val changes = Changes.of(classic.decisions, result.decisions)
                        val title = "Results: reranked $config"
                        text.append(Report.text(title, result.metrics, o.nextWord)).append('\n')
                        text.append(comparisonText(config, classic, result, changes, stats))
                        text.append('\n')
                        val latency = stats.latency()
                        val label = "lw=${fmt(config.lmWeight)} pool=${config.pool} ctx=${config.contextWords}"
                        summary.append(tableRow(label, result.metrics.all, changes, latency))
                        System.err.println(
                            String.format(
                                Locale.ROOT,
                                "rerank %s: acc %.2f%% (classic %.2f%%), %d scorer calls, %.0f s",
                                label,
                                result.metrics.all.wordAccuracy * 100,
                                classic.metrics.all.wordAccuracy * 100,
                                latency.count,
                                seconds,
                            )
                        )
                        val entry = LinkedHashMap<String, Any>()
                        entry["lmWeight"] = config.lmWeight
                        entry["pool"] = config.pool
                        entry["contextWords"] = config.contextWords
                        entry.putAll(Report.metricsMap(result.metrics, o.nextWord))
                        entry["changes"] = changesMap(changes)
                        entry["decodes"] = stats.decodes.get()
                        entry["refinedDecodes"] = stats.applied.get()
                        entry["scorerCacheHits"] = stats.cacheHits.get()
                        entry["scorerNoAnswer"] = stats.noAnswer.get()
                        entry["scorerLatency"] = latency.toMap()
                        entry["scorerShareOver40ms"] = stats.shareOver(40.0)
                        entry["seconds"] = seconds
                        rows.add(entry)
                        csv.append(csvRow(config, result.metrics.all, changes, latency, stats))
                        if (o.failures > 0) {
                            failures["failures_reranked_${slug(config)}.csv"] =
                                Report.failuresCsv(result.failures)
                        }
                    }
                }
            }
        } finally {
            scorerThread.shutdown()
            awaitIdle(scorerThread)
        }
        json["classicBaseline"] = Report.statsMap(classic.metrics.all)
        json["configs"] = rows
        return Outcome(
            summary.toString() + "\n" + text.toString(),
            json,
            failures,
            csv.toString(),
        )
    }

    private fun warm(executor: ExecutorService, session: LlamaSession) {
        executor
            .submit { session.score("The quick brown fox", listOf("jumps", "jumped"), -1) }
            .get()
    }

    private fun awaitIdle(executor: ExecutorService) {
        executor.awaitTermination(10, TimeUnit.SECONDS)
    }

    private fun slug(c: RerankConfig) = "lw${fmt(c.lmWeight)}_pool${c.pool}_ctx${c.contextWords}"

    private fun fmt(f: Float) = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()

    private fun changesMap(c: Changes): Map<String, Any> =
        linkedMapOf(
            "words" to c.words,
            "changedCommittedWords" to c.changed,
            "correctionsGained" to c.correctionsGained,
            "correctionsLost" to c.correctionsLost,
            "falseCorrectionsAvoided" to c.falseCorrectionsAvoided,
            "falseCorrectionsIntroduced" to c.falseCorrectionsIntroduced,
            "netWordsFixed" to c.net,
        )

    private fun tableHeader() =
        String.format(
            Locale.ROOT,
            "  %-22s %8s %8s %8s %8s %8s %7s %7s %7s %7s %8s %8s %8s\n",
            "config",
            "acc",
            "corr.",
            "FC/1000",
            "top-3",
            "top-1",
            "gained",
            "lost",
            "fcAvoid",
            "fcNew",
            "p50 ms",
            "p95 ms",
            "p99 ms",
        )

    private fun tableRow(
        name: String,
        s: Stats,
        c: Changes?,
        l: LatencyStats?,
    ): String =
        String.format(
            Locale.ROOT,
            "  %-22s %7.2f%% %7.2f%% %8.2f %7.2f%% %7.2f%% %7s %7s %7s %7s %8s %8s %8s\n",
            name,
            s.wordAccuracy * 100,
            s.correctionRate * 100,
            s.falseCorrectionsPer1000,
            s.top3Recall * 100,
            s.top1Recall * 100,
            c?.correctionsGained ?: "-",
            c?.correctionsLost ?: "-",
            c?.falseCorrectionsAvoided ?: "-",
            c?.falseCorrectionsIntroduced ?: "-",
            l?.let { String.format(Locale.ROOT, "%.1f", it.p50Ms) } ?: "-",
            l?.let { String.format(Locale.ROOT, "%.1f", it.p95Ms) } ?: "-",
            l?.let { String.format(Locale.ROOT, "%.1f", it.p99Ms) } ?: "-",
        )

    private fun csvRow(
        config: RerankConfig,
        s: Stats,
        c: Changes,
        l: LatencyStats,
        stats: RerankStats,
    ): String =
        String.format(
            Locale.ROOT,
            "%s,%d,%d,%.5f,%.5f,%.4f,%.5f,%.5f,%.5f,%d,%d,%d,%d,%d,%d,%.2f,%.2f,%.2f,%d,%d,%d\n",
            fmt(config.lmWeight),
            config.pool,
            config.contextWords,
            s.wordAccuracy,
            s.correctionRate,
            s.falseCorrectionsPer1000,
            s.top3Recall,
            s.top1Recall,
            s.ksrCompletion,
            c.correctionsGained,
            c.correctionsLost,
            c.falseCorrectionsAvoided,
            c.falseCorrectionsIntroduced,
            c.changed,
            l.count,
            l.p50Ms,
            l.p95Ms,
            l.p99Ms,
            stats.noAnswer.get(),
            stats.applied.get(),
            stats.decodes.get(),
        )

    private fun comparisonText(
        config: RerankConfig,
        classic: EvalResult,
        reranked: EvalResult,
        c: Changes,
        stats: RerankStats,
    ): String {
        val a = classic.metrics.all
        val b = reranked.metrics.all
        val sb = StringBuilder("== Reranked vs classic: $config ==\n")
        fun row(name: String, x: Double, y: Double, pct: Boolean) {
            val scale = if (pct) 100.0 else 1.0
            val unit = if (pct) "%" else ""
            sb.append(
                String.format(
                    Locale.ROOT,
                    "  %-40s %9.3f%s %9.3f%s %+9.3f\n",
                    name,
                    x * scale,
                    unit,
                    y * scale,
                    unit,
                    (y - x) * scale,
                )
            )
        }
        sb.append(String.format(Locale.ROOT, "  %-40s %10s %10s %10s\n", "", "classic", "reranked", "delta"))
        row("word accuracy", a.wordAccuracy, b.wordAccuracy, true)
        row("correction rate", a.correctionRate, b.correctionRate, true)
        row("false corrections per 1000", a.falseCorrectionsPer1000, b.falseCorrectionsPer1000, false)
        row("top-3 recall", a.top3Recall, b.top3Recall, true)
        row("top-1", a.top1Recall, b.top1Recall, true)
        if (a.completionWords > 0) row("keystroke savings (completion)", a.ksrCompletion, b.ksrCompletion, true)
        if (o.nextWord) {
            row("next word top-1", classic.metrics.nextWord.top1Rate, reranked.metrics.nextWord.top1Rate, true)
            row("next word top-3", classic.metrics.nextWord.top3Rate, reranked.metrics.nextWord.top3Rate, true)
        }
        sb.append(
            "  changed decisions over ${c.words} words: committed word differs ${c.changed}; " +
                "corrections gained ${c.correctionsGained}, lost ${c.correctionsLost}; " +
                "false corrections avoided ${c.falseCorrectionsAvoided}, introduced " +
                "${c.falseCorrectionsIntroduced}; net words fixed ${c.net}\n"
        )
        val l = stats.latency()
        sb.append(
            String.format(
                Locale.ROOT,
                "  scorer: %d calls (+%d cached), mean %.1f p50 %.1f p95 %.1f p99 %.1f max %.1f ms; " +
                    "%.1f%% over 40 ms; no answer %d; %d of %d decodes refined\n",
                l.count,
                stats.cacheHits.get(),
                l.meanMs,
                l.p50Ms,
                l.p95Ms,
                l.p99Ms,
                l.maxMs,
                stats.shareOver(40.0) * 100,
                stats.noAnswer.get(),
                stats.applied.get(),
                stats.decodes.get(),
            )
        )
        return sb.toString()
    }

    private companion object {
        /** KV cache of the scorer session; 40 words of context plus a candidate fit easily. */
        const val SESSION_CTX = 512
    }
}
