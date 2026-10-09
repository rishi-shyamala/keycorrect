package dev.keycorrect.core.dictionary

import java.util.Random
import kotlin.math.exp
import kotlin.math.ln

/** Procedurally generated dictionaries for tests (deterministic for a given seed). */
object SyntheticData {
    private val consonants = "bdfghklmnprstvzñ".toCharArray()
    private val vowels = "aeiouéü".toCharArray()
    private val syllables = consonants.flatMap { c -> vowels.map { v -> "$c$v" } }.toTypedArray()

    val METADATA =
        DictionaryMetadata(
            languageTag = "en-US",
            buildDate = "2026-10-09",
            dataVersion = 7,
            attribution = "Synthetic test data é CC0",
        )

    /** Hand-written entries every generated dictionary contains (case variants, accents...). */
    private val handWritten =
        listOf(
            WordEntry("the", -2.5f),
            WordEntry("of", -3.0f),
            WordEntry("to", -3.1f),
            WordEntry("I", -3.2f, WordFlags.PROPER_NOUN),
            WordEntry("iPhone", -9.5f, WordFlags.PROPER_NOUN),
            WordEntry("us", -6.0f),
            WordEntry("US", -7.0f, WordFlags.PROPER_NOUN),
            WordEntry("McDonald", -11.0f, WordFlags.PROPER_NOUN),
            WordEntry("café", -8.0f),
            WordEntry("Café", -12.0f),
            WordEntry("naïve", -9.0f),
            WordEntry("İstanbul", -10.0f, WordFlags.PROPER_NOUN),
            WordEntry("damn", -9.2f, WordFlags.POSSIBLY_OFFENSIVE),
            WordEntry("etc", -9.9f, WordFlags.NOT_A_WORD),
        )

    private fun logUniform(rnd: Random, n: Int): Int {
        val r = exp(rnd.nextDouble() * ln(n.toDouble())).toInt() - 1
        return r.coerceIn(0, n - 1)
    }

    fun words(rnd: Random, count: Int): List<WordEntry> {
        val out = ArrayList<WordEntry>(count + 32)
        val seenSurface = HashSet<String>()
        val seenKeys = HashSet<String>()
        for (w in handWritten) {
            out.add(w)
            seenSurface.add(w.surface)
            seenKeys.add(w.surface.lowercase())
        }
        while (out.size < count) {
            val sb = StringBuilder()
            val len = 2 + rnd.nextInt(3) + (if (count > 20000) 1 else 0)
            repeat(len) { sb.append(syllables[rnd.nextInt(syllables.size)]) }
            val base = sb.toString()
            if (!seenKeys.add(base)) continue
            // Frequency drawn log-uniformly; ranks are implied by the sort in the writer.
            val lp = (-2.0 - 10.0 * Math.pow(rnd.nextDouble(), 0.7)).toFloat()
            val offensive = if (rnd.nextInt(100) < 3) WordFlags.POSSIBLY_OFFENSIVE else 0
            val kind = rnd.nextInt(100)
            val cap = base.substring(0, 1).uppercase() + base.substring(1)
            when {
                kind < 80 -> out.add(WordEntry(base, lp, offensive))
                kind < 86 -> {
                    out.add(WordEntry(base, lp, offensive))
                    out.add(WordEntry(cap, lp - 1.5f, WordFlags.PROPER_NOUN))
                }
                kind < 87 -> {
                    out.add(WordEntry(base, lp, offensive))
                    out.add(WordEntry(base.uppercase(), lp - 2.5f, WordFlags.PROPER_NOUN))
                }
                kind < 92 -> out.add(WordEntry(cap, lp, WordFlags.PROPER_NOUN))
                kind < 94 ->
                    out.add(
                        WordEntry(
                            base.substring(0, 2).uppercase() +
                                base.substring(2, 3).uppercase() +
                                base.substring(3),
                            lp,
                            WordFlags.PROPER_NOUN,
                        )
                    )
                else -> out.add(WordEntry(base, lp, offensive))
            }
        }
        return out
    }

    /** Deterministic dictionary: words with Zipf-like skew, bigrams/trigrams between them. */
    fun make(seed: Long, words: Int, bigrams: Int, trigrams: Int): DictionaryData {
        val rnd = Random(seed)
        val ws = words(rnd, words)
        // Rank by frequency: n-gram sampling is skewed towards frequent words.
        val ranked =
            ws.sortedWith(compareByDescending<WordEntry> { it.logProb }.thenBy { it.surface })
        val n = ranked.size
        // Index n stands for <s> as w1 and for </s> as w2/w3.
        fun name(i: Int, first: Boolean) =
            if (i < n) ranked[i].surface
            else if (first) Dictionary.BOS_TOKEN else Dictionary.EOS_TOKEN

        val bi = IntArray(bigrams)
        val bj = IntArray(bigrams)
        val seen = HashSet<Long>(bigrams * 2)
        var count = 0
        // Sentence-start successors: a large list (exercises skip tables).
        val startCount = minOf(600, n / 2, bigrams / 2)
        while (count < startCount) {
            val j = logUniform(rnd, n)
            if (seen.add((n.toLong() shl 32) or j.toLong())) {
                bi[count] = n
                bj[count++] = j
            }
        }
        while (count < bigrams) {
            val i = logUniform(rnd, n)
            val j = if (rnd.nextInt(60) == 0) n else logUniform(rnd, n)
            if (seen.add((i.toLong() shl 32) or j.toLong())) {
                bi[count] = i
                bj[count++] = j
            }
        }
        val bg = List(bigrams) { BigramEntry(name(bi[it], true), name(bj[it], false), nlp(rnd)) }

        val tg = ArrayList<TrigramEntry>(trigrams)
        val tseen = HashSet<Long>(trigrams * 2)
        val nn = (n + 1).toLong()
        var attempts = 0
        while (tg.size < trigrams && attempts++ < trigrams * 20) {
            val i1: Int
            val i2: Int
            val pick = rnd.nextInt(100)
            if (pick < 5) { // a context without a stored bigram
                i1 = logUniform(rnd, n)
                i2 = logUniform(rnd, n)
            } else if (pick < 12) {
                i1 = n
                i2 = bj[rnd.nextInt(startCount)]
            } else {
                val b = startCount + rnd.nextInt(bigrams - startCount)
                if (bj[b] == n) continue
                i1 = bi[b]
                i2 = bj[b]
            }
            val i3 = if (rnd.nextInt(60) == 0) n else logUniform(rnd, n)
            if (tseen.add((i1 * nn + i2) * nn + i3)) {
                tg.add(TrigramEntry(name(i1, true), name(i2, false), name(i3, false), nlp(rnd)))
            }
        }
        return DictionaryData(METADATA, ws, bg, tg)
    }

    /** Natural-log probability in roughly [-13, -0.1]. */
    private fun nlp(rnd: Random): Float = (-0.1 - 12.9 * Math.pow(rnd.nextDouble(), 0.6)).toFloat()
}
