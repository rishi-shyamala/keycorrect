package dev.keycorrect.core.decoder.rerank

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
 * [POLL_MS] steps, giving up when the input is [stale] or after [deadlineMs]. On success it turns
 * the log-probabilities into `lmBonus = lmStrength * lmWeight * (lp - mean lp)` (so a uniformly
 * likely or unlikely set changes nothing) and re-decodes the request with it; the engine adds the
 * bonus before ranking and the autocorrect decision. Only one scorer call is in flight at a time:
 * while one is still running (a late answer after a deadline), [refine] returns null at once, so
 * typing never queues behind the model.
 *
 * Returns null (keep the classic result) when [lmStrength] is zero, the request doesn't allow an
 * engine, there is nothing to compare, the scorer has no answer in time, or the input moved on.
 * Typed text is never logged here.
 *
 * @param lmStrength the user's LM strength setting, read per call (0 switches the re-ranker off).
 * @param phraseGenerator reserved for phrase completion; unused by the re-ranker itself.
 * @param lmWeight scale of the bonus against the classic fused score.
 * @param deadlineMs how long [refine] waits for the scorer before keeping the classic result.
 * @param maxTexts the most texts (literal plus candidates) sent to the scorer in one call.
 * @param contextWords the scorer sees about this many words before the cursor.
 * @param poolSize when above the request's `maxCandidates`, [refine] first re-decodes the request
 *   with `maxCandidates = poolSize`, so the scorer also sees candidates below the classic strip;
 *   the final re-decode still uses the original `maxCandidates` (0 scores only what the classic
 *   result shows).
 */
@Suppress("LongParameterList") // the tunables are plain constructor parameters
class NeuralReranker(
    private val scorer: CandidateScorer,
    private val scorerExecutor: Executor,
    private val lmStrength: () -> Float,
    val phraseGenerator: PhraseGenerator? = null,
    private val lmWeight: Float = LM_WEIGHT,
    private val deadlineMs: Long = RERANK_DEADLINE_MS,
    private val maxTexts: Int = MAX_TEXTS,
    private val contextWords: Int = CONTEXT_WORDS,
    private val poolSize: Int = 0,
) {
    private val busy = AtomicBoolean(false)

    @Suppress("ReturnCount") // guard clauses
    fun refine(
        request: PredictRequest,
        result: PredictResult,
        stale: () -> Boolean,
        redecode: (PredictRequest) -> PredictResult,
    ): PredictResult? {
        if (!request.engineAllowed) return null
        val strength = lmStrength()
        if (!(strength > 0f) || !strength.isFinite()) return null
        if (busy.get()) return null // a late answer is still running; don't decode for nothing
        val scored = wideResult(request, result, stale, redecode) ?: return null
        val texts = textsToScore(scored)
        if (texts.size < 2) return null // one text has nothing to be compared with
        val context = lastWords(request.context.before, contextWords)

        val job = startScoring(context, texts) ?: return null
        val lp = awaitScores(job, stale) ?: return null
        if (lp.size != texts.size || lp.any { !it.isFinite() }) return null
        val mean = lp.average().toFloat()
        val bonus = HashMap<String, Float>(texts.size * 2)
        for (i in texts.indices) bonus[texts[i]] = strength * lmWeight * (lp[i] - mean)
        if (stale()) return null
        val refined = redecode(request.copy(lmBonus = bonus))
        return if (stale()) null else refined
    }

    /**
     * Hands the texts to the scorer thread; null when a call is in flight or the thread is gone.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    // a failing scorer or a closed executor just means no answer (never log text)
    private fun startScoring(context: String, texts: List<String>): Job? {
        if (!busy.compareAndSet(false, true)) return null
        val started = Job()
        var job: Job? = started
        try {
            scorerExecutor.execute {
                try {
                    started.scores =
                        try {
                            scorer.score(context, texts, deadlineMs)
                        } catch (e: Exception) {
                            null
                        }
                } finally {
                    started.done = true
                    busy.set(false)
                }
            }
        } catch (e: RejectedExecutionException) {
            busy.set(false)
            job = null
        }
        return job
    }

    /**
     * The result whose texts are scored: [result] itself, or with a [poolSize] a re-decode with a
     * wider strip; null when the input moved on meanwhile.
     */
    private fun wideResult(
        request: PredictRequest,
        result: PredictResult,
        stale: () -> Boolean,
        redecode: (PredictRequest) -> PredictResult,
    ): PredictResult? {
        if (poolSize <= request.maxCandidates) return result
        val wide = redecode(request.copy(maxCandidates = poolSize))
        return if (stale()) null else wide
    }

    private class Job {
        @Volatile var done = false

        @Volatile var scores: FloatArray? = null
    }

    @Suppress("ReturnCount") // deadline / stale exits
    private fun awaitScores(job: Job, stale: () -> Boolean): FloatArray? {
        val deadline = System.nanoTime() + deadlineMs * NANOS_PER_MS
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

    /** The literal (if any) and the candidates: distinct non-blank texts, at most [maxTexts]. */
    private fun textsToScore(result: PredictResult): List<String> {
        val out = LinkedHashSet<String>()
        result.literal?.text?.let { if (it.isNotBlank()) out.add(it) }
        for (c in result.candidates) {
            if (out.size >= maxTexts) break
            if (c.text.isNotBlank()) out.add(c.text)
        }
        return out.toList()
    }

    companion object {
        /**
         * Default for how long the decoder thread waits for the scorer before keeping the classic
         * result.
         */
        const val RERANK_DEADLINE_MS = 40L

        /** Default scale of the language-model bonus against the classic fused score. */
        const val LM_WEIGHT = 1.0f

        /** Default for the most texts sent to the scorer in one call. */
        const val MAX_TEXTS = 16

        /** Default for how many words before the cursor the scorer sees. */
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
