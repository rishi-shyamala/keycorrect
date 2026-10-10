package dev.keycorrect.core.learning.model

import kotlin.math.tanh

/**
 * Correction memory: (typed, target, context bucket) -> decayed signed score, plus a bucket-free
 * (global, bucket = "") cell updated by the same evidence for back-off (§6.3 rows 5-8, 21, 22).
 * Per-event steps and the total score are clamped (anti-poisoning). Single-writer.
 */
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

    fun freeze(now: Long) = FrozenCorrections(cells.freeze(), decay, now, cfg)

    fun exportRows(): List<CorrectionRow> {
        val rows = ArrayList<CorrectionRow>()
        cells.forEach { k, c ->
            val p = k.split(Text.SEP)
            rows += CorrectionRow(p[0], p[1], p[2], c.weight, c.last)
        }
        return rows.sortedWith(compareBy({ it.typed }, { it.target }, { it.bucket }))
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

    fun bias(typed: String, candidate: String, bucket: String): Float {
        val t = Text.key(typed)
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
