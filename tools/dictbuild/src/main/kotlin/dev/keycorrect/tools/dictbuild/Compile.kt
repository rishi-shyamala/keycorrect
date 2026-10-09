package dev.keycorrect.tools.dictbuild

import dev.keycorrect.core.dictionary.BigramEntry
import dev.keycorrect.core.dictionary.Dictionary
import dev.keycorrect.core.dictionary.DictionaryData
import dev.keycorrect.core.dictionary.DictionaryMetadata
import dev.keycorrect.core.dictionary.DictionaryWriter
import dev.keycorrect.core.dictionary.TrigramEntry
import dev.keycorrect.core.dictionary.WordEntry
import dev.keycorrect.core.dictionary.WordFlags
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ln

/** What is kept of the estimated model (applied on top of the pruning `count` already did). */
data class PruneParams(
    /** Minimum raw (wiki + tatoeba) occurrences of a stored bigram / trigram. */
    val minBigramCount: Int = 2,
    val minTrigramCount: Int = 2,
    /** Maximum successors stored per context. */
    val maxBigramSuccessors: Int = 1000,
    val maxTrigramSuccessors: Int = 200,
    /**
     * Gain threshold (count-weighted relative-entropy pruning): an n-gram is stored only if
     * `count * |ln P(stored) - ln P(runtime back-off estimate)|` is at least this many nats, i.e.
     * if removing it would cost that much training log-likelihood. The back-off estimate is
     * `penalty + lower-order value` as the runtime computes it ([penalty2] / [penalty3]).
     */
    val bigramGain: Double = 12.0,
    val trigramGain: Double = 12.0,
    val penalty2: Double = -1.3,
    val penalty3: Double = -0.8,
)

/** Command line of `dictbuild compile`. */
data class CompileOptions(
    val input: Path = Paths.get("data/processed"),
    val out: Path = Paths.get("app/src/main/assets/dictionaries/en_US.kcd"),
    val lm: LmParams = LmParams(),
    val prune: PruneParams = PruneParams(),
    val language: String = "en-US",
    /** ISO date recorded in the dictionary; also the data version (yyyymmdd). */
    val buildDate: String = LocalDate.now().toString(),
    /** Fail if the compiled dictionary is larger than this many bytes. */
    val maxBytes: Long = 10L * 1024 * 1024,
) {
    companion object {
        const val USAGE =
            """
dictbuild compile [options]    (reads the output of `dictbuild count`)
  --in DIR                   data/processed directory              (default data/processed)
  --out FILE                 compiled dictionary to write          (default app/src/main/assets/dictionaries/en_US.kcd)
  --tatoeba-weight W         Tatoeba count multiplier              (default 3.0)
  --tatoeba-cap N            clip any n-gram's Tatoeba count to N  (default off)
  --discount2 D              absolute discount of bigrams          (default: Chen-Goodman from count-of-counts)
  --discount3 D              absolute discount of trigrams         (default: Chen-Goodman)
  --unigram-mix A            Kneser-Ney continuation share of the unigram base (0..1, default 0.5)
  --min-bigram-count N       raw count needed to store a bigram    (default 2)
  --min-trigram-count N      raw count needed to store a trigram   (default 2)
  --max-bigram-successors N  successors stored per word            (default 1000)
  --max-trigram-successors N successors stored per word pair       (default 200)
  --bigram-gain G            store a bigram only if dropping it costs >= G nats of weighted
                             training log-likelihood (default 12; 0 = keep all candidates)
  --trigram-gain G           same for trigrams
  --penalty2 P --penalty3 P  back-off penalties assumed by the gain pruning (defaults -1.3 / -0.8,
                             the optimum found by eval-lm)
  --language TAG             language tag                          (default en-US)
  --build-date YYYY-MM-DD    build date / data version             (default today)
  --max-size-mib N           fail above this compiled size         (default 10)
"""

        fun parse(args: List<String>): CompileOptions {
            var o = CompileOptions()
            var i = 0
            fun value(flag: String, inline: String?): String {
                if (inline != null) return inline
                if (i + 1 >= args.size) throw IllegalArgumentException("$flag needs a value")
                return args[++i]
            }
            while (i < args.size) {
                val arg = args[i]
                val eq = arg.indexOf('=')
                val flag = if (arg.startsWith("--") && eq > 0) arg.substring(0, eq) else arg
                val inline = if (arg.startsWith("--") && eq > 0) arg.substring(eq + 1) else null
                fun v() = value(flag, inline)
                o =
                    when (flag) {
                        "--in" -> o.copy(input = Paths.get(v()))
                        "--out" -> o.copy(out = Paths.get(v()))
                        "--tatoeba-weight" -> o.copy(lm = o.lm.copy(tatoebaWeight = v().toDouble()))
                        "--tatoeba-cap" -> o.copy(lm = o.lm.copy(tatoebaCap = v().toLong()))
                        "--discount2" -> o.copy(lm = o.lm.copy(discount2 = v().toDouble()))
                        "--discount3" -> o.copy(lm = o.lm.copy(discount3 = v().toDouble()))
                        "--unigram-mix" -> o.copy(lm = o.lm.copy(unigramMix = v().toDouble()))
                        "--min-bigram-count" ->
                            o.copy(prune = o.prune.copy(minBigramCount = v().toInt()))
                        "--min-trigram-count" ->
                            o.copy(prune = o.prune.copy(minTrigramCount = v().toInt()))
                        "--max-bigram-successors" ->
                            o.copy(prune = o.prune.copy(maxBigramSuccessors = v().toInt()))
                        "--max-trigram-successors" ->
                            o.copy(prune = o.prune.copy(maxTrigramSuccessors = v().toInt()))
                        "--bigram-gain" -> o.copy(prune = o.prune.copy(bigramGain = v().toDouble()))
                        "--trigram-gain" ->
                            o.copy(prune = o.prune.copy(trigramGain = v().toDouble()))
                        "--penalty2" -> o.copy(prune = o.prune.copy(penalty2 = v().toDouble()))
                        "--penalty3" -> o.copy(prune = o.prune.copy(penalty3 = v().toDouble()))
                        "--language" -> o.copy(language = v())
                        "--build-date" -> o.copy(buildDate = v())
                        "--max-size-mib" -> o.copy(maxBytes = (v().toDouble() * 1048576).toLong())
                        else -> throw IllegalArgumentException("unknown option: $arg")
                    }
                i++
            }
            return o
        }
    }
}

/** Result of selecting what to store. */
class SelectedModel(
    val bigramRows: IntArray,
    val trigramRows: IntArray,
)

/** Pruning of an estimated model. */
object LmPruner {
    fun select(lm: LmEstimator, p: PruneParams): SelectedModel {
        val t = lm.tables
        val b = t.bi
        val vocabSize = t.vocabSize
        // --- bigrams: group rows by context, rank by weighted count.
        val keptBi = LongIntMap(1024)
        val biOut = IntList()
        run {
            val order = rowsByContext(b.size, b.w1) { lm.weighted(b.wiki[it], b.tat[it]) }
            var s = 0
            while (s < order.size) {
                var e = s + 1
                while (e < order.size && b.w1[order[e]] == b.w1[order[s]]) e++
                var kept = 0
                for (k in s until e) {
                    val r = order[k]
                    if (kept >= p.maxBigramSuccessors) break
                    if (b.wiki[r] + b.tat[r] < p.minBigramCount) continue
                    if (p.bigramGain > 0.0) {
                        val est = p.penalty2 + ln(lm.uni[b.last[r]])
                        val gain = lm.weighted(b.wiki[r], b.tat[r]) * abs(lm.lp2[r] - est)
                        if (gain < p.bigramGain) continue
                    }
                    biOut.add(r)
                    keptBi.put(LmTables.pairKey(b.w1[r], b.last[r]), r)
                    kept++
                }
                s = e
            }
        }
        // --- trigrams
        val tr = t.tri
        val triOut = IntList()
        run {
            val ctxKey = IntArray(tr.size) { lm.triCtxRow[it] }
            val order = rowsByContext(tr.size, ctxKey) { lm.weighted(tr.wiki[it], tr.tat[it]) }
            var s = 0
            while (s < order.size) {
                var e = s + 1
                while (e < order.size && ctxKey[order[e]] == ctxKey[order[s]]) e++
                var kept = 0
                for (k in s until e) {
                    val r = order[k]
                    if (ctxKey[r] < 0 || lm.lp3[r].isNaN()) continue
                    if (kept >= p.maxTrigramSuccessors) break
                    if (tr.wiki[r] + tr.tat[r] < p.minTrigramCount) continue
                    if (p.trigramGain > 0.0) {
                        val br = keptBi.get(LmTables.pairKey(tr.w2[r], tr.last[r]))
                        val lower = if (br >= 0) lm.lp2[br] else p.penalty2 + ln(lm.uni[tr.last[r]])
                        val gain =
                            lm.weighted(tr.wiki[r], tr.tat[r]) *
                                abs(lm.lp3[r] - (p.penalty3 + lower))
                        if (gain < p.trigramGain) continue
                    }
                    triOut.add(r)
                    kept++
                }
                s = e
            }
        }
        check(vocabSize > 0)
        return SelectedModel(biOut.toArray(), triOut.toArray())
    }

    /** Row indices sorted by (context, weight desc, row). */
    private fun rowsByContext(n: Int, ctx: IntArray, weight: (Int) -> Double): IntArray {
        val w = DoubleArray(n) { weight(it) }
        val boxed = arrayOfNulls<Int>(n)
        for (i in 0 until n) boxed[i] = i
        java.util.Arrays.sort(boxed) { x, y ->
            val a = x!!
            val b = y!!
            when {
                ctx[a] != ctx[b] -> Integer.compare(ctx[a], ctx[b])
                w[a] != w[b] -> java.lang.Double.compare(w[b], w[a])
                else -> Integer.compare(a, b)
            }
        }
        return IntArray(n) { boxed[it]!! }
    }
}

object CompileCommand {
    private fun log(msg: String) = System.err.println(msg)

    fun run(args: List<String>): Int {
        if (args.contains("--help") || args.contains("-h")) {
            println(CompileOptions.USAGE)
            return 0
        }
        val opts =
            try {
                CompileOptions.parse(args)
            } catch (e: IllegalArgumentException) {
                System.err.println("error: ${e.message}\n${CompileOptions.USAGE}")
                return 2
            }
        val bytes = execute(opts)
        return if (bytes.size > opts.maxBytes) {
            System.err.println(
                "error: compiled dictionary is ${bytes.size} bytes, above the limit of " +
                    "${opts.maxBytes}; tighten pruning"
            )
            3
        } else 0
    }

    /** Estimates, prunes, compiles and writes the dictionary; returns the compiled bytes. */
    fun execute(opts: CompileOptions): ByteArray {
        val start = System.nanoTime()
        log("loading ${opts.input}")
        val tables = LmTables.load(opts.input)
        log("estimating (${opts.lm})")
        val lm = LmEstimator(tables, opts.lm)
        log(
            "discounts D2=%.3f D3=%.3f, unseen words: %d, unseen mass %.3g"
                .format(lm.d2, lm.d3, lm.unseen.count { it }, lm.unseenMass)
        )
        val sel = LmPruner.select(lm, opts.prune)
        val data = buildData(lm, sel, opts, sourceNote(opts.input))
        val bytes = DictionaryWriter.write(data)
        opts.out.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.write(opts.out, bytes)
        val dict = Dictionary.open(ByteBuffer.wrap(bytes))
        log(
            "wrote ${opts.out}: ${bytes.size} bytes (%.2f MiB), $dict, %.1fs"
                .format(bytes.size / 1048576.0, (System.nanoTime() - start) / 1e9)
        )
        return bytes
    }

    fun buildData(
        lm: LmEstimator,
        sel: SelectedModel,
        opts: CompileOptions,
        sourceNote: String,
    ): DictionaryData {
        val t = lm.tables
        val words =
            List(t.vocabSize) { k ->
                val v = t.vocab[k]
                var flags = 0
                if (VocabEntry.POSSIBLY_OFFENSIVE in v.flags)
                    flags = flags or WordFlags.POSSIBLY_OFFENSIVE
                if (VocabEntry.NOT_A_WORD in v.flags) flags = flags or WordFlags.NOT_A_WORD
                WordEntry(v.word, ln(lm.uni[k + 1]).toFloat(), flags)
            }
        val b = t.bi
        val bigrams =
            List(sel.bigramRows.size) {
                val r = sel.bigramRows[it]
                BigramEntry(t.words[b.w1[r]], t.words[b.last[r]], lm.lp2[r].toFloat())
            }
        val tr = t.tri
        val trigrams =
            List(sel.trigramRows.size) {
                val r = sel.trigramRows[it]
                TrigramEntry(
                    t.words[tr.w1[r]],
                    t.words[tr.w2[r]],
                    t.words[tr.last[r]],
                    lm.lp3[r].toFloat(),
                )
            }
        val version = opts.buildDate.filter { it.isDigit() }.toIntOrNull() ?: 1
        return DictionaryData(
            DictionaryMetadata(
                opts.language,
                opts.buildDate,
                version,
                ATTRIBUTION + " " + sourceNote,
            ),
            words,
            bigrams,
            trigrams,
        )
    }

    /** Source versions recorded in the attribution, taken from `stats.json` when present. */
    private fun sourceNote(dir: Path): String {
        val stats = dir.resolve("stats.json")
        val text = if (Files.exists(stats)) Files.readString(stats) else ""
        fun find(re: String) = Regex(re).find(text)?.groupValues?.get(1)
        val aosp = find("\"version\": \"(\\d+)\"")
        val aospDate = find("\"date\": \"(\\d+)\"")
        val wiki = find("\"file\": \"(simplewiki-[^\"]+)\"")
        val aospDay =
            aospDate?.toLongOrNull()?.let {
                java.time.Instant.ofEpochSecond(it).toString().take(10)
            }
        return "Sources: AOSP LatinIME en_US_wordlist.combined (version ${aosp ?: "?"}" +
            (aospDay?.let { ", $it" } ?: "") +
            "); ${wiki ?: "Simple English Wikipedia dump"}; " +
            "Tatoeba English sentences export 2026-10-03."
    }

    const val ATTRIBUTION =
        "English dictionary data. Word list and word frequencies: Android Open Source Project, " +
            "LatinIME English dictionary, licensed under the Apache License 2.0 " +
            "(https://www.apache.org/licenses/LICENSE-2.0). Next-word statistics derived from " +
            "Simple English Wikipedia (text by Wikipedia contributors, https://simple.wikipedia.org, " +
            "licensed under CC BY-SA 4.0, https://creativecommons.org/licenses/by-sa/4.0/) and from " +
            "sentences on Tatoeba (https://tatoeba.org, by Tatoeba contributors, licensed under " +
            "CC BY 2.0 FR, https://creativecommons.org/licenses/by/2.0/fr/). The statistics are " +
            "aggregated word counts; no article or sentence text is included. Changes were made."
}
