package dev.keycorrect.core.learning.model

import dev.keycorrect.core.learning.RankingFeatures
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.tanh

/**
 * Online pairwise logistic regression over [RankingFeatures] (§6.1). Score = w . f(candidate);
 * training maximises sigma(w . (f(preferred) - f(other))) by SGD with an L2 pull toward the initial
 * weights (all zero = "defer to the decoder's own scoring"). Weights are bounded and a single
 * update's weight is capped, so the ranker can't drift wildly or be poisoned by one session.
 */
internal class FusionRanker(private val cfg: PersonalModelConfig) {
    class Checkpoint(val timeMillis: Long, val weights: DoubleArray)

    var weights = DoubleArray(DIM)
        private set

    var updates = 0L
        private set

    val checkpoints = ArrayList<Checkpoint>()
    var lastCheckpointMillis = Long.MIN_VALUE

    fun train(preferred: RankingFeatures, other: RankingFeatures, weight: Float) {
        val a = vector(preferred)
        val b = vector(other)
        val x = DoubleArray(DIM) { (a[it] - b[it]).coerceIn(-3.0, 3.0) }
        var z = 0.0
        for (i in 0 until DIM) z += weights[i] * x[i]
        val p = 1.0 / (1.0 + exp(-z))
        val wt = weight.coerceIn(0f, cfg.rankerMaxUpdateWeight).toDouble()
        val lr = cfg.rankerLearningRate
        for (i in 0 until DIM) {
            val g = wt * (1 - p) * x[i] - cfg.rankerL2 * weights[i]
            weights[i] = (weights[i] + lr * g).coerceIn(-cfg.rankerMaxWeight, cfg.rankerMaxWeight)
        }
        updates++
    }

    /** Takes an hourly checkpoint of the weights if one is due. */
    fun maybeCheckpoint(nowMillis: Long) {
        if (
            lastCheckpointMillis != Long.MIN_VALUE &&
                nowMillis - lastCheckpointMillis < cfg.checkpointIntervalMillis
        ) {
            return
        }
        checkpoints += Checkpoint(nowMillis, weights.copyOf())
        lastCheckpointMillis = nowMillis
        while (checkpoints.size > cfg.checkpointRetention) checkpoints.removeAt(0)
    }

    /**
     * Restores the weights from the latest checkpoint taken strictly before [timeMillis] (initial
     * weights if there is none: the model was born after T) and drops later checkpoints. Returns
     * the checkpoint time used, or null for a reset.
     */
    fun restoreBefore(timeMillis: Long): Long? {
        val cp = checkpoints.lastOrNull { it.timeMillis < timeMillis }
        checkpoints.removeAll { it.timeMillis >= timeMillis }
        weights = cp?.weights?.copyOf() ?: DoubleArray(DIM)
        lastCheckpointMillis = cp?.timeMillis ?: Long.MIN_VALUE
        return cp?.timeMillis
    }

    fun frozenWeights(): FloatArray = FloatArray(DIM) { weights[it].toFloat() }

    fun exportRows(): List<RankerWeightRow> {
        val rows = ArrayList<RankerWeightRow>()
        for (i in 0 until DIM) rows += RankerWeightRow(-1, i, weights[i])
        for (cp in checkpoints) for (i in 0 until DIM) rows +=
            RankerWeightRow(cp.timeMillis, i, cp.weights[i])
        return rows.sortedWith(compareBy({ it.checkpointMillis }, { it.index }))
    }

    fun importRows(rows: List<RankerWeightRow>, updates: Long, lastCheckpoint: Long) {
        weights = DoubleArray(DIM)
        checkpoints.clear()
        val byCp = HashMap<Long, DoubleArray>()
        for (r in rows) {
            if (r.index !in 0 until DIM) continue
            if (r.checkpointMillis == -1L) weights[r.index] = r.value
            else byCp.getOrPut(r.checkpointMillis) { DoubleArray(DIM) }[r.index] = r.value
        }
        for ((t, w) in byCp.toSortedMap()) checkpoints += Checkpoint(t, w)
        this.updates = updates
        lastCheckpointMillis = lastCheckpoint
    }

    companion object {
        const val DIM = 9

        /** Feature vector: scaled numeric fields, then booleans, then rank. */
        fun vector(f: RankingFeatures): DoubleArray {
            fun c(v: Double) = if (v.isNaN()) 0.0 else v.coerceIn(-6.0, 6.0)
            return doubleArrayOf(
                c(f.spatial * 0.2),
                c(f.lm * 0.2),
                c(f.edits * 0.5),
                c(f.completionChars.coerceIn(0, 20) * 0.1),
                c(f.userLm * 0.2),
                c(f.correctionBias * 0.25),
                if (f.isLiteral) 1.0 else 0.0,
                if (f.isUserWord) 1.0 else 0.0,
                f.rank.coerceIn(0, 8) * 0.25,
            )
        }

        /** Bounded score: soft clamp to +-[max]. */
        fun score(weights: FloatArray, f: RankingFeatures, max: Float): Float {
            val v = vector(f)
            var z = 0.0
            for (i in 0 until DIM) z += weights[i] * v[i]
            if (abs(z) < 1e-12) return 0f
            return (max * tanh(z / max)).toFloat()
        }
    }
}
