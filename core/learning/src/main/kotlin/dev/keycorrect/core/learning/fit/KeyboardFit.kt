package dev.keycorrect.core.learning.fit

import dev.keycorrect.core.learning.model.KeyTouchStatistics
import dev.keycorrect.core.learning.model.ProfileTouchStatistics
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The three size settings that decide how big a letter key's touch cell is (docs/PLAN.md §6.5). A
 * letter row is 10 cells wide, so a cell is `(screenWidthDp - 2 * edgePaddingDp) / 10` wide and
 * [keyHeightDp] tall. Cells tile the keyboard (a gap only insets the drawn key, see
 * KeyboardGeometry), so [keyGapDp] is carried for completeness but does not change which key a
 * touch hits.
 */
data class FitSizes(val keyHeightDp: Int, val keyGapDp: Int, val edgePaddingDp: Int)

/** Inclusive limits a proposal must stay within (the settings' clamp ranges). */
data class FitLimits(
    val keyHeightDp: IntRange,
    val keyGapDp: IntRange,
    val edgePaddingDp: IntRange,
)

/** What a [FitCandidate] changes. */
enum class FitKind {
    KEY_HEIGHT,
    KEY_GAP,
    EDGE_PADDING,
}

/** One proposed change; [percent] is the height percentage or the dp delta of gap and padding. */
data class FitCandidate(val kind: FitKind, val percent: Int, val sizes: FitSizes)

/** A recommendation and the predicted effect, as miss rates in 0..1. */
data class FitRecommendation(
    val kind: FitKind,
    val percent: Int,
    val proposed: FitSizes,
    val missBefore: Double,
    val missAfter: Double,
) {
    /** Share of today's misses the change removes, 0..1. */
    val relativeReduction: Double
        get() = if (missBefore > 0) (missBefore - missAfter) / missBefore else 0.0

    val absoluteReduction: Double
        get() = missBefore - missAfter

    val relativePercent: Int
        get() = (relativeReduction * PERCENT).roundToInt()

    private companion object {
        const val PERCENT = 100
    }
}

/**
 * Predicts, from per-key touch statistics, how often a touch aimed at a letter key lands outside
 * its cell, and recommends the size change that lowers that the most. Pure maths; never sees text.
 */
object KeyboardFit {
    /** Accepted words a profile needs before anything is recommended. */
    const val MIN_WORDS = 300

    /** A recommendation must remove at least this share of misses... */
    const val MIN_RELATIVE_REDUCTION = 0.10

    /** ...and at least this share of all touches (0.005 = half a percentage point). */
    const val MIN_ABSOLUTE_REDUCTION = 0.005

    /** A dismissed recommendation stays hidden this long (14 days). */
    const val DISMISS_WINDOW_MILLIS = 14L * 24 * 60 * 60 * 1000

    private const val LETTER_COLUMNS = 10
    private const val MIN_SIGMA = 1e-6
    private val HEIGHT_PERCENTS = listOf(5, 10, 15)
    private val GAP_DELTAS = listOf(-1, -2)
    private val EDGE_DELTAS = listOf(-1, -2)

    /** Width of one letter cell in dp. */
    fun cellWidthDp(screenWidthDp: Double, sizes: FitSizes): Double =
        ((screenWidthDp - 2.0 * sizes.edgePaddingDp) / LETTER_COLUMNS).coerceAtLeast(1.0)

    /** Error function, Abramowitz-Stegun 7.1.26 (absolute error under 1.5e-7). */
    internal fun erf(x: Double): Double {
        val t = 1.0 / (1.0 + 0.3275911 * abs(x))
        val poly =
            t *
                (0.254829592 +
                    t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))))
        val y = 1.0 - poly * exp(-x * x)
        return if (x >= 0) y else -y
    }

    private fun phi(z: Double) = 0.5 * (1.0 + erf(z / sqrt(2.0)))

    /** P(a normal(mean, sigma) value lies in [-half, half]); a point mass when sigma is ~0. */
    internal fun massInside(mean: Double, sigma: Double, half: Double): Double =
        if (sigma < MIN_SIGMA) {
            if (abs(mean) <= half) 1.0 else 0.0
        } else {
            phi((half - mean) / sigma) - phi((-half - mean) / sigma)
        }

    /**
     * Probability that a touch aimed at [key] lands outside a cell of [cellW] x [cellH] dp, when
     * the statistics (in key sizes) were recorded on cells of [refW] x [refH] dp. Treats the two
     * axes as independent normals around the key's mean offset.
     */
    fun missProbability(
        key: KeyTouchStatistics,
        refW: Double,
        refH: Double,
        cellW: Double,
        cellH: Double,
    ): Double {
        val inX = massInside(key.meanDx * refW, key.sigmaX * refW, cellW / 2)
        val inY = massInside(key.meanDy * refH, key.sigmaY * refH, cellH / 2)
        return (1.0 - inX * inY).coerceIn(0.0, 1.0)
    }

    /** Expected miss rate over [keys], each weighted by its sample count; 0 with no samples. */
    fun expectedMissRate(
        keys: List<KeyTouchStatistics>,
        refW: Double,
        refH: Double,
        cellW: Double,
        cellH: Double,
    ): Double {
        val total = keys.sumOf { it.sampleCount }
        if (total <= 0) return 0.0
        return keys.sumOf { missProbability(it, refW, refH, cellW, cellH) * it.sampleCount } / total
    }

    /** The candidate changes from [current] that stay within [limits] and actually differ. */
    fun candidates(current: FitSizes, limits: FitLimits): List<FitCandidate> = buildList {
        for (p in HEIGHT_PERCENTS) {
            val h = (current.keyHeightDp * (100 + p) / 100.0).roundToInt()
            if (h != current.keyHeightDp && h in limits.keyHeightDp) {
                add(FitCandidate(FitKind.KEY_HEIGHT, p, current.copy(keyHeightDp = h)))
            }
        }
        for (d in GAP_DELTAS) {
            val g = current.keyGapDp + d
            if (g in limits.keyGapDp) {
                add(FitCandidate(FitKind.KEY_GAP, d, current.copy(keyGapDp = g)))
            }
        }
        for (d in EDGE_DELTAS) {
            val e = current.edgePaddingDp + d
            if (e in limits.edgePaddingDp) {
                add(FitCandidate(FitKind.EDGE_PADDING, d, current.copy(edgePaddingDp = e)))
            }
        }
    }

    /** Miss rate of [profile]'s letter keys if the keyboard had [sizes], recorded on [current]. */
    fun missRate(
        profile: ProfileTouchStatistics,
        screenWidthDp: Double,
        current: FitSizes,
        sizes: FitSizes,
    ): Double =
        expectedMissRate(
            profile.keys,
            cellWidthDp(screenWidthDp, current),
            current.keyHeightDp.toDouble(),
            cellWidthDp(screenWidthDp, sizes),
            sizes.keyHeightDp.toDouble(),
        )

    /**
     * The candidate with the largest predicted drop in misses, or null when the profile has fewer
     * than [MIN_WORDS] accepted words, no candidate is in range, or the best one is not meaningful
     * (under [MIN_RELATIVE_REDUCTION] or [MIN_ABSOLUTE_REDUCTION]).
     */
    fun recommend(
        profile: ProfileTouchStatistics,
        screenWidthDp: Double,
        current: FitSizes,
        limits: FitLimits,
    ): FitRecommendation? {
        if (profile.wordCount < MIN_WORDS) return null
        val before = missRate(profile, screenWidthDp, current, current)
        val best =
            candidates(current, limits)
                .map { it to missRate(profile, screenWidthDp, current, it.sizes) }
                .minByOrNull { it.second } ?: return null
        val rec =
            FitRecommendation(
                best.first.kind,
                best.first.percent,
                best.first.sizes,
                before,
                best.second,
            )
        val meaningful =
            before > 0 &&
                rec.relativeReduction >= MIN_RELATIVE_REDUCTION &&
                rec.absoluteReduction >= MIN_ABSOLUTE_REDUCTION
        return rec.takeIf { meaningful }
    }

    /** True while a recommendation dismissed at [dismissedAt] must stay hidden at [now]. */
    fun isDismissed(dismissedAt: Long?, now: Long): Boolean =
        dismissedAt != null && now - dismissedAt in 0 until DISMISS_WINDOW_MILLIS
}
