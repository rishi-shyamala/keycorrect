package dev.keycorrect.tools.dictbuild

import java.io.BufferedWriter
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Arrays
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * `dictbuild count`: streams the AOSP wordlist, Simple English Wikipedia and Tatoeba and writes
 * `vocab.tsv`, `unigrams.tsv`, `bigrams.tsv`, `trigrams.tsv`, `heldout.txt` and `stats.json`.
 */
object CountCommand {
    private val startNanos = System.nanoTime()

    fun run(args: List<String>): Int {
        if (args.contains("--help") || args.contains("-h")) {
            println(CountOptions.USAGE)
            return 0
        }
        val opts =
            try {
                CountOptions.parse(args)
            } catch (e: IllegalArgumentException) {
                System.err.println("error: ${e.message}\n${CountOptions.USAGE}")
                return 2
            }
        execute(opts)
        return 0
    }

    private fun log(msg: String) =
        System.err.println("[%7.1fs] %s".format((System.nanoTime() - startNanos) / 1e9, msg))

    /** Runs the whole pipeline; returns the stats that were written to `stats.json`. */
    fun execute(opts: CountOptions): Map<String, Any?> {
        val phases = LinkedHashMap<String, Any?>()
        var phaseStart = System.nanoTime()

        fun endPhase(name: String) {
            val now = System.nanoTime()
            phases[name] = round1((now - phaseStart) / 1e9)
            phaseStart = now
        }
        Files.createDirectories(opts.out)

        // --- vocabulary -----------------------------------------------------------------------
        log("reading vocabulary ${opts.vocab}")
        val wordlist = Sources.openReader(opts.vocab).use { AospWordlistParser.parse(it) }
        val vocab = Vocabulary(wordlist.entries)
        writeVocab(opts.out.resolve("vocab.tsv"), wordlist)
        log("vocabulary: ${wordlist.entries.size} entries, ${vocab.size} words")
        endPhase("vocabulary")

        val counts = CorpusCounts(vocab, opts.tatoebaIgnoreNames)
        val sourceInfo = LinkedHashMap<String, Any?>()

        // --- Tatoeba --------------------------------------------------------------------------
        if (!opts.skipTatoeba) {
            sourceInfo["tatoeba"] = countTatoeba(opts, counts)
            endPhase("tatoeba")
        }

        // --- Wikipedia ------------------------------------------------------------------------
        if (!opts.skipWiki) {
            val dump = opts.wiki ?: CountOptions.findWikiDump(Path.of("data/raw"))
            if (dump == null || !Files.exists(dump)) {
                throw IllegalStateException(
                    "no Wikipedia dump found; run tools/data/fetch-sources.sh"
                )
            }
            sourceInfo["wikipedia"] = countWikipedia(opts, dump, counts)
            endPhase("wikipedia")
        }

        // --- output ---------------------------------------------------------------------------
        val ngramStats = LinkedHashMap<String, Any?>()
        // Number of distinct predecessors of each word (Kneser-Ney continuation counts), filled
        // from the *unpruned* bigram table while writing it.
        val continuation = IntArray(vocab.size + 1)
        ngramStats["bigrams"] =
            writeNgrams(
                opts.out.resolve("bigrams.tsv"),
                opts.out.resolve("bigram_contexts.tsv"),
                "#w1\tw2\tcount\twiki\ttatoeba",
                counts.bigrams,
                2,
                opts.minBigramCount,
                opts.maxBigramSuccessors,
                opts.tatoebaWeight,
                vocab,
                continuation,
            )
        ngramStats["unigrams"] = writeUnigrams(opts, vocab, counts, continuation)
        ngramStats["trigrams"] =
            writeNgrams(
                opts.out.resolve("trigrams.tsv"),
                opts.out.resolve("trigram_contexts.tsv"),
                "#w1\tw2\tw3\tcount\twiki\ttatoeba",
                counts.trigrams,
                3,
                opts.minTrigramCount,
                opts.maxTrigramSuccessors,
                opts.tatoebaWeight,
                vocab,
                null,
            )
        writeCountOfCounts(opts.out.resolve("count_of_counts.tsv"), ngramStats)
        endPhase("write")

        // --- stats ----------------------------------------------------------------------------
        val combined = SourceStats()
        for (s in counts.stats) {
            combined.sentences += s.sentences
            combined.tokens += s.tokens
            combined.inVocab += s.inVocab
            combined.oovNumeric += s.oovNumeric
            combined.oovOther += s.oovOther
        }
        var seen = 0
        for (id in 1..vocab.size) if (counts.unigrams.any { it[id] > 0 }) seen++
        val total = (System.nanoTime() - startNanos) / 1e9
        val stats = linkedMapOf<String, Any?>()
        stats["tool"] = "dictbuild count"
        stats["options"] =
            linkedMapOf(
                "vocab" to opts.vocab.fileName.toString(),
                "tatoebaWeight" to opts.tatoebaWeight,
                "heldoutPercent" to opts.heldoutPercent,
                "minBigramCount" to opts.minBigramCount,
                "minTrigramCount" to opts.minTrigramCount,
                "maxBigramSuccessors" to opts.maxBigramSuccessors,
                "maxTrigramSuccessors" to opts.maxTrigramSuccessors,
                "maxWikiPages" to
                    if (opts.maxWikiPages == Long.MAX_VALUE) null else opts.maxWikiPages,
            )
        stats["vocabulary"] =
            linkedMapOf(
                "header" to wordlist.header,
                "entries" to wordlist.entries.size,
                "words" to vocab.size,
                "notAWordEntries" to wordlist.entries.count { it.notAWord },
                "possiblyOffensive" to
                    wordlist.entries.count { VocabEntry.POSSIBLY_OFFENSIVE in it.flags },
                "skippedShortcutAndBigramLines" to wordlist.skippedChildLines,
            )
        stats["sources"] = sourceInfo
        stats["combined"] =
            combined.toMap() +
                linkedMapOf(
                    "vocabWordsSeen" to seen,
                    "vocabWordCoverage" to
                        if (vocab.size == 0) 0.0 else seen.toDouble() / vocab.size,
                )
        stats["ngrams"] = ngramStats
        stats["runtime"] = linkedMapOf("totalSeconds" to round1(total), "phaseSeconds" to phases)
        stats["memory"] = peakMemory()
        Files.writeString(opts.out.resolve("stats.json"), Json.write(stats))
        log("done in %.1fs; stats written to %s".format(total, opts.out.resolve("stats.json")))
        return stats
    }

    // --- sources ------------------------------------------------------------------------------

    private fun countTatoeba(opts: CountOptions, counts: CorpusCounts): Map<String, Any?> {
        val heldPath = opts.out.resolve("heldout.txt")
        val heldKeys = HashSet<Long>()
        var read = 0L
        var held = 0L
        log("tatoeba pass 1: selecting held-out sentences (${opts.heldoutPercent}%)")
        Sources.openReader(opts.tatoeba).use { reader ->
            Files.newBufferedWriter(heldPath, StandardCharsets.UTF_8).use { w ->
                reader.forEachLine { line ->
                    val s = Tatoeba.parseLine(line) ?: return@forEachLine
                    if (s.lang != "eng") return@forEachLine
                    read++
                    if (Tatoeba.isHeldOut(s.id, opts.heldoutPercent)) {
                        held++
                        heldKeys.add(Tatoeba.textKey(s.text))
                        w.write(s.text.replace('\t', ' ').replace('\n', ' '))
                        w.write("\n")
                    }
                }
            }
        }
        log("tatoeba pass 2: counting ($read sentences, $held held out)")
        var duplicates = 0L
        Sources.openReader(opts.tatoeba).use { reader ->
            reader.forEachLine { line ->
                val s = Tatoeba.parseLine(line) ?: return@forEachLine
                if (s.lang != "eng" || Tatoeba.isHeldOut(s.id, opts.heldoutPercent)) {
                    return@forEachLine
                }
                // Same text under a different id as a held-out sentence: keep it out of training.
                if (heldKeys.contains(Tatoeba.textKey(s.text))) {
                    duplicates++
                    return@forEachLine
                }
                counts.addSentence(s.text, CorpusCounts.TATOEBA)
            }
        }
        return linkedMapOf(
            "file" to opts.tatoeba.fileName.toString(),
            "sentencesRead" to read,
            "heldOutSentences" to held,
            "droppedDuplicatesOfHeldOut" to duplicates,
        ) + counts.stats[CorpusCounts.TATOEBA].toMap()
    }

    private fun countWikipedia(
        opts: CountOptions,
        dump: Path,
        counts: CorpusCounts,
    ): Map<String, Any?> {
        log("wikipedia: streaming $dump")
        val queue = ArrayBlockingQueue<Any>(256)
        val poison = Any()
        val failure = AtomicReference<Throwable?>()
        val stop = AtomicBoolean(false)
        val reader =
            Thread(
                {
                    try {
                        Sources.openStream(dump).use { input ->
                            WikiDumpReader.read(input) { page ->
                                queue.put(page)
                                !stop.get()
                            }
                        }
                    } catch (t: Throwable) {
                        failure.set(t)
                    } finally {
                        queue.put(poison)
                    }
                },
                "wiki-reader",
            )
        reader.isDaemon = true
        reader.start()

        var pagesSeen = 0L
        var redirects = 0L
        var otherNs = 0L
        var articles = 0L
        while (true) {
            val item = queue.take()
            if (item === poison) break
            if (stop.get()) continue
            val page = item as WikiPage
            pagesSeen++
            when {
                page.ns != 0 -> otherNs++
                page.redirect || page.text.startsWith("#REDIRECT", ignoreCase = true) -> redirects++
                else -> {
                    articles++
                    WikiText.sentences(page.text) { counts.addSentence(it, CorpusCounts.WIKI) }
                    if (articles >= opts.maxWikiPages) stop.set(true)
                }
            }
            if (pagesSeen % 25_000 == 0L) {
                log(
                    "wikipedia: $pagesSeen pages, $articles articles, ${counts.stats[0].tokens} tokens"
                )
            }
        }
        failure.get()?.let { throw IllegalStateException("reading ${dump.fileName} failed", it) }
        return linkedMapOf(
            "file" to dump.fileName.toString(),
            "pagesSeen" to pagesSeen,
            "articlesCounted" to articles,
            "redirectsSkipped" to redirects,
            "otherNamespacePagesSkipped" to otherNs,
        ) + counts.stats[CorpusCounts.WIKI].toMap()
    }

    // --- output -------------------------------------------------------------------------------

    private fun writeVocab(path: Path, list: AospWordlist) {
        Files.newBufferedWriter(path, StandardCharsets.UTF_8).use { w ->
            w.write("#word\tfreq\toriginalFreq\tflags\n")
            for (e in list.entries) {
                w.write("${e.word}\t${e.freq}\t${e.originalFreq}\t${e.flags.joinToString(",")}\n")
            }
        }
    }

    private fun weighted(wiki: Int, tatoeba: Int, weight: Double) = wiki + weight * tatoeba

    private fun writeUnigrams(
        opts: CountOptions,
        vocab: Vocabulary,
        counts: CorpusCounts,
        continuation: IntArray,
    ): Map<String, Any?> {
        val wiki = counts.unigrams[CorpusCounts.WIKI]
        val tat = counts.unigrams[CorpusCounts.TATOEBA]
        val ids =
            (0..vocab.size)
                .filter { wiki[it] + tat[it] > 0 }
                .sortedWith(
                    compareByDescending<Int> { weighted(wiki[it], tat[it], opts.tatoebaWeight) }
                        .thenBy { it }
                )
        Files.newBufferedWriter(opts.out.resolve("unigrams.tsv"), StandardCharsets.UTF_8).use { w ->
            w.write("#word\tcount\twiki\ttatoeba\tcont\n")
            for (id in ids) {
                w.write(
                    "${vocab.word(id)}\t${fmt(weighted(wiki[id], tat[id], opts.tatoebaWeight))}" +
                        "\t${wiki[id]}\t${tat[id]}\t${continuation[id]}\n"
                )
            }
        }
        return linkedMapOf("written" to ids.size) // includes the <s> row
    }

    /**
     * Writes one n-gram order. Prunes in two steps, both reported: keys with fewer than [minRaw]
     * raw occurrences are dropped, then each context keeps its [maxSucc] highest weighted
     * successors. Output is grouped by context (ascending word id), successors by count desc.
     *
     * Because pruning discards exactly what a probability estimator needs to normalise (context
     * totals, number of distinct successors, singleton counts), the statistics of the *unpruned*
     * table are written alongside: [contextPath] gets one row per context that has at least one
     * written n-gram (`wiki` / `tatoeba` = total count of all its successors, `distinct` = number
     * of distinct successors, `tatShare` = sum over the successors of the Tatoeba fraction `tatoeba
     * / (wiki + tatoeba)` of their count), [continuation] (bigrams only) receives, per word, the
     * number of distinct predecessors, and the returned stats contain the raw count-of-counts
     * n1..n4 used to estimate absolute discounts.
     */
    private fun writeNgrams(
        path: Path,
        contextPath: Path,
        header: String,
        map: LongCountMap,
        order: Int,
        minRaw: Int,
        maxSucc: Int,
        weight: Double,
        vocab: Vocabulary,
        continuation: IntArray?,
    ): Map<String, Any?> {
        val before = map.size
        val keys = map.sortedKeys(1)
        val countOfCounts = LongArray(5) // [1..4]: number of keys with raw total == i; [0]: >= 5
        var afterMin = 0
        var written = 0L
        var contexts = 0L
        var buf = LongArray(1024)
        val ctxShift = CorpusCounts.BITS
        Files.newBufferedWriter(path, StandardCharsets.UTF_8).use { w ->
            Files.newBufferedWriter(contextPath, StandardCharsets.UTF_8).use { cw ->
                w.write(header)
                w.write("\n")
                cw.write(
                    if (order == 2) "#w1\twiki\ttatoeba\tdistinct\ttatShare\n"
                    else "#w1\tw2\twiki\ttatoeba\tdistinct\ttatShare\n"
                )
                var s = 0
                while (s < keys.size) {
                    val ctx = keys[s] ushr ctxShift
                    var e = s + 1
                    while (e < keys.size && keys[e] ushr ctxShift == ctx) e++
                    val m = e - s
                    if (buf.size < m) buf = LongArray(maxOf(m, buf.size * 2))
                    var n = 0
                    var totalWiki = 0L
                    var totalTat = 0L
                    var tatShare = 0.0
                    for (k in s until e) {
                        val key = keys[k]
                        val wk = map.count(key, 0)
                        val tk = map.count(key, 1)
                        totalWiki += wk
                        totalTat += tk
                        tatShare += tk.toDouble() / (wk + tk)
                        val raw = wk + tk
                        countOfCounts[if (raw in 1..4) raw else 0]++
                        continuation?.let { it[(key and CorpusCounts.MASK).toInt()]++ }
                        if (raw < minRaw) continue
                        afterMin++
                        val wt = weighted(wk, tk, weight).toFloat()
                        if (wt <= 0f) continue
                        val inv = Int.MAX_VALUE - java.lang.Float.floatToRawIntBits(wt)
                        buf[n++] = (inv.toLong() shl 32) or (key and CorpusCounts.MASK)
                    }
                    Arrays.sort(buf, 0, n)
                    val keep = minOf(n, maxSucc)
                    for (k in 0 until keep) {
                        val succ = (buf[k] and 0xFFFFFFFFL)
                        val key = (ctx shl CorpusCounts.BITS) or succ
                        writeNgramLine(w, key, order, map, weight, vocab)
                        written++
                    }
                    if (keep > 0) {
                        contexts++
                        if (order == 3) {
                            cw.write(vocab.word((ctx ushr CorpusCounts.BITS).toInt()))
                            cw.write("\t")
                        }
                        cw.write(vocab.word((ctx and CorpusCounts.MASK).toInt()))
                        cw.write(
                            "\t$totalWiki\t$totalTat\t${e - s}\t" +
                                "%.3f\n".format(java.util.Locale.ROOT, tatShare)
                        )
                    }
                    s = e
                }
            }
        }
        log(
            "${path.fileName}: $before unique, $afterMin after min-count, $written written " +
                "($contexts contexts)"
        )
        return linkedMapOf(
            "uniqueBeforePruning" to before,
            "afterMinCount" to afterMin,
            "afterMaxSuccessors" to written,
            "contexts" to contexts,
            "countOfCounts" to
                linkedMapOf(
                    "n1" to countOfCounts[1],
                    "n2" to countOfCounts[2],
                    "n3" to countOfCounts[3],
                    "n4" to countOfCounts[4],
                    "n5plus" to countOfCounts[0],
                ),
        )
    }

    /**
     * `order n1 n2 n3 n4 n5plus` rows: raw count-of-counts of the unpruned bigram / trigram tables.
     */
    private fun writeCountOfCounts(path: Path, ngramStats: Map<String, Any?>) {
        val sb = StringBuilder("#order\tn1\tn2\tn3\tn4\tn5plus\n")
        for ((order, name) in listOf(2 to "bigrams", 3 to "trigrams")) {
            @Suppress("UNCHECKED_CAST")
            val coc = (ngramStats.getValue(name) as Map<String, Any?>).getValue("countOfCounts")
            @Suppress("UNCHECKED_CAST") val m = coc as Map<String, Any?>
            sb.append(order)
            for (k in listOf("n1", "n2", "n3", "n4", "n5plus")) sb.append('\t')
                .append(m.getValue(k))
            sb.append('\n')
        }
        Files.writeString(path, sb.toString())
    }

    private fun writeNgramLine(
        w: BufferedWriter,
        key: Long,
        order: Int,
        map: LongCountMap,
        weight: Double,
        vocab: Vocabulary,
    ) {
        if (order == 3) {
            w.write(vocab.word(((key ushr (2 * CorpusCounts.BITS)) and CorpusCounts.MASK).toInt()))
            w.write("\t")
        }
        w.write(vocab.word(((key ushr CorpusCounts.BITS) and CorpusCounts.MASK).toInt()))
        w.write("\t")
        w.write(vocab.word((key and CorpusCounts.MASK).toInt()))
        val wiki = map.count(key, 0)
        val tat = map.count(key, 1)
        w.write("\t${fmt(weighted(wiki, tat, weight))}\t$wiki\t$tat\n")
    }

    private fun fmt(d: Double): String =
        if (d == Math.rint(d)) d.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, d)

    private fun round1(d: Double) = Math.round(d * 10) / 10.0

    private fun peakMemory(): Map<String, Any?> {
        val heapPeak =
            ManagementFactory.getMemoryPoolMXBeans()
                .filter { it.type == MemoryType.HEAP }
                .sumOf { it.peakUsage.used }
        val rssKb =
            try {
                Files.readAllLines(Path.of("/proc/self/status"))
                    .firstOrNull { it.startsWith("VmHWM:") }
                    ?.filter { it.isDigit() }
                    ?.toLongOrNull()
            } catch (_: Exception) {
                null
            }
        return linkedMapOf(
            "peakHeapMiB" to heapPeak / (1024 * 1024),
            "peakRssMiB" to rssKb?.let { it / 1024 },
            "maxHeapMiB" to Runtime.getRuntime().maxMemory() / (1024 * 1024),
        )
    }
}
