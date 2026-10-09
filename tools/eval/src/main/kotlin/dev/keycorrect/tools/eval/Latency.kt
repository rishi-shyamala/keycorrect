package dev.keycorrect.tools.eval

import java.util.Arrays

/** Percentiles of a list of call durations. */
class LatencyStats(
    val count: Int,
    val meanMs: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val maxMs: Double,
) {
    fun toMap(): Map<String, Any> =
        linkedMapOf(
            "count" to count,
            "meanMs" to meanMs,
            "p50Ms" to p50Ms,
            "p95Ms" to p95Ms,
            "p99Ms" to p99Ms,
            "maxMs" to maxMs,
        )

    companion object {
        fun of(nanos: LongArray, size: Int): LatencyStats {
            if (size == 0) return LatencyStats(0, 0.0, 0.0, 0.0, 0.0, 0.0)
            val sorted = nanos.copyOf(size)
            Arrays.sort(sorted)
            fun pct(p: Double) = sorted[minOf(size - 1, (p * size).toInt())] / 1e6
            return LatencyStats(
                size,
                sorted.average() / 1e6,
                pct(0.50),
                pct(0.95),
                pct(0.99),
                sorted[size - 1] / 1e6,
            )
        }
    }
}

class LatencyResult(val composing: LatencyStats, val nextWord: LatencyStats, val all: LatencyStats)

/** Times every `predict` call; composing and empty-composing calls are kept apart. */
class TimingPredictor(private val inner: Predictor) : Predictor {
    var enabled = false
    private var composing = LongArray(1 shl 16)
    private var composingN = 0
    private var next = LongArray(1 shl 14)
    private var nextN = 0

    override fun predict(
        request: dev.keycorrect.core.engineapi.PredictRequest
    ): dev.keycorrect.core.engineapi.PredictResult {
        if (!enabled) return inner.predict(request)
        val t0 = System.nanoTime()
        val result = inner.predict(request)
        val dt = System.nanoTime() - t0
        if (request.composing.isEmpty) {
            if (nextN == next.size) next = next.copyOf(nextN * 2)
            next[nextN++] = dt
        } else {
            if (composingN == composing.size) composing = composing.copyOf(composingN * 2)
            composing[composingN++] = dt
        }
        return result
    }

    fun result(): LatencyResult {
        val both = LongArray(composingN + nextN)
        System.arraycopy(composing, 0, both, 0, composingN)
        System.arraycopy(next, 0, both, composingN, nextN)
        return LatencyResult(
            LatencyStats.of(composing, composingN),
            LatencyStats.of(next, nextN),
            LatencyStats.of(both, both.size),
        )
    }
}

/**
 * Single-threaded latency of `predict` over [sentences]: one warm-up pass (JIT, caches), then a
 * timed pass in which the engine is called after every key (as on a real keyboard) and at every
 * word boundary. Runs on the calling thread; nothing else should compete for the CPU.
 */
fun measureLatency(
    sentences: List<ParsedSentence>,
    keyboard: EvalKeyboard,
    typistConfig: TypistConfig,
    seed: Long,
    options: EpisodeOptions,
    predictor: Predictor,
): LatencyResult {
    val timing = TimingPredictor(predictor)
    val runOptions =
        options.copy(
            exhaustivePrefixes = true,
            completion = true,
            nextWord = true,
            failureLimit = 0,
        )
    val runner =
        EpisodeRunner(timing, keyboard.layout, Typist(keyboard, typistConfig), runOptions, seed)
    repeat(2) { pass ->
        timing.enabled = pass == 1
        val sink = Metrics()
        sentences.forEachIndexed { i, s -> runner.run(i, s, sink) }
    }
    return timing.result()
}
