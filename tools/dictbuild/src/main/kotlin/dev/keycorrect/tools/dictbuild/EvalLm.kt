package dev.keycorrect.tools.dictbuild

import dev.keycorrect.core.dictionary.Dictionary
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.text.Normalizer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/** Command line of `dictbuild eval-lm`. */
data class EvalOptions(
    val dict: Path = Paths.get("app/src/main/assets/dictionaries/en_US.kcd"),
    val vocab: Path = Paths.get("data/raw/en_US_wordlist.combined.gz"),
    val heldout: Path = Paths.get("data/processed/heldout.txt"),
    /** Evaluate at most this many held-out sentences (0 = all). */
    val limit: Int = 0,
    /** Fixed back-off penalties (ln units, <= 0); null = search the best pair on the data. */
    val penalty2: Double? = null,
    val penalty3: Double? = null,
) {
    companion object {
        const val USAGE =
            """
dictbuild eval-lm [options]    intrinsic evaluation of a compiled dictionary on held-out sentences
  --dict FILE        compiled dictionary   (default app/src/main/assets/dictionaries/en_US.kcd)
  --vocab FILE       AOSP wordlist used to tokenise (default data/raw/en_US_wordlist.combined.gz)
  --heldout FILE     one sentence per line (default data/processed/heldout.txt)
  --limit N          only the first N sentences
  --penalty2 P --penalty3 P   fixed back-off penalties, <= 0 (default: search the best pair)
"""

        fun parse(args: List<String>): EvalOptions {
            var o = EvalOptions()
            var i = 0
            while (i < args.size) {
                val arg = args[i]
                val eq = arg.indexOf('=')
                val flag = if (arg.startsWith("--") && eq > 0) arg.substring(0, eq) else arg
                val inline = if (arg.startsWith("--") && eq > 0) arg.substring(eq + 1) else null

                fun v(): String {
                    if (inline != null) return inline
                    if (i + 1 >= args.size) throw IllegalArgumentException("$flag needs a value")
                    return args[++i]
                }
                o =
                    when (flag) {
                        "--dict" -> o.copy(dict = Paths.get(v()))
                        "--vocab" -> o.copy(vocab = Paths.get(v()))
                        "--heldout" -> o.copy(heldout = Paths.get(v()))
                        "--limit" -> o.copy(limit = v().toInt())
                        "--penalty2" -> o.copy(penalty2 = v().toDouble())
                        "--penalty3" -> o.copy(penalty3 = v().toDouble())
                        else -> throw IllegalArgumentException("unknown option: $arg")
                    }
                i++
            }
            return o
        }
    }
}

/**
 * Held-out tokens scored against a [Dictionary].
 *
 * The runtime language model is the simple back-off scheme (see docs/LANGUAGE_MODEL notes in the
 * compile command): `score(z | v w) = trigram(v,w,z)` if stored, else `b3 + score(z | w)`, with
 * `score(z | w) = bigram(w,z)` if stored, else `b2 + unigram(z)`. A back-off *bonus* cannot be
 * judged by raw perplexity (a positive b makes every score larger, not better), so the perplexity
 * reported here is of the **normalised** model: each context's scores are divided by their sum over
 * the whole vocabulary. Normalisation does not change the ranking of candidates within one context,
 * which is all the decoder uses, but it makes the penalties comparable.
 */
class EvalSample(val dict: Dictionary) {
    private val ids = IntList()
    private val ctx1 = IntList()
    private val ctx2 = IntList()
    private val biCtx = IntList()
    private val triCtx = IntList()
    private var uniL = FloatArray(1 shl 20)
    private var biL = FloatArray(1 shl 20)
    private var triL = FloatArray(1 shl 20)
    var size = 0
        private set

    var tokens = 0L
    var oov = 0L
    var sentences = 0L

    /** Sum of the stored unigram probabilities over all words (normaliser of the unigram level). */
    val uniTotal: Double =
        (Dictionary.FIRST_WORD_ID until dict.wordCount).sumOf { exp(dict.logProb(it).toDouble()) }

    // Per distinct bigram context w (list of successors z): sum of stored bigram probs and of the
    // unigram probs of the same z.
    private val biCtxIndex = HashMap<Int, Int>()
    private val biA = DoubleList()
    private val biB = DoubleList()

    // Per distinct trigram context (v, w): sum of stored trigram probs; and, over the same z, the
    // sum of stored bigram probs of those z that have one (c) / unigram probs of those that don't
    // (d).
    private val triCtxIndex = HashMap<Long, Int>()
    private val triA = DoubleList()
    private val triC = DoubleList()
    private val triD = DoubleList()
    private val tmpIds = IntArray(1 shl 16)
    private val tmpLp = FloatArray(1 shl 16)

    private fun biContext(w: Int): Int =
        biCtxIndex.getOrPut(w) {
            val n = dict.topSuccessors(w, tmpIds.size, tmpIds, tmpLp)
            var a = 0.0
            var b = 0.0
            for (k in 0 until n) {
                a += exp(tmpLp[k].toDouble())
                b += exp(dict.logProb(tmpIds[k]).toDouble())
            }
            biA.add(a)
            biB.add(b)
            biA.size - 1
        }

    private fun triContext(v: Int, w: Int): Int =
        triCtxIndex.getOrPut((v.toLong() shl 32) or w.toLong()) {
            val n = dict.topSuccessors(v, w, tmpIds.size, tmpIds, tmpLp)
            val bl = dict.bigramList(w)
            var a = 0.0
            var c = 0.0
            var d = 0.0
            for (k in 0 until n) {
                a += exp(tmpLp[k].toDouble())
                val bi = dict.logProbInBigramList(bl, tmpIds[k])
                if (bi.isNaN()) d += exp(dict.logProb(tmpIds[k]).toDouble())
                else c += exp(bi.toDouble())
            }
            triA.add(a)
            triC.add(c)
            triD.add(d)
            triA.size - 1
        }

    /**
     * Adds one in-vocabulary token `id` with history ([p2], [p1]) (-1 = none; a history is lost
     * after an out-of-vocabulary token, like during counting).
     */
    fun add(id: Int, p1: Int, p2: Int) {
        if (size == uniL.size) {
            uniL = uniL.copyOf(size * 2)
            biL = biL.copyOf(size * 2)
            triL = triL.copyOf(size * 2)
        }
        var bi = Float.NaN
        var tri = Float.NaN
        var c2 = -1
        var c3 = -1
        if (p1 >= 0) {
            c2 = biContext(p1)
            bi = dict.bigramLogProbOrNaN(p1, id)
            if (p2 >= 0) {
                c3 = triContext(p2, p1)
                tri = dict.trigramLogProbOrNaN(p2, p1, id)
            }
        }
        uniL[size] = dict.logProb(id)
        biL[size] = bi
        triL[size] = tri
        biCtx.add(c2)
        triCtx.add(c3)
        ids.add(id)
        ctx1.add(p1)
        ctx2.add(p2)
        size++
    }

    fun id(i: Int) = ids[i]

    fun p1(i: Int) = ctx1[i]

    fun p2(i: Int) = ctx2[i]

    /** Mean normalised log-probability (nats per token) with back-off penalties [b3], [b2]. */
    fun meanLogProb(b3: Double, b2: Double): Double {
        val eb2 = exp(b2)
        val eb3 = exp(b3)
        val z2 = DoubleArray(biA.size) { biA[it] + eb2 * max(uniTotal - biB[it], 0.0) }
        val z3 = DoubleArray(triA.size)
        // z3 needs the bigram normaliser of its own context's w: filled lazily per token below.
        val z3Done = BooleanArray(triA.size)
        val lnU = ln(uniTotal)
        var sum = 0.0
        for (i in 0 until size) {
            val c2 = biCtx[i]
            if (c2 < 0) {
                sum += uniL[i] - lnU
                continue
            }
            val lnZ2 = ln(z2[c2])
            val lower = (if (biL[i].isNaN()) b2 + uniL[i] else biL[i].toDouble()) - lnZ2
            val c3 = triCtx[i]
            if (c3 < 0) {
                sum += lower
                continue
            }
            if (!z3Done[c3]) {
                val rest = 1.0 - (triC[c3] + eb2 * triD[c3]) / z2[c2]
                z3[c3] = triA[c3] + eb3 * max(rest, 1e-9)
                z3Done[c3] = true
            }
            val lnZ3 = ln(z3[c3])
            sum += (if (triL[i].isNaN()) b3 + lower else triL[i].toDouble()) - lnZ3
        }
        return sum / size
    }

    /** Debug: prints a few tokens whose bigram is missing from the table. */
    fun dump(n: Int) {
        var shown = 0
        var i = 0
        while (shown < n && i < size) {
            if (ctx1[i] >= 0 && biL[i].isNaN() && triL[i].isNaN()) {
                println(
                    "  %s -> %s  uni %.2f".format(dict.word(ctx1[i]), dict.word(ids[i]), uniL[i])
                )
                shown++
            }
            i += 997
        }
    }

    /** Breakdown of how the tokens are resolved: share and mean stored log-prob per category. */
    fun breakdown(): String {
        val names =
            listOf("trigram hit", "bigram hit (trigram missing)", "unigram only", "no history")
        val n = IntArray(4)
        val sum = DoubleArray(4)
        val sumUni = DoubleArray(4)
        for (i in 0 until size) {
            val c =
                when {
                    ctx1[i] < 0 -> 3
                    !triL[i].isNaN() -> 0
                    !biL[i].isNaN() -> 1
                    else -> 2
                }
            n[c]++
            sum[c] +=
                when (c) {
                    0 -> triL[i].toDouble()
                    1 -> biL[i].toDouble()
                    else -> uniL[i].toDouble()
                }
            sumUni[c] += uniL[i]
        }
        return (0 until 4).joinToString("\n") {
            "    %-30s %6.2f%% of tokens, mean stored ln p %.2f (mean unigram ln p %.2f)"
                .format(
                    names[it],
                    100.0 * n[it] / size,
                    sum[it] / maxOf(1, n[it]),
                    sumUni[it] / maxOf(1, n[it]),
                )
        }
    }

    fun unigramOnly(): Double {
        var sum = 0.0
        for (i in 0 until size) sum += uniL[i]
        return sum / size - ln(uniTotal)
    }
}

object EvalLmCommand {
    fun run(args: List<String>): Int {
        if (args.contains("--help") || args.contains("-h")) {
            println(EvalOptions.USAGE)
            return 0
        }
        val opts =
            try {
                EvalOptions.parse(args)
            } catch (e: IllegalArgumentException) {
                System.err.println("error: ${e.message}\n${EvalOptions.USAGE}")
                return 2
            }
        execute(opts)
        return 0
    }

    fun mapDictionary(path: Path): Dictionary =
        FileChannel.open(path, StandardOpenOption.READ).use { ch ->
            Dictionary.open(ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()))
        }

    /** Tokenises the held-out file the way `count` did and scores it against [dict]. */
    fun sample(dict: Dictionary, opts: EvalOptions): EvalSample {
        val wordlist = Sources.openReader(opts.vocab).use { AospWordlistParser.parse(it) }
        val vocab = Vocabulary(wordlist.entries)
        val toDict = IntArray(vocab.size + 1) { if (it == 0) -1 else dict.wordId(vocab.word(it)) }
        val s = EvalSample(dict)
        val buf = IdBuffer()
        var n = 0
        Files.newBufferedReader(opts.heldout, StandardCharsets.UTF_8).use { r ->
            while (true) {
                val raw = r.readLine() ?: break
                if (opts.limit > 0 && n >= opts.limit) break
                n++
                val text =
                    if (
                        raw.any { it.code > 127 } &&
                            !Normalizer.isNormalized(raw, Normalizer.Form.NFC)
                    ) {
                        Normalizer.normalize(raw, Normalizer.Form.NFC)
                    } else raw
                s.sentences++
                var p1 = Dictionary.BOS_ID
                var p2 = -1
                var first = true
                Tokenizer.tokenize(text) { token ->
                    s.tokens++
                    buf.clear()
                    if (vocab.resolve(token, first, buf)) {
                        for (k in 0 until buf.size) {
                            val id = toDict[buf[k]]
                            if (id < 0) {
                                p1 = -1
                                p2 = -1
                                s.oov++
                                continue
                            }
                            s.add(id, p1, p2)
                            p2 = p1
                            p1 = id
                        }
                    } else {
                        s.oov++
                        p1 = -1
                        p2 = -1
                    }
                    first = false
                }
            }
        }
        return s
    }

    class Best(val b3: Double, val b2: Double, val meanLp: Double)

    /** Finds the penalties (both <= 0) with the best normalised log-likelihood. */
    fun searchPenalties(s: EvalSample): Best {
        var best = Best(0.0, 0.0, Double.NEGATIVE_INFINITY)

        fun tryPair(b3: Double, b2: Double) {
            if (b3 > 1e-9 || b2 > 1e-9) return
            val m = s.meanLogProb(b3, b2)
            if (m > best.meanLp) best = Best(b3, b2, m)
        }
        var b3 = 0.0
        while (b3 >= -4.0) {
            var b2 = 0.0
            while (b2 >= -4.0) {
                tryPair(b3, b2)
                b2 -= 0.5
            }
            b3 -= 0.5
        }
        val c3 = best.b3
        val c2 = best.b2
        var d3 = -0.5
        while (d3 <= 0.5) {
            var d2 = -0.5
            while (d2 <= 0.5) {
                tryPair(c3 + d3, c2 + d2)
                d2 += 0.1
            }
            d3 += 0.1
        }
        return best
    }

    class NextWord(val positions: Int, val top1: Int, val top3: Int, val covered: Int)

    /**
     * Next-word prediction: candidates are the stored trigram successors of (w-2, w-1) plus the
     * stored bigram successors of w-1, ranked by their back-off score, and compared with the actual
     * next word. Positions without a history (after an out-of-vocabulary word) are skipped.
     */
    fun nextWord(s: EvalSample, b3: Double): NextWord {
        val dict = s.dict
        val triIds = IntArray(512)
        val triLp = FloatArray(512)
        val biIds = IntArray(2048)
        val biLp = FloatArray(2048)
        var positions = 0
        var t1 = 0
        var t3 = 0
        var covered = 0
        val candId = IntArray(2560)
        val candScore = DoubleArray(2560)
        for (i in 0 until s.size) {
            val p1 = s.p1(i)
            if (p1 < 0) continue
            val p2 = s.p2(i)
            positions++
            var n = 0
            var nTri = 0
            if (p2 >= 0) {
                nTri = dict.topSuccessors(p2, p1, triIds.size, triIds, triLp)
                for (k in 0 until nTri) {
                    candId[n] = triIds[k]
                    candScore[n++] = triLp[k].toDouble()
                }
            }
            val nBi = dict.topSuccessors(p1, biIds.size, biIds, biLp)
            val penalty = if (p2 >= 0) b3 else 0.0
            for (k in 0 until nBi) {
                var dup = false
                for (j in 0 until nTri) if (triIds[j] == biIds[k]) dup = true
                if (dup) continue
                candId[n] = biIds[k]
                candScore[n++] = penalty + biLp[k]
            }
            if (n > 0) covered++
            val target = s.id(i)
            var tIdx = -1
            for (k in 0 until n) if (candId[k] == target) tIdx = k
            if (tIdx >= 0) {
                val sc = candScore[tIdx]
                var rank = 0
                for (k in 0 until n) {
                    if (candScore[k] > sc || (candScore[k] == sc && candId[k] < target)) rank++
                }
                if (rank == 0) t1++
                if (rank < 3) t3++
            }
        }
        return NextWord(positions, t1, t3, covered)
    }

    /** Debug (`-Dnext="how are|thank|<s>"`): the top next words after a word or word pair. */
    private fun printNext(dict: Dictionary, context: String) {
        fun id(w: String) = if (w == "<s>") Dictionary.BOS_ID else dict.wordId(w)
        val parts = context.trim().split(Regex("[ _]")).map(::id)
        val ids = IntArray(8)
        val lp = FloatArray(8)
        val n =
            if (parts.size >= 2) {
                dict.topSuccessors(parts[parts.size - 2], parts.last(), 8, ids, lp)
            } else dict.topSuccessors(parts.last(), 8, ids, lp)
        println(
            "after \"$context\": " +
                (0 until n).joinToString(", ") { "%s (%.2f)".format(dict.word(ids[it]), lp[it]) }
        )
    }

    fun execute(opts: EvalOptions) {
        val dict = mapDictionary(opts.dict)
        val sizeBytes = Files.size(opts.dict)
        println(
            "dictionary: ${opts.dict} ($sizeBytes bytes, %.2f MiB)".format(sizeBytes / 1048576.0)
        )
        println(
            "  words=${dict.wordCount - Dictionary.FIRST_WORD_ID} bigrams=${dict.bigramCount} " +
                "trigrams=${dict.trigramCount} lang=${dict.metadata.languageTag} " +
                "version=${dict.metadata.dataVersion} date=${dict.metadata.buildDate}"
        )
        System.getProperty("next")?.split('|')?.forEach { printNext(dict, it) }
        val s = sample(dict, opts)
        println(
            "held-out: ${s.sentences} sentences, ${s.tokens} tokens, scored ${s.size} " +
                "in-vocabulary; OOV rate %.2f%%".format(100.0 * s.oov / s.tokens)
        )
        println(s.breakdown())
        System.getProperty("dump")?.let { s.dump(it.toInt()) }
        println("unigram-only perplexity: %.1f".format(exp(-s.unigramOnly())))
        val best =
            if (opts.penalty2 != null && opts.penalty3 != null) {
                Best(opts.penalty3, opts.penalty2, s.meanLogProb(opts.penalty3, opts.penalty2))
            } else {
                searchPenalties(s)
            }
        println(
            "back-off penalties b3=%.2f b2=%.2f -> normalised perplexity %.2f"
                .format(best.b3, best.b2, exp(-best.meanLp))
        )
        for (single in listOf(0.0, -0.25, -0.5, -1.0, -1.5, -2.0)) {
            println(
                "    single penalty %.2f -> perplexity %.2f"
                    .format(single, exp(-s.meanLogProb(single, single)))
            )
        }
        val nw = nextWord(s, best.b3)
        println(
            "next word (b3=%.2f): positions=%d top1=%.2f%% top3=%.2f%% candidates available %.1f%%"
                .format(
                    best.b3,
                    nw.positions,
                    100.0 * nw.top1 / nw.positions,
                    100.0 * nw.top3 / nw.positions,
                    100.0 * nw.covered / nw.positions,
                )
        )
        println(
            ("RESULT size=$sizeBytes ppl=%.3f top1=%.3f top3=%.3f b3=%.2f b2=%.2f oov=%.4f").format(
                exp(-best.meanLp),
                100.0 * nw.top1 / nw.positions,
                100.0 * nw.top3 / nw.positions,
                best.b3,
                best.b2,
                s.oov.toDouble() / s.tokens,
            )
        )
    }
}
