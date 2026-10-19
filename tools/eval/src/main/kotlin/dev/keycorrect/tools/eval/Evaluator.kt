package dev.keycorrect.tools.eval

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class EvalResult(
    val metrics: Metrics,
    val failures: List<FailureRow>,
    /** Per-word outcomes ordered by (sentence, token); empty unless asked for. */
    val decisions: List<WordDecision> = emptyList(),
)

/**
 * Runs episodes over many sentences on several threads. Each worker owns a predictor (the engine
 * reuses scratch buffers) and pulls sentences in increasing order; counters are summed at the end,
 * so the result does not depend on scheduling.
 */
class Evaluator(
    private val keyboard: EvalKeyboard,
    typistConfig: TypistConfig,
    private val seed: Long,
    private val threads: Int,
) {
    private val typist = Typist(keyboard, typistConfig)

    fun evaluate(
        sentences: List<ParsedSentence>,
        options: EpisodeOptions,
        predictorFactory: () -> Predictor,
        recordDecisions: Boolean = false,
    ): EvalResult {
        val next = AtomicInteger(0)
        val workers = threads.coerceIn(1, maxOf(1, sentences.size))
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val futures =
                List(workers) {
                    pool.submit(
                        Callable {
                            val metrics = newMetrics(recordDecisions)
                            val failures = ArrayList<FailureRow>()
                            val runner =
                                EpisodeRunner(
                                    predictorFactory(),
                                    keyboard.layout,
                                    typist,
                                    options,
                                    seed,
                                )
                            while (true) {
                                val i = next.getAndIncrement()
                                if (i >= sentences.size) break
                                runner.run(i, sentences[i], metrics, failures)
                            }
                            metrics to failures
                        }
                    )
                }
            val total = newMetrics(recordDecisions)
            val rows = ArrayList<FailureRow>()
            for (f in futures) {
                val (m, r) = f.get()
                total.merge(m)
                rows.addAll(r)
            }
            rows.sortWith(compareBy({ it.sentence }))
            return EvalResult(
                total,
                if (rows.size > options.failureLimit) rows.subList(0, options.failureLimit)
                else rows,
                total.decisions?.sortedWith(compareBy({ it.sentence }, { it.token })).orEmpty(),
            )
        } finally {
            pool.shutdown()
        }
    }

    private fun newMetrics(recordDecisions: Boolean) =
        Metrics().also { if (recordDecisions) it.decisions = ArrayList() }
}
