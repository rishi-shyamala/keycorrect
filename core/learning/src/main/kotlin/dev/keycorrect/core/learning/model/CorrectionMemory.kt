package dev.keycorrect.core.learning.model

import kotlin.math.tanh

/**
 * Correction memory: (typed, target, context bucket) -> decayed signed score, plus a bucket-free
 * (global, bucket = "") cell updated by the same evidence for back-off (§6.3 rows 5-8, 21, 22).
 * Per-event steps and the total score are clamped (anti-poisoning). Single-writer.
 */
/**
 * A learned autocorrection for the "What KeyCorrect learned" screen: the user accepted [typed] ->
 * [target] (both normalised keys: lower-case, folded apostrophes). [score] is the decayed evidence,
 * always positive here; [contextual] is true when nearly all of it was gathered after one
 * particular previous word, i.e. the correction mostly applies in that context.
 */
data class LearnedCorrection(
    val typed: String,
    val target: String,
    val score: Float,
    val contextual: Boolean,
)

internal class CorrectionMemory(private val cfg: PersonalModelConfig, private val decay: Decay) {
    private val cells = ShardedMap<Cell>()

    val size: Int
        get() = cells.size

    private fun upd(key: String, delta: Double, now: Long) {
        val c = decay.addSigned(cells[key], delta, now, cfg.correctionMaxScore)
        if (c.weight == 0.0) cells.remove(key) else cells.put(key, c)
    }

    fun add(typed: String, target: String, bucket: String, delta: Double, now: Long) {
        val t = Text.key(typed)
        val c = Text.key(target)
        if (t.isEmpty() || c.isEmpty() || delta == 0.0) return
        val d = delta.coerceIn(-cfg.correctionMaxStep, cfg.correctionMaxStep)
        val b = bucket.trim()
        if (b.isNotEmpty()) upd(Text.join(t, c, b), d, now)
        upd(Text.join(t, c, ""), d, now)
    }

    fun enforceCaps(now: Long) {
        if (cells.size <= cfg.maxCorrectionEntries) return
        val target = (cfg.maxCorrectionEntries * 0.9).toInt()
        val items = ArrayList<Pair<String, Double>>(cells.size)
        cells.forEach { k, c -> items += k to kotlin.math.abs(decay.value(c, now)) }
        items.sortBy { it.second }
        for (i in 0 until cells.size - target) cells.remove(items[i].first)
    }

    /** The strongest positive (typed, target) pairs by decayed bucket-free score. */
    fun top(now: Long, limit: Int): List<LearnedCorrection> {
        if (limit <= 0) return emptyList()
        class Acc(var global: Double = 0.0, var bestBucket: Double = Double.NEGATIVE_INFINITY)
        val pairs = HashMap<String, Acc>()
        cells.forEach { k, c ->
            val p = k.split(Text.SEP)
            val acc = pairs.getOrPut(Text.join(p[0], p[1])) { Acc() }
            val v = decay.value(c, now)
            if (p[2].isEmpty()) acc.global = v else acc.bestBucket = maxOf(acc.bestBucket, v)
        }
        return pairs
            .mapNotNull { (k, a) ->
                if (a.global < MIN_LISTED_SCORE) return@mapNotNull null
                val p = k.split(Text.SEP)
                LearnedCorrection(
                    p[0],
                    p[1],
                    a.global.toFloat(),
                    contextual = a.bestBucket >= CONTEXTUAL_SHARE * a.global,
                )
            }
            .sortedWith(
                compareByDescending<LearnedCorrection> { it.score }
                    .thenBy { it.typed }
                    .thenBy { it.target }
            )
            .take(limit)
    }

    /** Drops the pair's global and every context cell; returns whether anything was there. */
    fun forget(typed: String, target: String): Boolean {
        val t = Text.key(typed)
        val c = Text.key(target)
        val doomed = ArrayList<String>()
        cells.forEach { k, _ ->
            val p = k.split(Text.SEP)
            if (p[0] == t && p[1] == c) doomed += k
        }
        doomed.forEach { cells.remove(it) }
        return doomed.isNotEmpty()
    }

    fun freeze(now: Long) = FrozenCorrections(cells.freeze(), decay, now, cfg)

    fun exportRows(): List<CorrectionRow> {
        val rows = ArrayList<CorrectionRow>()
        cells.forEach { k, c ->
            val p = k.split(Text.SEP)
            rows += CorrectionRow(p[0], p[1], p[2], c.weight, c.last)
        }
        return rows.sortedWith(compareBy({ it.typed }, { it.target }, { it.bucket }))
    }

    private companion object {
        const val MIN_LISTED_SCORE = 0.1
        const val CONTEXTUAL_SHARE = 0.8
    }

    fun importRows(rows: List<CorrectionRow>) {
        cells.clear()
        for (r in rows) cells.put(
            Text.join(r.typed, r.target, r.bucket),
            Cell(r.score, r.lastActiveMillis),
        )
    }
}

internal class FrozenCorrections(
    private val cells: FrozenMap<Cell>,
    private val decay: Decay,
    private val now: Long,
    private val cfg: PersonalModelConfig,
) {
    private fun score(key: String): Double = cells[key]?.let { decay.value(it, now) } ?: 0.0

    /** The key of the last typed word: one typed word is asked about with every candidate. */
    private class TypedKey(val typed: String, val key: String)

    // Racy but safe: entries are immutable, and a miss only recomputes.
    private var lastTyped: TypedKey? = null

    private fun typedKey(typed: String): String {
        lastTyped?.let { if (it.typed == typed) return it.key }
        return Text.key(typed).also { lastTyped = TypedKey(typed, it) }
    }

    fun bias(typed: String, candidate: String, bucket: String): Float {
        val t = typedKey(typed)
        val c = Text.key(candidate)
        if (t.isEmpty() || c.isEmpty() || t == c) return 0f
        val sg = score(Text.join(t, c, ""))
        val b = bucket.trim()
        val s =
            if (b.isEmpty()) sg
            else {
                val sb = score(Text.join(t, c, b))
                sb + cfg.correctionBackoff * (sg - sb)
            }
        if (s == 0.0) return 0f
        return (cfg.maxCorrectionBias * tanh(s / cfg.correctionSaturation)).toFloat()
    }
}
