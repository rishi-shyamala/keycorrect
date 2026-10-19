package dev.keycorrect.feature.keyboard

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.decoder.rerank.CandidateScorer
import dev.keycorrect.core.decoder.rerank.NeuralReranker
import dev.keycorrect.core.dictionary.Dictionary
import dev.keycorrect.core.llama.LlamaLibrary
import dev.keycorrect.core.llama.LlamaModel
import dev.keycorrect.core.settings.KeyboardSettings
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.Executors
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Learning-behaviour tests of the whole typing pipeline (not pass/fail unit tests): they type about
 * 500 words key by key, with the shipped dictionary, the real decoder and the real learning model,
 * and measure what the suggestion strip offers before every word.
 *
 * 1. [repeatingTheSameSnippet]: the same snippet eight times; how many passes until the next word
 *    (and a whole phrase) is offered.
 * 2. [alternatingTwoSimilarSnippets]: two similar snippets alternately; whether both continuations
 *    are offered at the places where they differ, and whether one is favoured too strongly.
 *
 * Each runs with the classic engine and (when the llama host library and the model are present)
 * with the neural re-ranker. Slow (minutes), so they run only with `-Pkeycorrect.learncurve=true`;
 * the report goes to `build/learning-curve/report.md` of this module (and stdout).
 *
 * Faithfulness notes: every character is a tap at its key centre on the real letters layer; the
 * controller's learning events go through the real `OutcomeClassifier` and `PersonalModel`, applied
 * synchronously where the app queues them to a learning thread (so the model is never behind the
 * decoder: a slight upper bound on how fast the app learns, bounded by the app's 1.5 s publish
 * delay, which the rig simulates on the fake clock). Snippets are lowercase prose with only commas
 * and periods, so no shift or symbol layer is needed; a paragraph break is typed as a space.
 */
class LearningCurveTest {
    private val enabled = System.getProperty("keycorrect.learncurve") == "true"

    @Test
    fun repeatingTheSameSnippet() = runRepeat(neural = false)

    @Test
    fun alternatingTwoSimilarSnippets() = runAlternate(neural = false)

    @Test
    fun repeatingTheSameSnippetNeural() = runRepeat(neural = true)

    @Test
    fun alternatingTwoSimilarSnippetsNeural() = runAlternate(neural = true)

    // ---------------------------------------------------------------------------------- test 1

    private fun runRepeat(neural: Boolean) {
        val arm = arm(neural)
        val a = Snippet.load("snippet_a.txt")
        val rig = arm.rig()
        val passes = ArrayList<PassResult>()
        val wall = System.nanoTime()
        repeat(REPEAT_PASSES) { p ->
            if (p > 0) rig.idle(PASS_GAP_MS)
            passes += rig.typeSnippet(a.tokens, a.words)
            println("[${arm.name}] repeat pass ${p + 1} done (${secs(wall)} s)")
        }
        arm.close()

        fun pct(n: Int, d: Int) = if (d == 0) 0.0 else 100.0 * n / d

        class Row(
            val top1: Double,
            val top3: Double,
            val top3Words: Double,
            val phraseOffered: Double,
            val phraseCorrect: Double,
            val accuracy: Double,
        )

        val rows =
            passes.map { r ->
                val n = r.boundaries.size
                Row(
                    pct(r.boundaries.count { it.top1 }, n),
                    pct(r.boundaries.count { it.top3 }, n),
                    pct(r.boundaries.count { it.top3WordsOnly }, n),
                    pct(r.boundaries.count { it.phraseOffered }, n),
                    pct(r.boundaries.count { it.phraseCorrect }, n),
                    pct(r.tokensCorrect, r.tokens),
                )
            }
        fun firstPass(limit: Double, v: (Row) -> Double): String =
            rows.indexOfFirst { v(it) > limit }.let { if (it < 0) "never" else "pass ${it + 1}" }

        val sb = StringBuilder()
        sb.appendLine("## Test 1 (repeat), ${arm.name}: snippet A x $REPEAT_PASSES")
        sb.appendLine()
        sb.appendLine(
            "${a.words.size} words, ${passes[0].boundaries.size} measured positions per pass. " +
                "Top-1 = true next word in the centre slot; top-3 = in any slot (a phrase slot " +
                "counts by its first word); top-3w = plain word slots only; phrase offered = a " +
                "phrase slot is shown; phrase correct = its first 2+ words equal the next true " +
                "words; accuracy = typed words that stayed as typed."
        )
        sb.appendLine()
        sb.appendLine(
            "| pass | top-1 % | top-3 % | top-3w % | phrase offered % | phrase correct % | " +
                "committed ok % | simulated time |"
        )
        sb.appendLine("|---|---|---|---|---|---|---|---|")
        rows.forEachIndexed { i, r ->
            sb.appendLine(
                "| ${i + 1} | ${f(r.top1)} | ${f(r.top3)} | ${f(r.top3Words)} | " +
                    "${f(r.phraseOffered)} | ${f(r.phraseCorrect)} | ${f(r.accuracy)} | " +
                    "${mmss(passes[i].simulatedMs)} |"
            )
        }
        sb.appendLine()
        sb.appendLine("Passes until:")
        sb.appendLine("- top-3 > 50%: ${firstPass(50.0) { it.top3 }}")
        sb.appendLine("- top-3 > 80%: ${firstPass(80.0) { it.top3 }}")
        sb.appendLine("- top-1 > 50%: ${firstPass(50.0) { it.top1 }}")
        sb.appendLine("- phrase correct > 25%: ${firstPass(25.0) { it.phraseCorrect }}")
        sb.appendLine("- phrase correct > 50%: ${firstPass(50.0) { it.phraseCorrect }}")
        sb.appendLine()
        val totalMs = passes.sumOf { it.simulatedMs }
        sb.appendLine(
            "Simulated typing time ${mmss(totalMs)} (+ ${(REPEAT_PASSES - 1) * PASS_GAP_MS / 1000} s " +
                "of gaps between passes); real run ${secs(wall)} s; learning events " +
                "${rig.learnedEvents}."
        )
        val everOffered =
            passes[0].boundaries.indices.count { i -> passes.any { it.boundaries[i].top3 } }
        val last = passes.last().boundaries
        sb.appendLine()
        sb.appendLine(
            "Positions whose true next word was in the strip in at least one pass: " +
                "$everOffered of ${last.size} (${f(100.0 * everOffered / last.size)}%); never " +
                "offered in any pass: ${last.size - everOffered}."
        )
        sb.appendLine()
        sb.appendLine("Sample of misses in the last pass (two words before, true next | strip left, centre, right):")
        last.filter { !it.top3 }.take(12).forEach {
            val a = a.words
            sb.appendLine("- ${a[it.index - 2]} ${a[it.index - 1]} -> **${it.expected}** | ${it.describe()}")
        }
        val damage = passes.flatMap { it.damaged }
        if (damage.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine(
                "Words changed by autocorrect (expected -> got), all passes: " +
                    damage
                        .groupingBy { it }
                        .eachCount()
                        .entries
                        .sortedByDescending { it.value }
                        .take(12)
                        .joinToString("; ") { "${it.key.first} -> ${it.key.second} x${it.value}" }
            )
        }
        Report.put("1-${arm.name}", sb.toString())

        // Weak invariants: a regression guard, not a spec of the exact curve.
        assertThat(rows.last().top3).isGreaterThan(rows.first().top3 + 15.0)
        for (i in 1 until rows.size) {
            assertThat(rows[i].accuracy).isAtLeast(rows[i - 1].accuracy - 2.0)
        }
    }

    // ---------------------------------------------------------------------------------- test 2

    private fun runAlternate(neural: Boolean) {
        val arm = arm(neural)
        val a = Snippet.load("snippet_a.txt")
        val b = Snippet.load("snippet_b.txt")
        val points = divergencePoints(a, b)
        val rig = arm.rig()
        val results = ArrayList<Triple<String, PassResult, List<Divergence>>>()
        val wall = System.nanoTime()
        repeat(ALTERNATE_ROUNDS * 2) { p ->
            if (p > 0) rig.idle(PASS_GAP_MS)
            val isA = p % 2 == 0
            val s = if (isA) a else b
            val r = rig.typeSnippet(s.tokens, s.words)
            // Divergence positions of the snippet just typed, with its own and the other word.
            val here =
                points.filter { (if (isA) it.indexA else it.indexB) in r.boundaries.map { x -> x.index } }
            results += Triple(if (isA) "A" else "B", r, here.map { it.forSnippet(isA) })
            println("[${arm.name}] alternate pass ${p + 1} (${if (isA) "A" else "B"}) done (${secs(wall)} s)")
        }
        arm.close()

        fun pct(n: Int, d: Int) = if (d == 0) 0.0 else 100.0 * n / d

        class Row(
            val bothTop3: Double,
            val ownTop3: Double,
            val ownTop1: Double,
            val otherTop3: Double,
            val otherTop1: Double,
            val ownAbove: Double,
            val nonDivTop3: Double,
            val accuracy: Double,
        )

        val rows =
            results.map { (_, r, divs) ->
                val byIndex = r.boundaries.associateBy { it.index }
                val ds = divs.map { it to byIndex.getValue(it.index) }
                val n = ds.size
                val divIdx = divs.map { it.index }.toSet()
                val nonDiv = r.boundaries.filter { it.index !in divIdx }
                Row(
                    pct(ds.count { (d, bd) -> bd.rankOf(d.own) > 0 && bd.rankOf(d.other) > 0 }, n),
                    pct(ds.count { (d, bd) -> bd.rankOf(d.own) > 0 }, n),
                    pct(ds.count { (d, bd) -> bd.rankOf(d.own) == 1 }, n),
                    pct(ds.count { (d, bd) -> bd.rankOf(d.other) > 0 }, n),
                    pct(ds.count { (d, bd) -> bd.rankOf(d.other) == 1 }, n),
                    pct(
                        ds.count { (d, bd) ->
                            val o = bd.rankOf(d.own)
                            val x = bd.rankOf(d.other)
                            o > 0 && (x == 0 || o < x)
                        },
                        n,
                    ),
                    pct(nonDiv.count { it.top3 }, nonDiv.size),
                    pct(r.tokensCorrect, r.tokens),
                )
            }

        val sb = StringBuilder()
        sb.appendLine("## Test 2 (alternate), ${arm.name}: A,B x $ALTERNATE_ROUNDS")
        sb.appendLine()
        sb.appendLine(
            "Snippets: ${a.words.size} / ${b.words.size} words, ${points.size} divergence points " +
                "(same two preceding words, a different next word in A and B), " +
                f(100.0 * sharedTrigrams(a, b)) + "% of A's word trigrams also occur in B. " +
                "At a divergence point `own` is the next word of the snippet being typed, " +
                "`other` the word the other snippet has there. Ranks: centre 1, left 2, right 3 " +
                "(a phrase slot counts by its first word)."
        )
        sb.appendLine()
        sb.appendLine(
            "| pass | snippet | both in top-3 % | own top-3 % | own top-1 % | other top-3 % | " +
                "other top-1 % | own ranked above other % | non-divergent top-3 % | committed ok % |"
        )
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|")
        rows.forEachIndexed { i, r ->
            sb.appendLine(
                "| ${i + 1} | ${results[i].first} | ${f(r.bothTop3)} | ${f(r.ownTop3)} | " +
                    "${f(r.ownTop1)} | ${f(r.otherTop3)} | ${f(r.otherTop1)} | " +
                    "${f(r.ownAbove)} | ${f(r.nonDivTop3)} | ${f(r.accuracy)} |"
            )
        }
        val last = rows.takeLast(4)
        val ownTop1 = last.map { it.ownTop1 }.average()
        val otherTop3 = last.map { it.otherTop3 }.average()
        val otherTop1 = last.map { it.otherTop1 }.average()
        val ownTop3 = last.map { it.ownTop3 }.average()
        sb.appendLine()
        sb.appendLine("Bias check, last 4 passes (5-8), mean over divergence points:")
        sb.appendLine("- own word top-1: ${f(ownTop1)}% (target roughly <= 75%): " +
            if (ownTop1 <= 75.0) "OK" else "TOO STRONG")
        sb.appendLine("- other word in top-3: ${f(otherTop3)}% (target >= 50%): " +
            if (otherTop3 >= 50.0) "OK" else "TOO LOW")
        sb.appendLine("- own word in top-3: ${f(ownTop3)}%, other word top-1 (recency bias): ${f(otherTop1)}%")
        sb.appendLine()
        val totalMs = results.sumOf { it.second.simulatedMs }
        sb.appendLine(
            "Simulated typing time ${mmss(totalMs)}; real run ${secs(wall)} s; learning events " +
                "${rig.learnedEvents}."
        )
        val damage = results.flatMap { it.second.damaged }
        if (damage.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine(
                "Words changed by autocorrect (expected -> got), all passes: " +
                    damage
                        .groupingBy { it }
                        .eachCount()
                        .entries
                        .sortedByDescending { it.value }
                        .take(12)
                        .joinToString("; ") { "${it.key.first} -> ${it.key.second} x${it.value}" }
            )
        }
        Report.put("2-${arm.name}", sb.toString())

        // Weak sanity bounds only. The pipeline must learn (the typed snippet's own word shows up
        // at divergence points more and more) and must not lock in completely: it never shows
        // only the own word to the exclusion of every alternative.
        assertThat(rows.last().ownTop3).isGreaterThan(rows.first().ownTop3)
        assertThat(otherTop3).isGreaterThan(0.0)
        for (i in 1 until rows.size) {
            assertThat(rows[i].accuracy).isAtLeast(rows[0].accuracy - 2.0)
        }
    }

    // ----------------------------------------------------------------------------------- arms

    private inner class Arm(
        val name: String,
        val dictionary: Dictionary,
        val reranker: NeuralReranker?,
        val closer: () -> Unit,
    ) {
        fun rig() = TypingRig(dictionary, reranker, KeyboardSettings())

        fun close() = closer()
    }

    private fun arm(neural: Boolean): Arm {
        assumeTrue("learning curve: run with -Pkeycorrect.learncurve=true", enabled)
        val file = findUp("app/src/main/assets/dictionaries/en_US.kcd")
        assumeTrue("en_US.kcd asset not present", file != null)
        val dictionary =
            FileChannel.open(file!!.toPath(), StandardOpenOption.READ).use {
                Dictionary.open(it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()))
            }
        if (!neural) return Arm("classic", dictionary, null) {}
        // The files live in the main checkout (git-ignored); found from here, also from a worktree.
        val lib =
            System.getProperty("keycorrect.learncurve.lib")?.let(::File)
                ?: findUp("build/llama-host/libkcllama.so")
        val gguf =
            System.getProperty("keycorrect.learncurve.model")?.let(::File)
                ?: findUp("data/raw/neural/SmolLM2-135M-Q8_0.gguf")
        assumeTrue("libkcllama.so not found", lib?.isFile == true)
        assumeTrue("neural model not found", gguf?.isFile == true)
        LlamaLibrary.load(lib!!.absolutePath)
        val model = LlamaModel.openFile(gguf!!.absolutePath)
        val session = model.newSession(LlamaModel.DEFAULT_CTX, LlamaModel.DEFAULT_THREADS)
        val executor = Executors.newSingleThreadExecutor { Thread(it, "kc-learncurve-scorer") }
        val scorer = CandidateScorer { context, candidates, timeoutMs ->
            session.score(context, candidates, timeoutMs)
        }
        val reranker =
            NeuralReranker(
                scorer,
                executor,
                lmStrength = { KeyboardSettings().lmStrength },
                // Generous: the app's 40 ms budget is for a phone; here only the effect matters.
                deadlineMs = NEURAL_DEADLINE_MS,
            )
        return Arm("neural", dictionary, reranker) {
            executor.shutdownNow()
            session.close()
            model.close()
        }
    }

    private fun findUp(relative: String): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, relative)
            if (f.isFile) return f
            dir = dir.parentFile
        }
        return null
    }

    // -------------------------------------------------------------------------------- snippets

    private class Snippet(val tokens: List<String>, val words: List<String>) {
        companion object {
            fun load(name: String): Snippet {
                val text =
                    LearningCurveTest::class.java.getResource("/learning/$name")!!.readText()
                val tokens = text.trim().split(Regex("\\s+"))
                val words = tokens.map { t -> t.filter { it.isLetter() }.lowercase(Locale.ROOT) }
                return Snippet(tokens, words)
            }
        }
    }

    /** A place where both snippets have the same two words before it and different next words. */
    private class DivergencePoint(
        val wordA: String,
        val wordB: String,
        val indexA: Int,
        val indexB: Int,
    ) {
        fun forSnippet(isA: Boolean) =
            if (isA) Divergence(indexA, wordA, wordB) else Divergence(indexB, wordB, wordA)
    }

    private class Divergence(val index: Int, val own: String, val other: String)

    private fun divergencePoints(a: Snippet, b: Snippet): List<DivergencePoint> {
        fun contexts(s: Snippet): Map<Pair<String, String>, List<Int>> =
            (2 until s.words.size).groupBy { s.words[it - 2] to s.words[it - 1] }
        val ca = contexts(a)
        val cb = contexts(b)
        val out = ArrayList<DivergencePoint>()
        for ((ctx, ia) in ca) {
            val ib = cb[ctx] ?: continue
            // Only unambiguous contexts: one next word within each snippet.
            if (ia.map { a.words[it] }.toSet().size != 1 || ib.map { b.words[it] }.toSet().size != 1)
                continue
            if (a.words[ia[0]] == b.words[ib[0]]) continue
            ia.forEach { i ->
                out += DivergencePoint(a.words[i], b.words[ib[0]], i, ib[0])
            }
        }
        return out.sortedBy { it.indexA }
    }

    private fun sharedTrigrams(a: Snippet, b: Snippet): Double {
        fun tri(s: Snippet) = (0..s.words.size - 3).map { s.words.subList(it, it + 3) }.toSet()
        val ta = tri(a)
        return ta.count { it in tri(b) }.toDouble() / ta.size
    }

    // --------------------------------------------------------------------------------- helpers

    private fun f(v: Double) = String.format(Locale.ROOT, "%.1f", v)

    private fun secs(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000_000

    private fun mmss(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

    private companion object {
        const val REPEAT_PASSES = 8
        const val ALTERNATE_ROUNDS = 4

        /** A gap between two pastes of typing (picking the phone up again). */
        const val PASS_GAP_MS = 5_000L
        const val NEURAL_DEADLINE_MS = 2_000L

        @AfterClass
        @JvmStatic
        fun writeReport() = Report.write()
    }
}

/** Collects the sections the tests produce and writes `build/learning-curve/report.md`. */
internal object Report {
    private val sections = TreeMap<String, String>()

    @Synchronized
    fun put(key: String, text: String) {
        sections[key] = text
        println("\n$text")
        write()
    }

    @Synchronized
    fun write() {
        if (sections.isEmpty()) return
        val dir = File("build/learning-curve")
        dir.mkdirs()
        File(dir, "report.md")
            .writeText("# Learning curve report\n\n" + sections.values.joinToString("\n"))
    }
}
