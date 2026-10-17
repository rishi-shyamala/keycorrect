@file:Suppress("MagicNumber")

package dev.keycorrect.tools.eval

import dev.keycorrect.core.engineapi.GestureRequest
import dev.keycorrect.core.engineapi.PredictResult
import dev.keycorrect.core.engineapi.TypingContext
import java.util.Locale
import java.util.Random

/** The thing under test in swipe mode: a synchronous `GestureRequest -> PredictResult`. */
fun interface GestureDecoder {
    fun decode(request: GestureRequest): PredictResult
}

/** A word whose top-1 was wrong; [top] is what the engine returned (up to 3). */
class SwipeMiss(val sentence: Int, val target: String, val top: List<String>, val top3Hit: Boolean)

@Suppress("LongParameterList")
/** Counters of one swipe run. [latency] is per decode call. */
class SwipeResult(
    val words: Int,
    val top1: Int,
    val top3: Int,
    val noCandidate: Int,
    val latency: LatencyStats,
    val byLength: Map<String, LengthBucket>,
    val misses: List<SwipeMiss>,
) {
    val wrongCommits: Int
        get() = words - top1 - noCandidate

    private fun pct(n: Int) = if (words == 0) 0.0 else 100.0 * n / words

    val top1Pct: Double
        get() = pct(top1)

    val top3Pct: Double
        get() = pct(top3)

    val noCandidatePct: Double
        get() = pct(noCandidate)

    val wrongCommitPct: Double
        get() = pct(wrongCommits)

    fun text(title: String): String = buildString {
        append("== $title ==\n")
        append(String.format(Locale.ROOT, "words evaluated   %d\n", words))
        append(String.format(Locale.ROOT, "top-1             %.2f %%\n", top1Pct))
        append(String.format(Locale.ROOT, "top-3             %.2f %%\n", top3Pct))
        append(String.format(Locale.ROOT, "wrong commits     %.2f %%\n", wrongCommitPct))
        append(String.format(Locale.ROOT, "no candidate      %.2f %%\n", noCandidatePct))
        append(
            String.format(
                Locale.ROOT,
                "decode latency ms p50 %.2f  p95 %.2f  p99 %.2f  max %.2f  (mean %.2f, n=%d)\n",
                latency.p50Ms,
                latency.p95Ms,
                latency.p99Ms,
                latency.maxMs,
                latency.meanMs,
                latency.count,
            )
        )
        append("by word length (letters): words / top-1 % / top-3 %\n")
        for ((name, b) in byLength) {
            append(
                String.format(
                    Locale.ROOT,
                    "  %-5s %6d  %6.2f  %6.2f\n",
                    name,
                    b.words,
                    b.pct(b.top1),
                    b.pct(b.top3),
                )
            )
        }
    }

    fun toMap(): Map<String, Any> =
        linkedMapOf(
            "words" to words,
            "top1" to top1Pct / 100,
            "top3" to top3Pct / 100,
            "wrongCommits" to wrongCommitPct / 100,
            "noCandidate" to noCandidatePct / 100,
            "latency" to latency.toMap(),
            "byLength" to
                byLength.mapValues {
                    linkedMapOf(
                        "words" to it.value.words,
                        "top1" to it.value.pct(it.value.top1) / 100,
                        "top3" to it.value.pct(it.value.top3) / 100,
                    )
                },
        )

    fun missesCsv(): String {
        val sb = StringBuilder("sentence,target,top3_hit,top1,top2,top3\n")
        for (m in misses) {
            sb.append(m.sentence).append(',').append(csv(m.target)).append(',').append(m.top3Hit)
            for (i in 0 until 3) sb.append(',').append(csv(m.top.getOrElse(i) { "" }))
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun csv(s: String) = "\"" + s.replace("\"", "\"\"").replace("\n", " ") + "\""
}

/** Words / hits of one word-length bucket. */
class LengthBucket(var words: Int = 0, var top1: Int = 0, var top3: Int = 0) {
    fun pct(n: Int) = if (words == 0) 0.0 else 100.0 * n / words
}

/**
 * Swipes every swipeable word of every sentence through [decoder], one at a time on the calling
 * thread (latency is the point, so there is no parallel variant). The context is what the keyboard
 * would hold: the text committed so far (or the true text with [oracleContext]); a word the engine
 * returns nothing for is committed as the true word, as the user would retype it. Word `w` of
 * sentence `s` is generated from its own seed, so results don't depend on what the engine does.
 */
class SwipeEvaluator(
    private val keyboard: EvalKeyboard,
    private val generator: SwipeGenerator,
    private val seed: Long,
    private val oracleContext: Boolean = false,
    private val maxCandidates: Int = 3,
) {
    fun evaluate(
        sentences: List<ParsedSentence>,
        decoder: GestureDecoder,
        warmupSentences: Int = 0,
        missLimit: Int = Int.MAX_VALUE,
    ): SwipeResult {
        // Untimed, unscored pass so the JIT and the engine's caches are warm.
        for (i in 0 until minOf(warmupSentences, sentences.size)) run(
            i,
            sentences[i],
            decoder,
            Acc(missLimit),
            timed = false,
        )
        val acc = Acc(missLimit)
        sentences.forEachIndexed { i, s -> run(i, s, decoder, acc, timed = true) }
        return acc.result()
    }

    private class Acc(val missLimit: Int) {
        var words = 0
        var top1 = 0
        var top3 = 0
        var noCandidate = 0
        var nanos = LongArray(1 shl 12)
        val buckets = LinkedHashMap<String, LengthBucket>()
        val misses = ArrayList<SwipeMiss>()

        fun time(dt: Long) {
            if (words > nanos.size) nanos = nanos.copyOf(nanos.size * 2)
            nanos[words - 1] = dt
        }

        fun result() =
            SwipeResult(
                words,
                top1,
                top3,
                noCandidate,
                LatencyStats.of(nanos, words),
                BUCKETS.mapNotNull { n -> buckets[n]?.let { n to it } }.toMap(),
                misses,
            )
    }

    private fun run(
        index: Int,
        sentence: ParsedSentence,
        decoder: GestureDecoder,
        acc: Acc,
        timed: Boolean,
    ) {
        val committed = StringBuilder()
        val truth = StringBuilder()
        for ((k, token) in sentence.tokens.withIndex()) {
            committed.append(token.gap)
            truth.append(token.gap)
            // One-letter words ("I", "a") are tapped in real use, not swiped: typed as is.
            val tapped = token.text.count { it.isLetter() } < 2
            if (!token.evaluated || tapped || !generator.canSwipe(token.text)) {
                committed.append(token.text)
                truth.append(token.text)
                continue
            }
            val gesture = generator.generate(token.text, Random(wordSeed(index, k)))
            val before = tail(if (oracleContext) truth else committed)
            val request =
                GestureRequest(TypingContext(before), gesture, keyboard.layout, maxCandidates)
            val t0 = System.nanoTime()
            val result = decoder.decode(request)
            val dt = System.nanoTime() - t0
            val top = result.candidates.take(maxCandidates).map { it.text }
            if (timed) record(acc, index, token.text, top, dt)
            committed.append(top.firstOrNull() ?: token.text)
            truth.append(token.text)
        }
    }

    private fun record(acc: Acc, sentence: Int, target: String, top: List<String>, dt: Long) {
        val t = normalise(target)
        val hit1 = top.isNotEmpty() && normalise(top[0]) == t
        val hit3 = top.any { normalise(it) == t }
        acc.words++
        acc.time(dt)
        val bucket = acc.buckets.getOrPut(lengthBucket(t)) { LengthBucket() }
        bucket.words++
        if (top.isEmpty()) acc.noCandidate++
        if (hit1) {
            acc.top1++
            bucket.top1++
        }
        if (hit3) {
            acc.top3++
            bucket.top3++
        }
        if (!hit1 && acc.misses.size < acc.missLimit)
            acc.misses.add(SwipeMiss(sentence, target, top, hit3))
    }

    private companion object {
        val BUCKETS = listOf("1", "2-3", "4-6", "7+")
    }

    private fun wordSeed(sentence: Int, word: Int): Long =
        (seed * 0x9E3779B97F4A7C15uL.toLong()) xor (sentence.toLong() shl 20) xor word.toLong()

    private fun tail(text: CharSequence): String =
        if (text.length <= TypingContext.MAX_BEFORE_CHARS) text.toString()
        else text.substring(text.length - TypingContext.MAX_BEFORE_CHARS)

    private fun normalise(s: String) = s.replace('’', '\'').lowercase()

    private fun lengthBucket(word: String): String {
        val n = word.count { it.isLetter() }
        return when {
            n <= 1 -> "1"
            n <= 3 -> "2-3"
            n <= 6 -> "4-6"
            else -> "7+"
        }
    }
}
