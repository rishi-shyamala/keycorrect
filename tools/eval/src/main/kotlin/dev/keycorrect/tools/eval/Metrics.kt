package dev.keycorrect.tools.eval

/** Everything measured for one typed word, handed to [Metrics.record]. */
class WordOutcome(
    val target: String,
    val literal: String,
    val committed: String,
    /** The (up to 3) candidates shown at the end of typing, best first; never the literal. */
    val top: List<String>,
    /** True if the engine asked for the word to be replaced on space. */
    val autocorrectFired: Boolean,
    val typo: TypoKind,
    val touchSlips: Int,
    val targetInDictionary: Boolean,
    /** Keystrokes the user typed for this word (length of the typed text). */
    val typedKeys: Int,
    /**
     * Keystrokes with the "tap the suggestion as soon as it's in the top 3" user: taps count as
     * one. Equals [typedKeys] when the suggestion never helped; null when completion wasn't run.
     */
    val keysWithCompletion: Int?,
    /** Same, but the user may also tap a next-word prediction before typing the first char. */
    val keysWithNextWord: Int?,
) {
    val literalCorrect = literal.equals(target, ignoreCase = true)
    val finalCorrect = committed.equals(target, ignoreCase = true)
    val casingExact = committed == target
    val targetInTop3 = literalCorrect || top.any { it.equals(target, ignoreCase = true) }

    /** Failure category of an incorrectly committed word; null if the word is right. */
    fun failure(): String? {
        if (finalCorrect) return null
        return when {
            literalCorrect && !targetInDictionary -> "false correction: word not in dictionary"
            literalCorrect -> "false correction: real word changed"
            !targetInDictionary -> "unfixable: target not in dictionary"
            autocorrectFired -> "wrong correction: autocorrect chose another word"
            targetInTop3 -> "missed: target in top 3 but not applied"
            else -> "missed: target not in top 3"
        }
    }
}

/** Counters for one slice of words (all, one length bucket, ...). Mergeable, so threads can sum. */
class Stats {
    var words = 0L
    var literalCorrect = 0L
    var finalCorrect = 0L
    var casingExact = 0L
    var literalWrong = 0L
    var corrected = 0L
    var falseCorrections = 0L
    var autocorrectFired = 0L
    var firedWrong = 0L
    var missedTargetInTop3 = 0L
    var top3Hit = 0L
    var top1Hit = 0L
    var completionWords = 0L
    var completionBaseKeys = 0L
    var keysCompletion = 0L
    var keysNextWord = 0L

    fun add(o: WordOutcome) {
        words++
        if (o.literalCorrect) literalCorrect++ else literalWrong++
        if (o.finalCorrect) finalCorrect++
        if (o.casingExact) casingExact++
        if (!o.literalCorrect && o.finalCorrect) corrected++
        if (o.literalCorrect && !o.finalCorrect) falseCorrections++
        if (o.autocorrectFired) {
            autocorrectFired++
            if (!o.finalCorrect) firedWrong++
        }
        if (!o.literalCorrect && !o.finalCorrect && o.targetInTop3) missedTargetInTop3++
        if (o.targetInTop3) top3Hit++
        if (o.literalCorrect || o.top.firstOrNull()?.equals(o.target, ignoreCase = true) == true) {
            top1Hit++
        }
        if (o.keysWithCompletion != null) {
            completionWords++
            completionBaseKeys += o.typedKeys
            keysCompletion += o.keysWithCompletion
            keysNextWord += o.keysWithNextWord ?: o.keysWithCompletion
        }
    }

    fun merge(o: Stats) {
        words += o.words
        literalCorrect += o.literalCorrect
        finalCorrect += o.finalCorrect
        casingExact += o.casingExact
        literalWrong += o.literalWrong
        corrected += o.corrected
        falseCorrections += o.falseCorrections
        autocorrectFired += o.autocorrectFired
        firedWrong += o.firedWrong
        missedTargetInTop3 += o.missedTargetInTop3
        top3Hit += o.top3Hit
        top1Hit += o.top1Hit
        completionWords += o.completionWords
        completionBaseKeys += o.completionBaseKeys
        keysCompletion += o.keysCompletion
        keysNextWord += o.keysNextWord
    }

    // Rates (fractions in 0..1; NaN-safe: 0 when the denominator is 0).
    val wordAccuracy
        get() = ratio(finalCorrect, words)

    val casingAccuracy
        get() = ratio(casingExact, words)

    val rawAccuracy
        get() = ratio(literalCorrect, words)

    val correctionRate
        get() = ratio(corrected, literalWrong)

    val missedCorrections
        get() = literalWrong - corrected

    val missedRate
        get() = ratio(missedCorrections, literalWrong)

    /** False corrections per 1000 evaluated words (the critical metric). */
    val falseCorrectionsPer1000
        get() = 1000.0 * ratio(falseCorrections, words)

    /** Share of correctly typed words that autocorrect broke. */
    val falseCorrectionRateOfCorrect
        get() = ratio(falseCorrections, literalCorrect)

    val top3Recall
        get() = ratio(top3Hit, words)

    val top1Recall
        get() = ratio(top1Hit, words)

    /** Fraction of keystrokes saved by tapping completions (a tap counts as one keystroke). */
    val ksrCompletion
        get() = saved(keysCompletion)

    /** Same, when the user may also tap a next-word prediction before typing a char. */
    val ksrWithNextWord
        get() = saved(keysNextWord)

    private fun saved(keys: Long) =
        if (completionBaseKeys == 0L) 0.0 else 1.0 - keys.toDouble() / completionBaseKeys

    companion object {
        fun ratio(n: Long, d: Long): Double = if (d == 0L) 0.0 else n.toDouble() / d
    }
}

/** Next-word prediction and phrase offers at word boundaries (empty composing text). */
class NextWordStats {
    var predictions = 0L
    var top1 = 0L
    var top3 = 0L

    /** Same, restricted to positions after the first word of a sentence. */
    var midPredictions = 0L
    var midTop1 = 0L
    var midTop3 = 0L

    /** Targets that are in the dictionary (the only ones a dictionary model can ever predict). */
    var inDictPredictions = 0L
    var inDictTop1 = 0L
    var inDictTop3 = 0L

    /** Boundaries where a phrase was requested, offered, and how well it matched. */
    var phraseOpportunities = 0L
    var phrasesOffered = 0L
    var phraseWordsOffered = 0L
    var phraseWordsMatched = 0L
    var phraseFirstWordMatched = 0L
    var phrasesFullyMatched = 0L

    fun add(position: Int, targetInDictionary: Boolean, top: List<String>, target: String) {
        val hit1 = top.firstOrNull()?.equals(target, ignoreCase = true) == true
        val hit3 = top.any { it.equals(target, ignoreCase = true) }
        predictions++
        if (hit1) top1++
        if (hit3) top3++
        if (position > 0) {
            midPredictions++
            if (hit1) midTop1++
            if (hit3) midTop3++
        }
        if (targetInDictionary) {
            inDictPredictions++
            if (hit1) inDictTop1++
            if (hit3) inDictTop3++
        }
    }

    /** [offered] is null when no phrase was shown; [truth] are the next true words (any length). */
    fun addPhrase(offered: String?, truth: List<String>) {
        phraseOpportunities++
        if (offered == null) return
        val words = offered.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return
        phrasesOffered++
        phraseWordsOffered += words.size
        var matched = 0
        while (
            matched < words.size &&
                matched < truth.size &&
                words[matched].equals(truth[matched], ignoreCase = true)
        ) matched++
        phraseWordsMatched += matched
        if (matched >= 1) phraseFirstWordMatched++
        if (matched == words.size) phrasesFullyMatched++
    }

    fun merge(o: NextWordStats) {
        predictions += o.predictions
        top1 += o.top1
        top3 += o.top3
        midPredictions += o.midPredictions
        midTop1 += o.midTop1
        midTop3 += o.midTop3
        inDictPredictions += o.inDictPredictions
        inDictTop1 += o.inDictTop1
        inDictTop3 += o.inDictTop3
        phraseOpportunities += o.phraseOpportunities
        phrasesOffered += o.phrasesOffered
        phraseWordsOffered += o.phraseWordsOffered
        phraseWordsMatched += o.phraseWordsMatched
        phraseFirstWordMatched += o.phraseFirstWordMatched
        phrasesFullyMatched += o.phrasesFullyMatched
    }

    val top1Rate
        get() = Stats.ratio(top1, predictions)

    val top3Rate
        get() = Stats.ratio(top3, predictions)

    val midTop1Rate
        get() = Stats.ratio(midTop1, midPredictions)

    val midTop3Rate
        get() = Stats.ratio(midTop3, midPredictions)

    val inDictTop1Rate
        get() = Stats.ratio(inDictTop1, inDictPredictions)

    val inDictTop3Rate
        get() = Stats.ratio(inDictTop3, inDictPredictions)

    val phraseOfferRate
        get() = Stats.ratio(phrasesOffered, phraseOpportunities)

    /** Share of offered phrases whose every word matched the true continuation. */
    val phraseFullPrecision
        get() = Stats.ratio(phrasesFullyMatched, phrasesOffered)

    /** Share of offered phrases whose first word matched (what "accept next word" would get). */
    val phraseFirstWordPrecision
        get() = Stats.ratio(phraseFirstWordMatched, phrasesOffered)

    /** Matched words over offered words. */
    val phraseWordPrecision
        get() = Stats.ratio(phraseWordsMatched, phraseWordsOffered)

    val phraseAvgWords
        get() = if (phrasesOffered == 0L) 0.0 else phraseWordsOffered.toDouble() / phrasesOffered
}

/**
 * What one typed word ended as; recorded only for the rerank comparison (opt-in, see
 * [Metrics.decisions]). Held in memory, never written out.
 */
class WordDecision(
    val sentence: Int,
    val token: Int,
    val literalCorrect: Boolean,
    val finalCorrect: Boolean,
    val committed: String,
)

/** One row of the failures CSV. */
class FailureRow(
    val sentence: Int,
    val category: String,
    val target: String,
    val literal: String,
    val committed: String,
    val top: List<String>,
    val typo: TypoKind,
    val contextTail: String,
)

/** All measurements of one evaluation run; mergeable across worker threads. */
class Metrics {
    val all = Stats()
    val byLength: Map<String, Stats> = LENGTH_BUCKETS.associateWith { Stats() }
    val byVocab: Map<String, Stats> =
        linkedMapOf("in dictionary" to Stats(), "not in dictionary" to Stats())
    val byTypo: Map<String, Stats> = TypoKind.entries.associate { it.name.lowercase() to Stats() }
    val failureCategories = java.util.TreeMap<String, Long>()
    val nextWord = NextWordStats()
    var sentences = 0L
    var skippedTokens = 0L

    /** Per-word outcomes in arrival order; null (the default) when nobody asked for them. */
    var decisions: ArrayList<WordDecision>? = null

    fun record(o: WordOutcome) {
        all.add(o)
        byLength.getValue(lengthBucket(o.target.length)).add(o)
        byVocab.getValue(if (o.targetInDictionary) "in dictionary" else "not in dictionary").add(o)
        byTypo.getValue(o.typo.name.lowercase()).add(o)
        o.failure()?.let { failureCategories.merge(it, 1L, Long::plus) }
    }

    fun merge(o: Metrics) {
        all.merge(o.all)
        byLength.forEach { (k, v) -> v.merge(o.byLength.getValue(k)) }
        byVocab.forEach { (k, v) -> v.merge(o.byVocab.getValue(k)) }
        byTypo.forEach { (k, v) -> v.merge(o.byTypo.getValue(k)) }
        o.failureCategories.forEach { (k, v) -> failureCategories.merge(k, v, Long::plus) }
        nextWord.merge(o.nextWord)
        sentences += o.sentences
        skippedTokens += o.skippedTokens
        o.decisions?.let { decisions?.addAll(it) }
    }

    companion object {
        val LENGTH_BUCKETS = listOf("1", "2", "3", "4", "5-6", "7-8", "9+")

        fun lengthBucket(length: Int): String =
            when {
                length <= 4 -> length.coerceAtLeast(1).toString()
                length <= 6 -> "5-6"
                length <= 8 -> "7-8"
                else -> "9+"
            }
    }
}
