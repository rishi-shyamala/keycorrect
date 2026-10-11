package dev.keycorrect.tools.eval

import dev.keycorrect.core.dictionary.Dictionary
import dev.keycorrect.core.dictionary.WordFlags
import dev.keycorrect.core.engineapi.PredictRequest
import dev.keycorrect.core.engineapi.TypingContext
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * One entry of the common-misspellings list: a typed (misspelled) word and the accepted
 * corrections, each a single suggestable dictionary word. [realWord] is true when the misspelling
 * is itself a dictionary word (a real-word error: handled by the stricter real-word margin, so it
 * is reported apart from the headline).
 */
data class MisspellEntry(
    val typed: String,
    val corrections: Set<String>,
    val realWord: Boolean,
    val index: Int,
) {
    /** The [MisspellCategory] of the nearest correction. */
    val category: String by lazy { MisspellCategory.of(typed, corrections) }
}

/** How the list was filtered, for the report. */
data class MisspellListStats(
    val lines: Int,
    val entries: Int,
    val droppedBadTyped: Int,
    val droppedNoDictionaryCorrection: Int,
    val droppedDuplicate: Int,
    val realWordEntries: Int,
    val multiCorrection: Int,
)

/**
 * Parser for the Wikipedia "Lists of common misspellings/For machines" wikitext (EVAL ONLY, see
 * docs/DATA_SOURCES.md): ` misspelling->correction[, alternative ...]`.
 */
object MisspellList {
    class Parsed(val entries: List<MisspellEntry>, val stats: MisspellListStats)

    fun parse(lines: List<String>, dictionary: Dictionary): Parsed {
        val seen = HashSet<String>()
        val out = ArrayList<MisspellEntry>()
        var arrows = 0
        var badTyped = 0
        var noCorrection = 0
        var dup = 0
        var real = 0
        var multi = 0
        for (raw in lines) {
            val arrow = raw.indexOf("->")
            if (arrow < 0) continue
            arrows++
            val typed = raw.substring(0, arrow).trim().lowercase()
            if (typed.length < 2 || typed.any { !(it in 'a'..'z' || it == '\'') }) {
                badTyped++
                continue
            }
            val corrections =
                raw.substring(arrow + 2)
                    .split(',')
                    .map { it.trim().lowercase() }
                    .filter { c ->
                        c.isNotEmpty() &&
                            c != typed &&
                            c.all { it in 'a'..'z' || it == '\'' } &&
                            suggestable(dictionary, c)
                    }
                    .toCollection(LinkedHashSet())
            if (corrections.isEmpty()) {
                noCorrection++
                continue
            }
            if (!seen.add(typed)) {
                dup++
                continue
            }
            val realWord = dictionary.findNode(typed).let { it >= 0 && dictionary.isTerminal(it) }
            if (realWord) real++
            if (corrections.size > 1) multi++
            out.add(MisspellEntry(typed, corrections, realWord, out.size))
        }
        return Parsed(
            out,
            MisspellListStats(arrows, out.size, badTyped, noCorrection, dup, real, multi),
        )
    }

    /** True if the dictionary has [word] as a word the engine is allowed to suggest. */
    private fun suggestable(dictionary: Dictionary, word: String): Boolean {
        val node = dictionary.findNode(word)
        if (node < 0 || !dictionary.isTerminal(node)) return false
        return (0 until dictionary.wordIdCountAt(node)).any {
            dictionary.flags(dictionary.wordIdAt(node, it)) and EXCLUDED == 0
        }
    }

    private const val EXCLUDED = WordFlags.POSSIBLY_OFFENSIVE or WordFlags.NOT_A_WORD

    /** Fixed dev/test partition of the entries (a hash of the word), [devPercent] % dev. */
    fun isDev(typed: String, devPercent: Int = DEV_PERCENT): Boolean {
        var z = typed.hashCode().toLong() * -0x61c8864680b583ebL + 0x1F83D9ABFB41BD6BL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        return Math.floorMod(z, 100L) < devPercent
    }

    const val DEV_PERCENT = 50

    fun select(entries: List<MisspellEntry>, split: Split): List<MisspellEntry> = entries.filter {
        split.contains(isDev(it.typed))
    }
}

/** What kind of slip turns the correction into the misspelling (nearest correction wins). */
object MisspellCategory {
    val ORDER =
        listOf(
            "transposition ie/ei",
            "transposition, other vowel pair",
            "transposition, other",
            "doubled letter missing",
            "doubled letter extra",
            "letter missing",
            "letter extra",
            "vowel for vowel",
            "other substitution",
            "2 edits",
            "3+ edits",
        )

    fun of(typed: String, corrections: Set<String>): String =
        corrections.map { classify(typed, it) to osa(typed, it) }.minByOrNull { it.second }!!.first

    private fun isVowel(c: Char) = c in "aeiouy"

    private fun classify(t: String, c: String): String {
        val d = osa(t, c)
        if (d >= 3) return "3+ edits"
        if (d == 2) return "2 edits"
        if (t.length == c.length) {
            val diff = t.indices.filter { t[it] != c[it] }
            if (
                diff.size == 2 &&
                    diff[1] == diff[0] + 1 &&
                    t[diff[0]] == c[diff[1]] &&
                    t[diff[1]] == c[diff[0]]
            ) {
                val pair = setOf(t[diff[0]], t[diff[1]])
                return when {
                    pair == setOf('i', 'e') -> "transposition ie/ei"
                    pair.all(::isVowel) -> "transposition, other vowel pair"
                    else -> "transposition, other"
                }
            }
            if (diff.size == 1) {
                return if (isVowel(t[diff[0]]) && isVowel(c[diff[0]])) "vowel for vowel"
                else "other substitution"
            }
        }
        if (t.length + 1 == c.length) {
            val i = t.indices.firstOrNull { t[it] != c[it] } ?: t.length
            val doubled = (i > 0 && c[i] == c[i - 1]) || (i + 1 < c.length && c[i] == c[i + 1])
            return if (doubled) "doubled letter missing" else "letter missing"
        }
        if (t.length == c.length + 1) {
            val i = c.indices.firstOrNull { t[it] != c[it] } ?: c.length
            val doubled = (i > 0 && t[i] == t[i - 1]) || (i + 1 < t.length && t[i] == t[i + 1])
            return if (doubled) "doubled letter extra" else "letter extra"
        }
        return "2 edits"
    }

    /** Optimal string alignment distance (adjacent transposition counts 1). */
    fun osa(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
            }
        }
        return d[a.length][b.length]
    }
}

/** One way of typing the list: touch noise and the text before the word. */
data class MisspellCondition(
    val name: String,
    val sigma: Float,
    val contexts: List<String>,
    val draws: Int,
)

/** Counters of one slice (all, one category, ...). */
class MisspellStats {
    var trials = 0L
    var applied = 0L
    var correct = 0L
    var wrong = 0L
    var top3 = 0L
    var top1 = 0L

    fun add(corrections: Set<String>, appliedWord: String?, top: List<String>) {
        trials++
        if (appliedWord == null) {
            // abstained
        } else {
            applied++
            if (appliedWord.lowercase() in corrections) correct++ else wrong++
        }
        if (top.any { it.lowercase() in corrections }) top3++
        if (top.firstOrNull()?.lowercase() in corrections) top1++
    }

    fun merge(o: MisspellStats) {
        trials += o.trials
        applied += o.applied
        correct += o.correct
        wrong += o.wrong
        top3 += o.top3
        top1 += o.top1
    }

    /** Autocorrect applied one of the listed corrections. */
    val accuracy
        get() = Stats.ratio(correct, trials)

    val top3Recall
        get() = Stats.ratio(top3, trials)

    val top1Rate
        get() = Stats.ratio(top1, trials)

    /** No autocorrection applied (the misspelling stays as typed). */
    val abstainRate
        get() = Stats.ratio(trials - applied, trials)

    /** An autocorrection was applied but is not a listed correction. */
    val wrongRate
        get() = Stats.ratio(wrong, trials)
}

/** All slices of one condition: the entries split by real-word status and by category. */
class MisspellConditionResult(val condition: MisspellCondition) {
    val nonWord = MisspellStats()
    val realWord = MisspellStats()
    val byCategory = LinkedHashMap<String, MisspellStats>()

    fun merge(o: MisspellConditionResult) {
        nonWord.merge(o.nonWord)
        realWord.merge(o.realWord)
        for ((k, v) in o.byCategory) byCategory.getOrPut(k) { MisspellStats() }.merge(v)
    }
}

class MisspellFailure(
    val condition: String,
    val entry: MisspellEntry,
    val context: String,
    val literal: String,
    val applied: String?,
    val top: List<String>,
)

class MisspellResult(
    val conditions: List<MisspellConditionResult>,
    val failures: List<MisspellFailure>,
)

/**
 * Types every misspelling with key-centre touches (and optionally with Gaussian touch noise) and
 * records what the engine does. Deterministic for a given [seed].
 */
class MisspellEvaluator(
    private val keyboard: EvalKeyboard,
    private val seed: Long,
    private val threads: Int,
    private val maxCandidates: Int = 3,
) {
    fun evaluate(
        entries: List<MisspellEntry>,
        conditions: List<MisspellCondition>,
        factory: () -> Predictor,
        failureLimit: Int = 0,
    ): MisspellResult {
        val next = AtomicInteger(0)
        val workers = threads.coerceIn(1, maxOf(1, entries.size))
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val futures =
                List(workers) {
                    pool.submit(
                        Callable {
                            val predictor = factory()
                            val results = conditions.map { MisspellConditionResult(it) }
                            val failures = ArrayList<MisspellFailure>()
                            while (true) {
                                val i = next.getAndIncrement()
                                if (i >= entries.size) break
                                runEntry(entries[i], predictor, results, failures, failureLimit)
                            }
                            results to failures
                        }
                    )
                }
            val total = conditions.map { MisspellConditionResult(it) }
            val failures = ArrayList<MisspellFailure>()
            for (f in futures) {
                val (r, fl) = f.get()
                for (k in total.indices) total[k].merge(r[k])
                failures.addAll(fl)
            }
            failures.sortWith(compareBy({ it.entry.typed }, { it.condition }, { it.context }))
            return MisspellResult(
                total,
                if (failures.size > failureLimit) failures.subList(0, failureLimit) else failures,
            )
        } finally {
            pool.shutdown()
        }
    }

    private fun runEntry(
        entry: MisspellEntry,
        predictor: Predictor,
        results: List<MisspellConditionResult>,
        failures: MutableList<MisspellFailure>,
        failureLimit: Int,
    ) {
        for ((ci, result) in results.withIndex()) {
            val cond = result.condition
            val typist =
                Typist(
                    keyboard,
                    TypistConfig(
                        sigmaX = cond.sigma,
                        sigmaY = cond.sigma,
                        omissionRate = 0f,
                        insertionRate = 0f,
                        transpositionRate = 0f,
                    ),
                )
            for (d in 0 until cond.draws) {
                val context = cond.contexts[d % cond.contexts.size]
                val typed =
                    typist.type(
                        entry.typed,
                        Random(EpisodeRunner.mix(seed, entry.index, ci * 64 + d)),
                    )
                val r =
                    predictor.predict(
                        PredictRequest(
                            TypingContext(context),
                            typed.input,
                            keyboard.layout,
                            maxCandidates,
                            autocorrectAllowed = true,
                        )
                    )
                val literal = typed.text
                val applied = r.autocorrect?.text?.takeIf { it != literal }
                val top = r.candidates.take(maxCandidates).map { it.text }
                val stats = if (entry.realWord) result.realWord else result.nonWord
                stats.add(entry.corrections, applied, top)
                result.byCategory
                    .getOrPut(entry.category + if (entry.realWord) " (real word)" else "") {
                        MisspellStats()
                    }
                    .add(entry.corrections, applied, top)
                val ok = applied != null && applied.lowercase() in entry.corrections
                if (!ok && failures.size < failureLimit) {
                    failures.add(MisspellFailure(cond.name, entry, context, literal, applied, top))
                }
            }
        }
    }

    companion object {
        /** Short, grammatically neutral left contexts (the word follows a trailing space). */
        val NEUTRAL_CONTEXTS = listOf("I really ", "It was ", "We need ")

        fun conditions(noisySigma: Float, draws: Int): List<MisspellCondition> = buildList {
            add(MisspellCondition("centre, no context", 0f, listOf(""), 1))
            add(
                MisspellCondition(
                    "centre, neutral context",
                    0f,
                    NEUTRAL_CONTEXTS,
                    NEUTRAL_CONTEXTS.size,
                )
            )
            if (noisySigma > 0f && draws > 0) {
                add(
                    MisspellCondition(
                        "sigma ${noisySigma}, neutral context",
                        noisySigma,
                        NEUTRAL_CONTEXTS,
                        draws,
                    )
                )
            }
        }
    }
}
