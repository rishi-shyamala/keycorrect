package dev.keycorrect.core.learning.model

import kotlin.math.tanh

/**
 * The built-in tune overlay (N-26): (previous word, word) -> decayed signed score, kept apart from
 * the read-only dictionary. A conflict between what the user regularly does and what the built-in
 * model ranks first raises the preferred pair and lowers the other; the decoder adds `maxTune *
 * tanh(score / scale)` (ln units) to the built-in log-probability, so a single conflict is a slight
 * nudge and only regular ones approach the cap. Steps and totals are clamped (anti-poisoning).
 * Single-writer.
 */
internal class BaseTune(private val cfg: PersonalModelConfig, private val decay: Decay) {
    private val cells = ShardedMap<Cell>()

    val size: Int
        get() = cells.size

    fun add(prev: String, word: String, delta: Double, now: Long) {
        val p = Text.key(prev)
        val w = Text.key(word)
        if (p.isEmpty() || w.isEmpty()) return
        if (delta == 0.0 || !delta.isFinite()) return
        val d = delta.coerceIn(-cfg.tuneMaxStep, cfg.tuneMaxStep)
        val key = Text.join(p, w)
        val c = decay.addSigned(cells[key], d, now, cfg.tuneMaxScore)
        if (c.weight == 0.0) cells.remove(key) else cells.put(key, c)
    }

    fun enforceCaps(now: Long) {
        if (cells.size <= cfg.maxTuneEntries) return
        val target = (cfg.maxTuneEntries * 0.9).toInt()
        val items = ArrayList<Pair<String, Double>>(cells.size)
        cells.forEach { k, c -> items += k to kotlin.math.abs(decay.value(c, now)) }
        // Ties by key, so which tied entries go never depends on hash-map iteration order.
        items.sortWith(compareBy({ it.second }, { it.first }))
        for (i in 0 until cells.size - target) cells.remove(items[i].first)
    }

    fun freeze(now: Long) = FrozenTune(cells.freeze(), cells.size, decay, now, cfg)

    fun exportRows(): List<TuneRow> {
        val rows = ArrayList<TuneRow>()
        cells.forEach { k, c ->
            val p = k.split(Text.SEP)
            rows += TuneRow(p[0], p[1], c.weight, c.last)
        }
        return rows.sortedWith(compareBy({ it.prev }, { it.word }))
    }

    fun importRows(rows: List<TuneRow>) {
        cells.clear()
        for (r in rows) {
            if (r.prev.isEmpty() || r.word.isEmpty() || !r.score.isFinite()) continue
            cells.put(Text.join(r.prev, r.word), Cell(r.score, r.lastActiveMillis))
        }
    }
}

internal class FrozenTune(
    private val cells: FrozenMap<Cell>,
    val size: Int,
    private val decay: Decay,
    private val now: Long,
    private val cfg: PersonalModelConfig,
) {
    fun value(prev: String, word: String): Float {
        val cell = if (size == 0) null else cells[Text.join(Text.key(prev), Text.key(word))]
        val s = if (cell == null) 0.0 else decay.value(cell, now)
        return if (s == 0.0) 0f else (cfg.maxTune * tanh(s / cfg.tuneScale)).toFloat()
    }
}
