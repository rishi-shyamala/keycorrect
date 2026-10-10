package dev.keycorrect.tools.eval

import java.util.Locale
import kotlin.math.hypot

/** Text, JSON and CSV output of a learn-mode run. */
object LearnReport {
    private fun pct(n: Int, d: Int): Double = if (d == 0) Double.NaN else 100.0 * n / d

    private fun per1000(n: Int, d: Int): Double = if (d == 0) Double.NaN else 1000.0 * n / d

    private fun fmt(v: Double, digits: Int = 2): String =
        if (v.isNaN()) "-" else String.format(Locale.ROOT, "%.${digits}f", v)

    /** A row of the curve: value of each window for one arm, plus the whole run. */
    private class Metric(
        val name: String,
        val digits: Int,
        val value: (LearnWindow) -> Double,
    )

    private val METRICS =
        listOf(
            Metric("word accuracy % (final text)", 2) { pct(it.finalCorrect, it.words) },
            Metric("keyboard accuracy % (before reaction)", 2) {
                pct(it.keyboardCorrect, it.words)
            },
            Metric("personal-word accuracy %", 2) {
                pct(it.personalFinalCorrect, it.personalWords)
            },
            Metric("personal keyboard accuracy %", 2) {
                pct(it.personalKeyboardCorrect, it.personalWords)
            },
            Metric("personal raw (typed) accuracy %", 2) {
                pct(it.personalLiteralCorrect, it.personalWords)
            },
            Metric("false corrections / 1000 words", 2) { per1000(it.falseCorrections, it.words) },
            Metric("false corrections / 1000 personal", 1) {
                per1000(it.personalFalseCorrections, it.personalWords)
            },
            Metric("reverts / 1000 words", 2) { per1000(it.reverts, it.words) },
            Metric("reverts / 1000 personal", 1) { per1000(it.personalReverts, it.personalWords) },
            Metric("strip taps / 1000 words", 1) { per1000(it.taps, it.words) },
            Metric("retyped typos / 1000 words", 1) { per1000(it.retypes, it.words) },
            Metric("top-3 recall %", 2) { pct(it.top3, it.words) },
            Metric("personal top-3 recall %", 2) { pct(it.personalTop3, it.personalWords) },
        )

    fun total(windows: List<LearnWindow>): LearnWindow {
        val t = LearnWindow(windows.first().from, windows.last().to)
        for (w in windows) {
            t.words += w.words
            t.personalWords += w.personalWords
            t.finalCorrect += w.finalCorrect
            t.personalFinalCorrect += w.personalFinalCorrect
            t.keyboardCorrect += w.keyboardCorrect
            t.personalKeyboardCorrect += w.personalKeyboardCorrect
            t.literalCorrect += w.literalCorrect
            t.personalLiteralCorrect += w.personalLiteralCorrect
            t.falseCorrections += w.falseCorrections
            t.personalFalseCorrections += w.personalFalseCorrections
            t.reverts += w.reverts
            t.personalReverts += w.personalReverts
            t.taps += w.taps
            t.retypes += w.retypes
            t.top3 += w.top3
            t.personalTop3 += w.personalTop3
            t.afterRevertOccurrences += w.afterRevertOccurrences
            t.afterRevertCorrected += w.afterRevertCorrected
            t.personalAfterRevertCorrected += w.personalAfterRevertCorrected
        }
        return t
    }

    fun text(
        header: String,
        user: PersonalUser,
        learn: ArmResult,
        base: ArmResult,
        bias: Pair<Float, Float>,
    ): String {
        val sb = StringBuilder(header).append('\n')
        val lw = learn.windows
        val bw = base.windows
        val cols = lw.map { it.label } + "all"
        sb.append("== Learning curve: learning / no-learning baseline (same stream) ==\n")
        sb.append(String.format(Locale.ROOT, "%-40s", "words"))
        for (c in cols) sb.append(String.format(Locale.ROOT, "%-18s", c))
        sb.append('\n')
        for (m in METRICS) {
            sb.append(String.format(Locale.ROOT, "%-40s", m.name))
            val ls = lw + total(lw)
            val bs = bw + total(bw)
            for (i in ls.indices) {
                val cell = fmt(m.value(ls[i]), m.digits) + " / " + fmt(m.value(bs[i]), m.digits)
                sb.append(String.format(Locale.ROOT, "%-18s", cell))
            }
            sb.append('\n')
        }
        sb.append(String.format(Locale.ROOT, "%-40s", "personal words in window"))
        for (w in lw) sb.append(
            String.format(Locale.ROOT, "%-18s", "${w.personalWords}/${w.words}")
        )
        sb.append('\n')

        val trueErr = hypot(bias.first.toDouble(), bias.second.toDouble())
        sb.append("\n== Touch model (offsets in key widths / heights; true bias ")
        sb.append(String.format(Locale.ROOT, "%+.2f, %+.2f", bias.first, bias.second))
        sb.append(") ==\n")
        sb.append(String.format(Locale.ROOT, "%-40s", "window"))
        for (w in lw) sb.append(String.format(Locale.ROOT, "%-18s", w.label))
        sb.append('\n')
        fun row(name: String, f: (LearnWindow) -> String) {
            sb.append(String.format(Locale.ROOT, "%-40s", name))
            for (w in lw) sb.append(String.format(Locale.ROOT, "%-18s", f(w)))
            sb.append('\n')
        }
        row("keys with a learned offset (of 26)") { it.learnedKeys.toString() }
        row("mean learned offset (dx, dy)") {
            if (it.learnedKeys == 0) "-"
            else String.format(Locale.ROOT, "%+.3f, %+.3f", it.learnedMeanDx, it.learnedMeanDy)
        }
        row("learned error vs true bias (L2)") { fmt(it.learnedError, 3) }
        row("no learning: error (= |bias|)") { fmt(trueErr, 3) }
        row("oracle: per-key mean of all taps") { fmt(it.oracleError, 3) }
        row("attainable: mean of confirmed taps") { fmt(it.attainableError, 3) }

        sb.append("\n== Note-2 rule: a reverted literal is never corrected again ==\n")
        val lt = total(lw)
        val bt = total(bw)
        sb.append(
            String.format(
                Locale.ROOT,
                "after the first revert the same word was typed right %d times (learning) / %d (baseline);\n" +
                    "the keyboard changed it anyway %d times (learning, %d personal) / %d times (baseline, %d personal)\n",
                lt.afterRevertOccurrences,
                bt.afterRevertOccurrences,
                lt.afterRevertCorrected,
                lt.personalAfterRevertCorrected,
                bt.afterRevertCorrected,
                bt.personalAfterRevertCorrected,
            )
        )
        learn.userWords?.let {
            sb.append("\n== Learned user dictionary (learning arm) ==\n")
            sb.append(
                "${it.total} words: ${it.personal} of ${user.words.size} personal, ${it.otherTargets} other words the user typed, ${it.pollution.size} never-intended strings (promoted typos)\n"
            )
            if (it.pollution.isNotEmpty()) {
                sb.append("promoted typos: ${it.pollution.take(15).joinToString(", ")}\n")
            }
            if (it.personalMissing.isNotEmpty()) {
                sb.append("typed but not learned: ${it.personalMissing.joinToString(", ")}\n")
            }
            sb.append("suppressed correction pairs: ${learn.suppressions}\n")
        }

        val last = lw.last().label
        sb.append("\n== Personal words, last window ($last): weakest by final misses ==\n")
        sb.append(
            String.format(
                Locale.ROOT,
                "%-12s %5s %9s %9s %9s %9s %9s %9s\n",
                "word",
                "n",
                "raw ok",
                "kb ok",
                "final ok",
                "FC",
                "reverts",
                "FC base",
            )
        )
        val rows =
            learn.perWordLastWindow.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, WordTally>> {
                            it.value.occurrences - it.value.finalRight
                        }
                        .thenBy { it.key }
                )
                .take(10)
        for ((k, v) in rows) {
            val b = base.perWordLastWindow[k]
            sb.append(
                String.format(
                    Locale.ROOT,
                    "%-12s %5d %9d %9d %9d %9d %9d %9s\n",
                    k,
                    v.occurrences,
                    v.literalRight,
                    v.keyboardRight,
                    v.finalRight,
                    v.falseCorrections,
                    v.reverts,
                    b?.falseCorrections?.toString() ?: "-",
                )
            )
        }
        if (learn.examples.isNotEmpty()) {
            sb.append("\nwrongly corrected personal words in the last window (learning arm):\n")
            for (e in learn.examples.take(12)) sb.append("  $e\n")
        }
        sb.append(
            String.format(
                Locale.ROOT,
                "\nruntime: learning arm %.1f s, baseline %.1f s\n",
                learn.seconds,
                base.seconds,
            )
        )
        return sb.toString()
    }

    private fun windowMap(w: LearnWindow): Map<String, Any> =
        linkedMapOf(
            "from" to w.from,
            "to" to w.to,
            "words" to w.words,
            "personalWords" to w.personalWords,
            "wordAccuracy" to pct(w.finalCorrect, w.words) / 100,
            "keyboardAccuracy" to pct(w.keyboardCorrect, w.words) / 100,
            "rawAccuracy" to pct(w.literalCorrect, w.words) / 100,
            "personalAccuracy" to pct(w.personalFinalCorrect, w.personalWords) / 100,
            "personalKeyboardAccuracy" to pct(w.personalKeyboardCorrect, w.personalWords) / 100,
            "personalRawAccuracy" to pct(w.personalLiteralCorrect, w.personalWords) / 100,
            "falseCorrections" to w.falseCorrections,
            "falseCorrectionsPer1000" to per1000(w.falseCorrections, w.words),
            "personalFalseCorrections" to w.personalFalseCorrections,
            "personalFalseCorrectionsPer1000" to
                per1000(w.personalFalseCorrections, w.personalWords),
            "reverts" to w.reverts,
            "revertsPer1000" to per1000(w.reverts, w.words),
            "personalRevertsPer1000" to per1000(w.personalReverts, w.personalWords),
            "tapsPer1000" to per1000(w.taps, w.words),
            "retypesPer1000" to per1000(w.retypes, w.words),
            "top3Recall" to pct(w.top3, w.words) / 100,
            "personalTop3Recall" to pct(w.personalTop3, w.personalWords) / 100,
            "afterRevertOccurrences" to w.afterRevertOccurrences,
            "afterRevertCorrected" to w.afterRevertCorrected,
            "touch" to
                linkedMapOf(
                    "learnedKeys" to w.learnedKeys,
                    "meanDx" to w.learnedMeanDx,
                    "meanDy" to w.learnedMeanDy,
                    "learnedError" to w.learnedError,
                    "attainableKeys" to w.attainableKeys,
                    "attainableError" to w.attainableError,
                    "oracleError" to w.oracleError,
                ),
        )

    private fun armMap(a: ArmResult): Map<String, Any> {
        val m = LinkedHashMap<String, Any>()
        m["windows"] = a.windows.map { windowMap(it) }
        m["total"] = windowMap(total(a.windows))
        a.userWords?.let {
            m["userWords"] =
                linkedMapOf(
                    "total" to it.total,
                    "personal" to it.personal,
                    "otherTargets" to it.otherTargets,
                    "promotedTypos" to it.pollution,
                    "personalNotLearned" to it.personalMissing,
                )
            m["suppressedPairs"] = a.suppressions
        }
        m["examples"] = a.examples
        m["seconds"] = a.seconds
        return m
    }

    fun json(
        options: Map<String, Any>,
        user: PersonalUser,
        learn: ArmResult,
        base: ArmResult,
    ): Map<String, Any> =
        linkedMapOf(
            "options" to options,
            "personalUser" to
                linkedMapOf(
                    "words" to user.words,
                    "phrases" to user.phrases.map { it.joinToString(" ") },
                    "biasX" to user.config.biasX,
                    "biasY" to user.config.biasY,
                    "personalRate" to user.config.personalRate,
                    "zipfExponent" to user.config.zipfExponent,
                ),
            "learning" to armMap(learn),
            "baseline" to armMap(base),
        )

    fun curveCsv(learn: ArmResult, base: ArmResult): String = buildString {
        append(
            "arm,window,words,personal_words,accuracy,personal_accuracy,fc_per_1000,personal_fc_per_1000,reverts_per_1000,top3,personal_top3,touch_keys,touch_error\n"
        )
        for (a in listOf(learn, base)) for (w in a.windows) {
            append(
                listOf(
                        if (a.learning) "learning" else "baseline",
                        w.label,
                        w.words.toString(),
                        w.personalWords.toString(),
                        fmt(pct(w.finalCorrect, w.words), 3),
                        fmt(pct(w.personalFinalCorrect, w.personalWords), 3),
                        fmt(per1000(w.falseCorrections, w.words), 3),
                        fmt(per1000(w.personalFalseCorrections, w.personalWords), 3),
                        fmt(per1000(w.reverts, w.words), 3),
                        fmt(pct(w.top3, w.words), 3),
                        fmt(pct(w.personalTop3, w.personalWords), 3),
                        w.learnedKeys.toString(),
                        fmt(w.learnedError, 4),
                    )
                    .joinToString(",")
            )
            append('\n')
        }
    }

    fun wordsCsv(learn: ArmResult, base: ArmResult): String = buildString {
        append(
            "word,n,raw_ok,l_keyboard_ok,l_final_ok,l_fc,l_reverts,b_keyboard_ok,b_final_ok,b_fc,b_reverts\n"
        )
        for ((k, v) in learn.perWord.entries.sortedByDescending { it.value.occurrences }) {
            val b = base.perWord[k]
            append(
                listOf(
                        k,
                        v.occurrences,
                        v.literalRight,
                        v.keyboardRight,
                        v.finalRight,
                        v.falseCorrections,
                        v.reverts,
                        b?.keyboardRight ?: "",
                        b?.finalRight ?: "",
                        b?.falseCorrections ?: "",
                        b?.reverts ?: "",
                    )
                    .joinToString(",")
            )
            append('\n')
        }
    }
}
