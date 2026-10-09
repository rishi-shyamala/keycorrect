package dev.keycorrect.tools.eval

import java.util.Locale

/** One evaluated parameter combination. */
class SweepPoint(val overrides: Map<String, String>, val metrics: Metrics) {
    val stats: Stats
        get() = metrics.all

    /** False corrections of words the dictionary knows, per 1000 words (names are unfixable). */
    val falseInDictPer1000: Double
        get() =
            1000.0 * metrics.byVocab.getValue("in dictionary").falseCorrections /
                maxOf(1L, stats.words)

    /** Applied corrections that produced a wrong word, per 1000 words. */
    val wrongFixPer1000: Double
        get() = 1000.0 * stats.firedWrong / maxOf(1L, stats.words)

    val accuracy: Double
        get() = stats.wordAccuracy

    val falsePer1000: Double
        get() = stats.falseCorrectionsPer1000

    fun label(): String =
        overrides.entries.joinToString(" ") { "${it.key.substringAfter('.')}=${it.value}" }
}

object Sweep {
    /** `a=1,2;b=3,4` -> the cartesian product as override maps (first key varies slowest). */
    fun grid(spec: String): List<Map<String, String>> {
        var combos: List<Map<String, String>> = listOf(emptyMap())
        for (axis in spec.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val eq = axis.indexOf('=')
            require(eq > 0) { "bad sweep axis '$axis'" }
            val key = axis.substring(0, eq).trim()
            val values =
                axis.substring(eq + 1).split(',').map { it.trim() }.filter { it.isNotEmpty() }
            combos = combos.flatMap { c -> values.map { v -> c + (key to v) } }
        }
        return combos
    }

    /** Points that no other point beats on both axes (higher accuracy, fewer false corrections). */
    fun pareto(points: List<SweepPoint>): List<SweepPoint> = points.filter { p ->
        points.none { q ->
            q !== p &&
                q.accuracy >= p.accuracy &&
                q.falsePer1000 <= p.falsePer1000 &&
                (q.accuracy > p.accuracy || q.falsePer1000 < p.falsePer1000)
        }
    }

    /** The most accurate point within [targetFc] false corrections per 1000 words, if any. */
    fun recommend(points: List<SweepPoint>, targetFc: Double): SweepPoint? =
        points
            .filter { it.falsePer1000 <= targetFc }
            .maxWithOrNull(compareBy<SweepPoint>({ it.accuracy }, { -it.falsePer1000 }))

    fun table(
        points: List<SweepPoint>,
        defaultPoint: SweepPoint?,
        recommended: SweepPoint?,
    ): String {
        val frontier = pareto(points).toSet()
        val sb = StringBuilder()
        sb.append(
            String.format(
                Locale.ROOT,
                "  %-62s %9s %8s %9s %7s %s\n",
                "point",
                "accuracy",
                "corr.",
                "FC/1000",
                "top-3",
                "",
            )
        )
        for (p in points.sortedWith(compareBy({ it.falsePer1000 }, { -it.accuracy }))) {
            val marks = buildString {
                if (p in frontier) append("pareto ")
                if (p === defaultPoint) append("<default> ")
                if (p === recommended) append("<recommended>")
            }
            sb.append(
                String.format(
                    Locale.ROOT,
                    "  %-62s %8.2f%% %7.2f%% %9.2f %9.2f %9.1f %6.2f%% %s\n",
                    p.label(),
                    100 * p.accuracy,
                    100 * p.stats.correctionRate,
                    p.falsePer1000,
                    p.falseInDictPer1000,
                    p.wrongFixPer1000,
                    100 * p.stats.top3Recall,
                    marks,
                )
            )
        }
        return sb.toString()
    }

    fun csv(points: List<SweepPoint>): String {
        val keys = points.flatMap { it.overrides.keys }.distinct()
        val sb =
            StringBuilder(
                keys.joinToString(",") +
                    ",word_accuracy,correction_rate,false_corrections_per_1000,false_in_dictionary_per_1000,wrong_fixes_per_1000,top3_recall,words\n"
            )
        for (p in points) {
            sb.append(keys.joinToString(",") { p.overrides[it] ?: "" })
            sb.append(
                String.format(
                    Locale.ROOT,
                    ",%.5f,%.5f,%.3f,%.3f,%.2f,%.5f,%d\n",
                    p.accuracy,
                    p.stats.correctionRate,
                    p.falsePer1000,
                    p.falseInDictPer1000,
                    p.wrongFixPer1000,
                    p.stats.top3Recall,
                    p.stats.words,
                )
            )
        }
        return sb.toString()
    }
}
