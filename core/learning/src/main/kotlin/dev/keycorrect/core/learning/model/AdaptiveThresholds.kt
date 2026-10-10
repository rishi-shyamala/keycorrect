package dev.keycorrect.core.learning.model

/**
 * Adaptive autocorrect threshold and phrase gate (§6.1, §6.3 rows 6, 7, 18-20).
 *
 * Revert rate: a window of the last [PersonalModelConfig.outcomeWindow] autocorrect outcomes,
 * averaged with exponentially decaying weights (newest weight 1, each older entry x
 * [PersonalModelConfig.outcomeEwmaDecay]) plus [PersonalModelConfig.outcomePriorWeight]
 * pseudo-observations at the target rate, so a handful of outcomes only nudges the threshold. The
 * controller is proportional and asymmetric: rate above target raises the threshold (more
 * conservative) by gainUp * excess up to +2; below target lowers it by gainDown * deficit down to
 * -0.5. The phrase gate works the same way on the acceptance rate of offered phrase words.
 */
internal class AdaptiveThresholds(private val cfg: PersonalModelConfig) {
    class Autocorrect(val timeMillis: Long, val reverted: Boolean)

    class Phrase(val timeMillis: Long, val offered: Int, val accepted: Int)

    val autocorrect = ArrayDeque<Autocorrect>()
    val phrase = ArrayDeque<Phrase>()

    fun addAutocorrect(timeMillis: Long, reverted: Boolean) {
        autocorrect.addLast(Autocorrect(timeMillis, reverted))
        while (autocorrect.size > cfg.outcomeWindow) autocorrect.removeFirst()
    }

    fun addPhrase(timeMillis: Long, offered: Int, accepted: Int) {
        if (offered <= 0) return
        phrase.addLast(Phrase(timeMillis, offered, accepted.coerceIn(0, offered)))
        while (phrase.size > cfg.outcomeWindow) phrase.removeFirst()
    }

    /** Drops outcomes at or after [timeMillis]; returns how many. */
    fun forgetSince(timeMillis: Long): Int {
        val n = autocorrect.size + phrase.size
        autocorrect.removeAll { it.timeMillis >= timeMillis }
        phrase.removeAll { it.timeMillis >= timeMillis }
        return n - autocorrect.size - phrase.size
    }

    fun revertRate(): Double {
        var num = cfg.outcomePriorWeight * cfg.targetRevertRate
        var den = cfg.outcomePriorWeight
        var w = 1.0
        for (i in autocorrect.indices.reversed()) {
            if (autocorrect[i].reverted) num += w
            den += w
            w *= cfg.outcomeEwmaDecay
        }
        return num / den
    }

    fun acceptanceRate(): Double {
        var num = cfg.outcomePriorWeight * cfg.targetPhraseAcceptance
        var den = cfg.outcomePriorWeight
        var w = 1.0
        for (i in phrase.indices.reversed()) {
            num += w * phrase[i].accepted / phrase[i].offered
            den += w
            w *= cfg.outcomeEwmaDecay
        }
        return num / den
    }

    fun thresholdDelta(): Float {
        val e = revertRate() - cfg.targetRevertRate
        return if (e >= 0) (e * cfg.thresholdGainUp).toFloat().coerceAtMost(cfg.thresholdMaxUp)
        else (e * cfg.thresholdGainDown).toFloat().coerceAtLeast(-cfg.thresholdMaxDown)
    }

    /** Positive = phrases are rejected more than expected, so offer fewer. */
    fun phraseGateDelta(): Float {
        val e = cfg.targetPhraseAcceptance - acceptanceRate()
        return (e * cfg.phraseGateGain)
            .toFloat()
            .coerceIn(-cfg.phraseGateMaxDown, cfg.phraseGateMaxUp)
    }

    fun exportRows(): List<OutcomeRow> =
        autocorrect.map { OutcomeRow(0, it.timeMillis, if (it.reverted) 1 else 0, 0) } +
            phrase.map { OutcomeRow(1, it.timeMillis, it.offered, it.accepted) }

    fun importRows(rows: List<OutcomeRow>) {
        autocorrect.clear()
        phrase.clear()
        for (r in rows) {
            if (r.kind == 0) autocorrect.addLast(Autocorrect(r.timeMillis, r.a == 1))
            else phrase.addLast(Phrase(r.timeMillis, r.a, r.b))
        }
    }
}
