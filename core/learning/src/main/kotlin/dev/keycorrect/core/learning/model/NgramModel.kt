package dev.keycorrect.core.learning.model

import kotlin.math.ln

/**
 * User n-gram cache (uni/bi/trigram) with exponential decay on the active clock. Live, mutable,
 * single-writer; [freeze] produces the immutable query view used by snapshots.
 *
 * Probability: Witten-Bell-style interpolation. P1 = c(w)/N; P2 = l1 * c(a,w)/T(a) + (1-l1) * P1;
 * P3 = l2 * c(b,a,w)/T(b,a) + (1-l2) * P2 with l = T/(T+k), where T is the total decayed mass seen
 * after that context. Context totals are kept explicitly so eviction stays consistent.
 */
internal class NgramModel(private val cfg: PersonalModelConfig, private val decay: Decay) {
    private val uni = ShardedMap<Cell>()
    private val bi = ShardedMap<Cell>()
    private val tri = ShardedMap<Cell>()
    private val ctx1 = ShardedMap<Cell>()
    private val ctx2 = ShardedMap<Cell>()
    private var uniTotal = Cell(0.0, 0)

    private fun bump(map: ShardedMap<Cell>, key: String, delta: Double, now: Long) {
        val c = decay.add(map[key], delta, now)
        if (c.weight <= 0.0) map.remove(key) else map.put(key, c)
    }

    /** [context] are normalised keys, oldest first (at most 2 used); [word] is a normalised key. */
    fun add(context: List<String>, word: String, delta: Double, now: Long) {
        if (word.isEmpty() || delta == 0.0) return
        bump(uni, word, delta, now)
        uniTotal = decay.add(uniTotal, delta, now)
        if (context.isNotEmpty()) {
            val a = context.last()
            bump(bi, "$a $word", delta, now)
            bump(ctx1, a, delta, now)
            if (context.size >= 2) {
                val k2 = "${context[context.size - 2]} $a"
                bump(tri, "$k2 $word", delta, now)
                bump(ctx2, k2, delta, now)
            }
        }
    }

    /** Evicts the lowest decayed weights from any table over its cap (down to 90% of the cap). */
    fun enforceCaps(now: Long) {
        evict(uni, cfg.maxUnigrams, now) { _, v -> uniTotal = decay.add(uniTotal, -v, now) }
        evict(bi, cfg.maxBigrams, now) { k, v -> bump(ctx1, k.substringBefore(' '), -v, now) }
        evict(tri, cfg.maxTrigrams, now) { k, v -> bump(ctx2, k.substringBeforeLast(' '), -v, now) }
    }

    private inline fun evict(
        map: ShardedMap<Cell>,
        cap: Int,
        now: Long,
        onEvict: (String, Double) -> Unit,
    ) {
        if (map.size <= cap) return
        val target = (cap * 0.9).toInt()
        val items = ArrayList<Pair<String, Double>>(map.size)
        map.forEach { k, c -> items += k to decay.value(c, now) }
        items.sortBy { it.second }
        for (i in 0 until map.size - target) {
            val (k, v) = items[i]
            map.remove(k)
            onEvict(k, v)
        }
    }

    fun freeze(now: Long): FrozenNgrams =
        FrozenNgrams(
            uni.freeze(),
            bi.freeze(),
            tri.freeze(),
            ctx1.freeze(),
            ctx2.freeze(),
            uniTotal,
            decay,
            now,
            cfg,
        )

    fun exportRows(): List<NgramRow> {
        val rows = ArrayList<NgramRow>()
        uni.forEach { k, c -> rows += NgramRow(1, k, c.weight, c.last) }
        bi.forEach { k, c -> rows += NgramRow(2, k, c.weight, c.last) }
        tri.forEach { k, c -> rows += NgramRow(3, k, c.weight, c.last) }
        return rows.sortedWith(compareBy({ it.order }, { it.key }))
    }

    /** Replaces the cache; context totals are rebuilt from the rows at active time [now]. */
    fun importRows(rows: List<NgramRow>, now: Long) {
        uni.clear()
        bi.clear()
        tri.clear()
        ctx1.clear()
        ctx2.clear()
        uniTotal = Cell(0.0, now)
        for (r in rows) {
            val cell = Cell(r.weight, r.lastActiveMillis)
            val v = decay.value(cell, now)
            when (r.order) {
                1 -> {
                    uni.put(r.key, cell)
                    uniTotal = decay.add(uniTotal, v, now)
                }
                2 -> {
                    bi.put(r.key, cell)
                    bump(ctx1, r.key.substringBefore(' '), v, now)
                }
                3 -> {
                    tri.put(r.key, cell)
                    bump(ctx2, r.key.substringBeforeLast(' '), v, now)
                }
            }
        }
    }
}

/** Immutable n-gram view. */
internal class FrozenNgrams(
    private val uni: FrozenMap<Cell>,
    private val bi: FrozenMap<Cell>,
    private val tri: FrozenMap<Cell>,
    private val ctx1: FrozenMap<Cell>,
    private val ctx2: FrozenMap<Cell>,
    private val uniTotal: Cell,
    private val decay: Decay,
    private val now: Long,
    private val cfg: PersonalModelConfig,
) {
    private fun v(map: FrozenMap<Cell>, key: String): Double =
        map[key]?.let { decay.value(it, now) } ?: 0.0

    fun logProb(context: List<String>, word: String): Float? {
        val w = Text.key(word)
        if (w.isEmpty()) return null
        val c1 = v(uni, w)
        if (c1 < cfg.minWordEvidence) return null
        val n = decay.value(uniTotal, now)
        if (n < cfg.minTotalEvidence) return null
        var p = (c1 / n).coerceAtMost(1.0)
        val ctx = Text.contextKeys(context)
        if (ctx.isNotEmpty()) {
            val a = ctx.last()
            val t1 = v(ctx1, a)
            if (t1 > 0.0) {
                val lam = t1 / (t1 + cfg.interpolationK)
                p = lam * (v(bi, "$a $w") / t1).coerceAtMost(1.0) + (1 - lam) * p
            }
            if (ctx.size >= 2) {
                val k2 = "${ctx[ctx.size - 2]} $a"
                val t2 = v(ctx2, k2)
                if (t2 > 0.0) {
                    val lam = t2 / (t2 + cfg.interpolationK)
                    p = lam * (v(tri, "$k2 $w") / t2).coerceAtMost(1.0) + (1 - lam) * p
                }
            }
        }
        return ln(p.coerceAtLeast(1e-9)).toFloat()
    }
}
