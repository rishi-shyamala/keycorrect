package dev.keycorrect.tools.dictbuild

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Command-line options of `dictbuild count`. */
data class CountOptions(
    val vocab: Path = Paths.get("data/raw/en_US_wordlist.combined.gz"),
    /** Null = auto-detect the newest data/raw/simplewiki-*-pages-articles.xml.bz2. */
    val wiki: Path? = null,
    val skipWiki: Boolean = false,
    val tatoeba: Path = Paths.get("data/raw/eng_sentences.tsv.bz2"),
    val skipTatoeba: Boolean = false,
    val out: Path = Paths.get("data/processed"),
    /** Multiplier applied to Tatoeba counts when combining with Wikipedia counts. */
    val tatoebaWeight: Double = 3.0,
    /** Exact-case tokens ignored (treated as OOV) in Tatoeba sentences; Wikipedia is unaffected. */
    val tatoebaIgnoreNames: Set<String> = setOf("Tom", "Mary"),
    /** Percentage (0..100) of Tatoeba sentence ids held out for evaluation. */
    val heldoutPercent: Int = 5,
    /** Minimum raw (unweighted, wiki+tatoeba) occurrences for a bigram / trigram to be kept. */
    val minBigramCount: Int = 2,
    val minTrigramCount: Int = 2,
    /** Maximum successors kept per bigram context (w1) / trigram context (w1,w2). */
    val maxBigramSuccessors: Int = 1000,
    val maxTrigramSuccessors: Int = 200,
    /** Debug: stop after this many main-namespace Wikipedia pages. */
    val maxWikiPages: Long = Long.MAX_VALUE,
) {
    companion object {
        const val USAGE =
            """
dictbuild count [options]
  --vocab PATH               AOSP *.combined[.gz] wordlist      (default data/raw/en_US_wordlist.combined.gz)
  --wiki PATH|none           Wikipedia pages-articles XML[.bz2]   (default: newest data/raw/simplewiki-*-pages-articles.xml.bz2)
  --tatoeba PATH|none        Tatoeba eng_sentences.tsv[.bz2]      (default data/raw/eng_sentences.tsv.bz2)
  --out DIR                  output directory                     (default data/processed)
  --tatoeba-weight W         weight of Tatoeba counts vs Wikipedia (default 3.0)
  --tatoeba-ignore-names A,B  Tatoeba-only tokens treated as OOV  (default Tom,Mary; empty = none)
  --heldout-percent P        % of Tatoeba sentence ids held out    (default 5)
  --min-bigram-count N       raw count needed to keep a bigram     (default 2)
  --min-trigram-count N      raw count needed to keep a trigram    (default 2)
  --max-bigram-successors N  top successors kept per word          (default 1000)
  --max-trigram-successors N top successors kept per word pair     (default 200)
  --max-wiki-pages N         debug: stop after N Wikipedia articles
"""

        fun parse(args: List<String>): CountOptions {
            var o = CountOptions()
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
                o =
                    when (flag) {
                        "--vocab" -> o.copy(vocab = Paths.get(value(flag, inline)))
                        "--wiki" ->
                            value(flag, inline).let {
                                if (it == "none") o.copy(skipWiki = true)
                                else o.copy(wiki = Paths.get(it))
                            }
                        "--tatoeba" ->
                            value(flag, inline).let {
                                if (it == "none") o.copy(skipTatoeba = true)
                                else o.copy(tatoeba = Paths.get(it))
                            }
                        "--out" -> o.copy(out = Paths.get(value(flag, inline)))
                        "--tatoeba-weight" -> o.copy(tatoebaWeight = value(flag, inline).toDouble())
                        "--tatoeba-ignore-names" ->
                            o.copy(
                                tatoebaIgnoreNames =
                                    value(flag, inline)
                                        .split(',')
                                        .filter { it.isNotBlank() }
                                        .toSet()
                            )
                        "--heldout-percent" -> o.copy(heldoutPercent = value(flag, inline).toInt())
                        "--min-bigram-count" -> o.copy(minBigramCount = value(flag, inline).toInt())
                        "--min-trigram-count" ->
                            o.copy(minTrigramCount = value(flag, inline).toInt())
                        "--max-bigram-successors" ->
                            o.copy(maxBigramSuccessors = value(flag, inline).toInt())
                        "--max-trigram-successors" ->
                            o.copy(maxTrigramSuccessors = value(flag, inline).toInt())
                        "--max-wiki-pages" -> o.copy(maxWikiPages = value(flag, inline).toLong())
                        else -> throw IllegalArgumentException("unknown option: $arg")
                    }
                i++
            }
            require(o.heldoutPercent in 0..100) { "--heldout-percent must be 0..100" }
            require(o.tatoebaWeight >= 0) { "--tatoeba-weight must be >= 0" }
            return o
        }

        /** Newest `simplewiki-*-pages-articles.xml.bz2` in [dir], or null. */
        fun findWikiDump(dir: Path): Path? {
            if (!Files.isDirectory(dir)) return null
            Files.list(dir).use { s ->
                return s.filter {
                        val n = it.fileName.toString()
                        n.startsWith("simplewiki-") && n.contains("pages-articles")
                    }
                    .max(compareBy { it.fileName.toString() })
                    .orElse(null)
            }
        }
    }
}
