package dev.keycorrect.ime

import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.PredictResult
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scores words with a language model (docs/PHASE7.md, SCORE tier). Backed later by the engine
 * service (AIDL); the re-ranker only knows this interface.
 */
fun interface CandidateScorer {
    /**
     * For each of [candidates], the sum of log P over its tokens following [context]; null when
     * there is no answer (engine unavailable, [timeoutMs] exceeded). Blocking; called on the
     * re-ranker's scorer thread, never on the decoder thread.
     */
    fun score(context: String, candidates: List<String>, timeoutMs: Long): FloatArray?
}

/**
 * Extension point for completing the top candidate with a few words (the GENERATE tier). Not used
 * by [NeuralReranker] yet: it only carries the generator for the later phrase package.
 */
fun interface PhraseGenerator {
    /** Up to a few words that continue [context] after [top], or null for no answer. */
    fun complete(context: String, top: String, timeoutMs: Long): String?
}

/**
 * The SCORE tier of the prediction pipeline (docs/PHASE7.md "Pipeline"): re-ranks a finished
 * classic result with a language model.
 *
 * [refine] runs on the decoder thread right after the classic result was published. It hands the
 * literal and the candidates to the [scorer] on [scorerExecutor] and polls for the answer in
 * [POLL_MS] steps, giving up when the input is [stale] or after [RERANK_DEADLINE_MS]. On success it
 * turns the log-probabilities into `lmBonus = lmStrength * LM_WEIGHT * (lp - mean lp)` (so a
 * uniformly likely or unlikely set changes nothing) and re-decodes the request with it; the engine
 * adds the bonus before ranking and the autocorrect decision. Only one scorer call is in flight at
 * a time: while one is still running (a late answer after a deadline), [refine] returns null at
 * once, so typing never queues behind the model.
 *
 * Returns null (keep the classic result) when [lmStrength] is zero, the request doesn't allow an
 * engine, there is nothing to compare, the scorer has no answer in time, or the input moved on.
 * Typed text is never logged here.
 *
 * @param lmStrength the user's LM strength setting, read per call (0 switches the re-ranker off).
 * @param phraseGenerator reserved for phrase completion; unused by the re-ranker itself.
 */
class NeuralReranker(
    private val scorer: CandidateScorer,
    private val scorerExecutor: Executor,
    private val lmStrength: () -> Float,
    val phraseGenerator: PhraseGenerator? = null,
) {
    private val busy = AtomicBoolean(false)

    @Suppress("ReturnCount", "TooGenericExceptionCaught", "SwallowedException")
    // guard clauses; a failing scorer or a closed executor just means no answer (never log text)
    fun refine(
        request: PredictRequest,
        result: PredictResult,
        stale: () -> Boolean,
        redecode: (PredictRequest) -> PredictResult,
    ): PredictResult? {
        if (!request.engineAllowed) return null
        val strength = lmStrength()
        if (!(strength > 0f) || !strength.isFinite()) return null
        val texts = textsToScore(result)
        if (texts.size < 2) return null // one text has nothing to be compared with
        val context = lastWords(request.context.before, CONTEXT_WORDS)

        if (!busy.compareAndSet(false, true)) return null
        val job = Job()
        try {
            scorerExecutor.execute {
                try {
                    job.scores =
                        try {
                            scorer.score(context, texts, RERANK_DEADLINE_MS)
                        } catch (e: Exception) {
                            null
                        }
                } finally {
                    job.done = true
                    busy.set(false)
                }
            }
        } catch (e: RejectedExecutionException) {
            busy.set(false)
            return null
        }

        val lp = awaitScores(job, stale) ?: return null
        if (lp.size != texts.size || lp.any { !it.isFinite() }) return null
        val mean = lp.average().toFloat()
        val bonus = HashMap<String, Float>(texts.size * 2)
        for (i in texts.indices) bonus[texts[i]] = strength * LM_WEIGHT * (lp[i] - mean)
        if (stale()) return null
        val refined = redecode(request.copy(lmBonus = bonus))
        return if (stale()) null else refined
    }

    private class Job {
        @Volatile var done = false

        @Volatile var scores: FloatArray? = null
    }

    @Suppress("ReturnCount") // deadline / stale exits
    private fun awaitScores(job: Job, stale: () -> Boolean): FloatArray? {
        val deadline = System.nanoTime() + RERANK_DEADLINE_MS * NANOS_PER_MS
        while (!job.done) {
            if (stale() || System.nanoTime() >= deadline) return null
            try {
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return job.scores
    }

    /** The literal (if any) and the candidates: distinct non-blank texts, at most [MAX_TEXTS]. */
    private fun textsToScore(result: PredictResult): List<String> {
        val out = LinkedHashSet<String>()
        result.literal?.text?.let { if (it.isNotBlank()) out.add(it) }
        for (c in result.candidates) {
            if (out.size >= MAX_TEXTS) break
            if (c.text.isNotBlank()) out.add(c.text)
        }
        return out.toList()
    }

    companion object {
        /** How long the decoder thread waits for the scorer before keeping the classic result. */
        const val RERANK_DEADLINE_MS = 40L

        /** Scale of the language-model bonus against the classic fused score; tuned on the eval. */
        const val LM_WEIGHT = 1.0f

        /** The most texts sent to the scorer in one call. */
        const val MAX_TEXTS = 16

        /** The scorer sees about this many words before the cursor. */
        const val CONTEXT_WORDS = 40

        private const val POLL_MS = 5L
        private const val NANOS_PER_MS = 1_000_000L

        /**
         * The last [maxWords] words of [text], cut at a word boundary, without trailing whitespace
         * (the scorer adds the space before each candidate).
         */
        internal fun lastWords(text: CharSequence, maxWords: Int): String {
            val s = text.toString().trimEnd()
            var i = s.length
            var words = 0
            while (i > 0) {
                while (i > 0 && !s[i - 1].isWhitespace()) i--
                words++
                if (words == maxWords) break
                while (i > 0 && s[i - 1].isWhitespace()) i--
            }
            return s.substring(i)
        }
    }
}
