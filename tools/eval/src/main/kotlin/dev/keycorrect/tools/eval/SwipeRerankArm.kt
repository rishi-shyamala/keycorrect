@file:Suppress("MagicNumber") // report formatting: percentages, milliseconds

package dev.keycorrect.tools.eval

import dev.keycorrect.core.decoder.ClassicEngine
import dev.keycorrect.core.decoder.rerank.NeuralReranker
import dev.keycorrect.core.engineapi.GestureRequest
import dev.keycorrect.core.engineapi.PredictResult
import dev.keycorrect.core.llama.LlamaLibrary
import dev.keycorrect.core.llama.LlamaModel
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * `--mode swipe --real ... --rerank MODEL.gguf`: the swipe decode of [engine] followed by the
 * production [NeuralReranker.refineGesture], for every combination of `--lm-weight` (the effective
 * swipe weight, at strength 1) and `--rerank-context-words` (no wider pool: gestures score the
 * final candidates only), against the same decode without it (docs/EVAL.md, "Reranked swipes").
 * Scores are reused across configs (the scorer is deterministic), so only the first config pays for
 * the model; the latency table counts real scorer calls only.
 */
class SwipeRerankArm(
    private val o: Options,
    private val engine: ClassicEngine,
    private val evaluator: SwipeEvaluator,
    private val warmup: Int,
) {
    /** Top-1 text of every decode, in call order (warm-up included). */
    private class Tops(private val inner: GestureDecoderFn) : GestureDecoder {
        val top1 = ArrayList<String>()

        override fun decode(request: GestureRequest): PredictResult {
            val r = inner(request)
            top1.add(r.candidates.firstOrNull()?.text.orEmpty())
            return r
        }
    }

    private fun interface GestureDecoderFn {
        operator fun invoke(request: GestureRequest): PredictResult
    }

    @Suppress("LongMethod") // one linear driver: setup, per-config loop, table
    fun run(set: RealSwipeSet): String {
        val modelPath = EvalMain.requireFile(requireNotNull(o.rerankModel), "rerank model")
        val lib = EvalMain.requireFile(o.llamaLib, "llama library (tools/llama/build-host.sh)")
        LlamaLibrary.load(lib.toAbsolutePath().normalize().toString())
        val configs = buildList {
            for (ctx in o.rerankContextWords) {
                for (w in o.lmWeights) add(RerankConfig(w, 0, ctx))
            }
        }
        val baseTops = Tops { engine.decodeGestureNow(it) }
        val (base, _) = evaluator.evaluateReal(set.swipes, baseTops, warmup)
        val baseline = baseTops.top1.drop(warmup)
        val targets = set.swipes.map { normalise(it.target) }
        val sb = StringBuilder()
        sb.append("== Reranked swipes: ").append(modelPath.fileName).append(" ==\n")
        sb.append(header())
        sb.append(row("no rerank", base, null, null, baseline.size))
        val scorerThread = Executors.newSingleThreadExecutor { r ->
            Thread(r, "rerank-scorer").also { it.isDaemon = true }
        }
        val cache = if (o.rerankCache) ConcurrentHashMap<String, Float>() else null
        try {
            LlamaModel.openFile(modelPath.toAbsolutePath().normalize().toString()).use { model ->
                model.newSession(SESSION_CTX, o.llamaThreads).use { session ->
                    scorerThread
                        .submit {
                            session.score("The quick brown fox", listOf("jumps", "jumped"), -1)
                        }
                        .get()
                    for (c in configs) {
                        val stats = RerankStats()
                        val reranker =
                            NeuralReranker(
                                LlamaScorer(session, stats, cache),
                                scorerThread,
                                { 1f },
                                gestureLmWeight = c.lmWeight,
                                deadlineMs = o.rerankDeadlineMs,
                                contextWords = c.contextWords,
                            )
                        val tops = Tops { req ->
                            val r = engine.decodeGestureNow(req)
                            reranker.refineGesture(req.copy(engineAllowed = true), r) { false } ?: r
                        }
                        val (res, _) = evaluator.evaluateReal(set.swipes, tops, warmup)
                        sb.append(
                            row(
                                "lw=${fmt(c.lmWeight)} ctx=${c.contextWords}",
                                res,
                                tops.top1.drop(warmup).let { gainLost(targets, baseline, it) },
                                stats,
                                baseline.size,
                            )
                        )
                        System.err.println("swipe rerank $c done")
                    }
                }
            }
        } finally {
            scorerThread.shutdown()
        }
        return sb.toString()
    }

    private fun gainLost(targets: List<String>, a: List<String>, b: List<String>): IntArray {
        var gained = 0
        var lost = 0
        for (i in a.indices) {
            val ra = normalise(a[i]) == targets[i]
            val rb = normalise(b[i]) == targets[i]
            if (!ra && rb) gained++
            if (ra && !rb) lost++
        }
        return intArrayOf(gained, lost)
    }

    private fun header() =
        String.format(
            Locale.ROOT,
            "  %-26s %8s %8s %8s %7s %6s %9s %9s %9s\n",
            "config",
            "top-1",
            "top-3",
            "wrong",
            "gained",
            "lost",
            "z(McN)",
            "scorer p50",
            "p95 ms",
        )

    private fun row(
        name: String,
        r: SwipeResult,
        gl: IntArray?,
        stats: RerankStats?,
        n: Int,
    ): String {
        val l = stats?.latency()
        val z = gl?.let { (g, lo) ->
            if (g + lo == 0) 0.0 else (g - lo) / sqrt((g + lo).toDouble())
        }
        return String.format(
            Locale.ROOT,
            "  %-26s %7.2f%% %7.2f%% %7.2f%% %7s %6s %9s %9s %9s  (n=%d)\n",
            name,
            r.top1Pct,
            r.top3Pct,
            r.wrongCommitPct,
            gl?.get(0) ?: "-",
            gl?.get(1) ?: "-",
            z?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "-",
            l?.let { String.format(Locale.ROOT, "%.1f", it.p50Ms) } ?: "-",
            l?.let { String.format(Locale.ROOT, "%.1f", it.p95Ms) } ?: "-",
            n,
        )
    }

    private fun fmt(f: Float) = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()

    private fun normalise(s: String) = s.replace('’', '\'').lowercase()

    private companion object {
        const val SESSION_CTX = 512
    }
}
