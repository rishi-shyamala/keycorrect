package dev.keycorrect.tools.eval

import java.util.Locale

/** Rendering of [Metrics] as a text table, JSON and a failures CSV. */
object Report {
    private fun pct(v: Double) = String.format(Locale.ROOT, "%6.2f%%", v * 100)

    private fun ratioText(n: Long, d: Long) = "$n / $d"

    fun statsMap(s: Stats): Map<String, Any> =
        linkedMapOf(
            "words" to s.words,
            "wordAccuracy" to s.wordAccuracy,
            "casingAccuracy" to s.casingAccuracy,
            "rawAccuracy" to s.rawAccuracy,
            "literalWrong" to s.literalWrong,
            "corrected" to s.corrected,
            "correctionRate" to s.correctionRate,
            "missedCorrections" to s.missedCorrections,
            "missedWithTargetInTop3" to s.missedTargetInTop3,
            "falseCorrections" to s.falseCorrections,
            "falseCorrectionsPer1000Words" to s.falseCorrectionsPer1000,
            "falseCorrectionRateOfCorrectlyTyped" to s.falseCorrectionRateOfCorrect,
            "autocorrectFired" to s.autocorrectFired,
            "autocorrectFiredWrong" to s.firedWrong,
            "top3Recall" to s.top3Recall,
            "top1Recall" to s.top1Recall,
            "keystrokeSavingsCompletion" to s.ksrCompletion,
            "keystrokeSavingsWithNextWordTap" to s.ksrWithNextWord,
        )

    fun nextWordMap(n: NextWordStats): Map<String, Any> =
        linkedMapOf(
            "predictions" to n.predictions,
            "top1" to n.top1Rate,
            "top3" to n.top3Rate,
            "midSentencePredictions" to n.midPredictions,
            "midSentenceTop1" to n.midTop1Rate,
            "midSentenceTop3" to n.midTop3Rate,
            "inDictionaryTargets" to n.inDictPredictions,
            "inDictionaryTop1" to n.inDictTop1Rate,
            "inDictionaryTop3" to n.inDictTop3Rate,
            "phraseOpportunities" to n.phraseOpportunities,
            "phrasesOffered" to n.phrasesOffered,
            "phraseOfferRate" to n.phraseOfferRate,
            "phraseAvgWords" to n.phraseAvgWords,
            "phraseFullPrecision" to n.phraseFullPrecision,
            "phraseFirstWordPrecision" to n.phraseFirstWordPrecision,
            "phraseWordPrecision" to n.phraseWordPrecision,
        )

    fun metricsMap(m: Metrics, includeNextWord: Boolean): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        out["sentences"] = m.sentences
        out["skippedTokens"] = m.skippedTokens
        out["overall"] = statsMap(m.all)
        out["byLength"] = m.byLength.mapValues { statsMap(it.value) }
        out["byTargetVocabulary"] = m.byVocab.mapValues { statsMap(it.value) }
        out["byTypoKind"] = m.byTypo.mapValues { statsMap(it.value) }
        out["failureCategories"] = m.failureCategories
        if (includeNextWord) out["nextWord"] = nextWordMap(m.nextWord)
        return out
    }

    fun latencyMap(l: LatencyResult): Map<String, Any> =
        linkedMapOf(
            "composing" to l.composing.toMap(),
            "nextWord" to l.nextWord.toMap(),
            "all" to l.all.toMap(),
        )

    fun text(
        title: String,
        m: Metrics,
        includeNextWord: Boolean,
        latency: Map<String, LatencyResult> = emptyMap(),
    ): String {
        val s = m.all
        val sb = StringBuilder()
        sb.append("== ").append(title).append(" ==\n")
        sb.append(
            "sentences ${m.sentences}, words evaluated ${s.words}, tokens skipped ${m.skippedTokens} (digits / placeholder names)\n\n"
        )
        fun row(name: String, value: String) =
            sb.append(String.format(Locale.ROOT, "  %-44s %s\n", name, value))
        sb.append("Word level (after autocorrect)\n")
        row("word accuracy (case-insensitive)", pct(s.wordAccuracy))
        row("casing accuracy (exact match)", pct(s.casingAccuracy))
        row("raw typed accuracy (no correction)", pct(s.rawAccuracy))
        row(
            "correction rate (wrong -> fixed)",
            pct(s.correctionRate) + "   " + ratioText(s.corrected, s.literalWrong),
        )
        row(
            "missed corrections",
            pct(s.missedRate) +
                "   " +
                ratioText(s.missedCorrections, s.literalWrong) +
                "   (target in top 3: ${s.missedTargetInTop3})",
        )
        row(
            "FALSE CORRECTIONS per 1000 words",
            String.format(Locale.ROOT, "%.2f", s.falseCorrectionsPer1000) +
                "   (${s.falseCorrections} words; ${pct(s.falseCorrectionRateOfCorrect).trim()} of correctly typed)",
        )
        row("autocorrect fired / of which wrong", "${s.autocorrectFired} / ${s.firedWrong}")
        row("top-3 recall (target or literal in strip)", pct(s.top3Recall))
        row("top-1 (literal or first suggestion)", pct(s.top1Recall))
        if (s.completionWords > 0) {
            row("keystroke savings, completion taps", pct(s.ksrCompletion))
            if (includeNextWord) row("keystroke savings, + next-word taps", pct(s.ksrWithNextWord))
        }
        if (includeNextWord) {
            val n = m.nextWord
            sb.append("\nNext word (empty composing)\n")
            row(
                "top-1 / top-3, all positions",
                "${pct(n.top1Rate)} / ${pct(n.top3Rate)}   n=${n.predictions}",
            )
            row(
                "top-1 / top-3, after the first word",
                "${pct(n.midTop1Rate)} / ${pct(n.midTop3Rate)}   n=${n.midPredictions}",
            )
            row(
                "top-1 / top-3, in-dictionary targets",
                "${pct(n.inDictTop1Rate)} / ${pct(n.inDictTop3Rate)}   n=${n.inDictPredictions}",
            )
            if (n.phraseOpportunities > 0) {
                sb.append("\nPhrase completion (at word boundaries)\n")
                row(
                    "offer rate",
                    pct(n.phraseOfferRate) +
                        "   ${n.phrasesOffered} of ${n.phraseOpportunities}, avg ${"%.2f".format(Locale.ROOT, n.phraseAvgWords)} words",
                )
                row("precision: whole phrase correct", pct(n.phraseFullPrecision))
                row("precision: first word correct", pct(n.phraseFirstWordPrecision))
                row("precision: words matched / offered", pct(n.phraseWordPrecision))
            }
        }
        sb.append("\nBy target length\n")
        sb.append(sliceHeader("length"))
        for ((k, v) in m.byLength) if (v.words > 0) sb.append(sliceRow(k, v))
        sb.append("\nBy target vocabulary\n")
        sb.append(sliceHeader("target"))
        for ((k, v) in m.byVocab) if (v.words > 0) sb.append(sliceRow(k, v))
        sb.append("\nBy typo kind (cognitive slip; touch noise applies to all)\n")
        sb.append(sliceHeader("typo"))
        for ((k, v) in m.byTypo) if (v.words > 0) sb.append(sliceRow(k, v))
        sb.append("\nFailure categories (wrong committed words)\n")
        for ((k, v) in m.failureCategories.entries.sortedByDescending { it.value }) {
            sb.append(
                String.format(
                    Locale.ROOT,
                    "  %-52s %7d  %5.2f%% of words\n",
                    k,
                    v,
                    100.0 * v / maxOf(1L, s.words),
                )
            )
        }
        if (latency.isNotEmpty()) {
            sb.append("\nLatency of predict (ms, single thread, after warm-up)\n")
            sb.append(
                String.format(
                    Locale.ROOT,
                    "  %-18s %-10s %8s %8s %8s %8s %8s\n",
                    "setting",
                    "kind",
                    "calls",
                    "p50",
                    "p95",
                    "p99",
                    "max",
                )
            )
            for ((name, l) in latency) {
                for ((kind, st) in listOf("composing" to l.composing, "next-word" to l.nextWord)) {
                    sb.append(
                        String.format(
                            Locale.ROOT,
                            "  %-18s %-10s %8d %8.3f %8.3f %8.3f %8.3f\n",
                            name,
                            kind,
                            st.count,
                            st.p50Ms,
                            st.p95Ms,
                            st.p99Ms,
                            st.maxMs,
                        )
                    )
                }
            }
        }
        return sb.toString()
    }

    private fun sliceHeader(first: String) =
        String.format(
            Locale.ROOT,
            "  %-18s %7s %8s %8s %8s %9s %8s %8s %8s\n",
            first,
            "words",
            "raw",
            "final",
            "corr.",
            "FC/1000",
            "top-3",
            "KSR",
            "missed",
        )

    private fun sliceRow(name: String, s: Stats) =
        String.format(
            Locale.ROOT,
            "  %-18s %7d %8s %8s %8s %9.2f %8s %8s %8s\n",
            name,
            s.words,
            pct(s.rawAccuracy).trim(),
            pct(s.wordAccuracy).trim(),
            pct(s.correctionRate).trim(),
            s.falseCorrectionsPer1000,
            pct(s.top3Recall).trim(),
            if (s.completionWords > 0) pct(s.ksrCompletion).trim() else "-",
            pct(s.missedRate).trim(),
        )

    fun failuresCsv(rows: List<FailureRow>): String {
        val sb =
            StringBuilder("sentence,category,typo,target,literal,committed,top3,context_tail\n")
        for (r in rows) {
            sb.append(r.sentence)
                .append(',')
                .append(csv(r.category))
                .append(',')
                .append(r.typo.name.lowercase())
                .append(',')
                .append(csv(r.target))
                .append(',')
                .append(csv(r.literal))
                .append(',')
                .append(csv(r.committed))
                .append(',')
                .append(csv(r.top.joinToString(" | ")))
                .append(',')
                .append(csv(r.contextTail))
                .append('\n')
        }
        return sb.toString()
    }

    private fun csv(s: String) = "\"" + s.replace("\"", "\"\"").replace("\n", " ") + "\""
}
