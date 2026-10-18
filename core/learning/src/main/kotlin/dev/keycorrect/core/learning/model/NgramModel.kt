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
        val n = map.size
        val keys = Array(n) { "" }
        val values = DoubleArray(n)
        var count = 0
        map.forEach { k, c ->
            keys[count] = k
            values[count++] = decay.value(c, now)
        }
        // Only the lowest n - target go, in ascending order (the context totals they feed are sums
        // whose rounding depends on that order). Ties by key, so which tied entries go never
        // depends on hash-map iteration order.
        val drop = n - target
        for (i in lowest(keys, values, count, drop)) {
            val k = keys[i]
            map.remove(k)
            onEvict(k, values[i])
        }
    }

    /** The indices of the [k] smallest entries by (value, key), ascending: a partial selection. */
    private fun lowest(keys: Array<String>, values: DoubleArray, n: Int, k: Int): List<Int> {
        if (k <= 0) return emptyList()
        val order =
            Comparator<Int> { i, j ->
                val c = values[i].compareTo(values[j])
                if (c != 0) c else keys[i].compareTo(keys[j])
            }
        val idx = Array(n) { it }
        if (k < n) {
            // Hoare quickselect: afterwards idx[0 until k] hold the k smallest, in no order.
            val kth = k - 1
            var lo = 0
            var hi = n - 1
            while (lo < hi) {
                val pivot = idx[(lo + hi) ushr 1]
                var i = lo
                var j = hi
                while (i <= j) {
                    while (order.compare(idx[i], pivot) < 0) i++
                    while (order.compare(idx[j], pivot) > 0) j--
                    if (i <= j) {
                        val t = idx[i]
                        idx[i] = idx[j]
                        idx[j] = t
                        i++
                        j--
                    }
                }
                if (kth <= j) hi = j else if (kth >= i) lo = i else break
            }
        }
        return idx.asList().subList(0, k).sortedWith(order)
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

    /** Total decayed unigram mass: the same for every query on this frozen view. */
    private val total = decay.value(uniTotal, now)

    /**
     * What one context contributes to every candidate asked about with it: the key prefixes and the
     * decayed context totals with their interpolation weights (all context-only, so computed once
     * rather than per candidate). A missing level has an empty prefix and a total of 0.
     */
    private class ContextFactors(val context: List<String>, val bi: Level, val tri: Level)

    /**
     * One interpolation level: key prefix, decayed context total, and the weight [lam] = t/(t+k).
     */
    private class Level(val prefix: String, val total: Double, val lam: Double)

    // Racy but safe: entries are immutable, and a miss only recomputes.
    private var lastContext: ContextFactors? = null

    private fun level(prefix: String, total: Double) =
        Level(prefix, total, total / (total + cfg.interpolationK))

    private fun factors(context: List<String>): ContextFactors {
        lastContext?.let { if (it.context == context) return it }
        val ctx = Text.contextKeys(context)
        var bi = level("", 0.0)
        var tri = bi
        if (ctx.isNotEmpty()) {
            val a = ctx.last()
            bi = level("$a ", v(ctx1, a))
            if (ctx.size >= 2) {
                val k2 = "${ctx[ctx.size - 2]} $a"
                tri = level("$k2 ", v(ctx2, k2))
            }
        }
        return ContextFactors(context.toList(), bi, tri).also { lastContext = it }
    }

    fun logProb(context: List<String>, word: String): Float? {
        val w = Text.key(word)
        if (w.isEmpty()) return null
        val c1 = v(uni, w)
        if (c1 < cfg.minWordEvidence) return null
        if (total < cfg.minTotalEvidence) return null
        var p = (c1 / total).coerceAtMost(1.0)
        val f = factors(context)
        if (f.bi.total > 0.0) {
            p =
                f.bi.lam * (v(bi, f.bi.prefix + w) / f.bi.total).coerceAtMost(1.0) +
                    (1 - f.bi.lam) * p
        }
        if (f.tri.total > 0.0) {
            p =
                f.tri.lam * (v(tri, f.tri.prefix + w) / f.tri.total).coerceAtMost(1.0) +
                    (1 - f.tri.lam) * p
        }
        return ln(p.coerceAtLeast(1e-9)).toFloat()
    }
}
