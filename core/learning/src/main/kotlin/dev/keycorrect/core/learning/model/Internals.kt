package dev.keycorrect.core.learning.model

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/** Key normalisation shared by every model: case-insensitive, apostrophes folded. */
internal object Text {
    /** Separator for composite keys; never occurs in a normalised word. */
    const val SEP = '\u001F'

    private fun foldChar(c: Char): Char =
        when (c) {
            '’',
            '‘',
            'ʼ',
            '´',
            '`',
            '′' -> '\''
            else -> c
        }

    /** Folds apostrophe variants to `'` but keeps case. */
    fun fold(s: String): String = String(CharArray(s.length) { foldChar(s[it]) }).trim()

    /** Normalised lookup key: folded, lower-cased, whitespace replaced so keys stay joinable. */
    fun key(s: String): String {
        val f = fold(s).lowercase(Locale.ROOT)
        if (f.none { it.isWhitespace() || it == SEP }) return f
        return f.map { if (it.isWhitespace() || it == SEP) '_' else it }.joinToString("")
    }

    /** At most the last two non-blank context keys, oldest first. */
    fun contextKeys(context: List<String>): List<String> =
        context.takeLast(2).map(::key).filter { it.isNotEmpty() }

    fun join(vararg parts: String): String = parts.joinToString(SEP.toString())
}

/**
 * A decayed value: [weight] as of active-clock time [last]. Immutable so snapshots can share it.
 */
internal data class Cell(val weight: Double, val last: Long)

/** Exponential decay on the model's active clock. */
internal class Decay(val halfLifeMillis: Double) {
    fun factor(elapsed: Long): Double =
        if (elapsed <= 0L) 1.0 else 2.0.pow(-elapsed.toDouble() / halfLifeMillis)

    fun value(c: Cell, now: Long): Double = c.weight * factor(now - c.last)

    /** Non-negative accumulation; a result below [EPS] becomes zero. */
    fun add(c: Cell?, delta: Double, now: Long): Cell {
        val base = if (c == null) 0.0 else value(c, now)
        val w = base + delta
        return Cell(if (w < EPS) 0.0 else w, now)
    }

    /** Signed accumulation clamped to ±[limit]. */
    fun addSigned(c: Cell?, delta: Double, now: Long, limit: Double): Cell {
        val base = if (c == null) 0.0 else value(c, now)
        val w = (base + delta).coerceIn(-limit, limit)
        return Cell(if (abs(w) < EPS) 0.0 else w, now)
    }

    companion object {
        const val EPS = 1e-3
    }
}

/**
 * Hash map split in shards with per-shard copy-on-write freezing: publishing a snapshot only copies
 * shards touched since the previous publish, so a 100k-entry table costs a few hundred entries per
 * publish, not a full copy. Values must be immutable. Not thread-safe (single writer).
 */
internal class ShardedMap<V : Any>(private val shardCount: Int = 128) {
    private val live = Array(shardCount) { HashMap<String, V>() }
    private var frozenShards: Array<Map<String, V>> = Array(shardCount) { emptyMap() }
    private val dirty = BooleanArray(shardCount)
    private var anyDirty = false
    private var cached = FrozenMap(frozenShards)

    var size = 0
        private set

    private fun idx(k: String): Int {
        val h = k.hashCode()
        return ((h xor (h ushr 16)) and Int.MAX_VALUE) % shardCount
    }

    operator fun get(k: String): V? = live[idx(k)][k]

    fun put(k: String, v: V) {
        val i = idx(k)
        if (live[i].put(k, v) == null) size++
        dirty[i] = true
        anyDirty = true
    }

    fun remove(k: String): V? {
        val i = idx(k)
        val old = live[i].remove(k)
        if (old != null) {
            size--
            dirty[i] = true
            anyDirty = true
        }
        return old
    }

    inline fun forEach(action: (String, V) -> Unit) {
        for (s in 0 until shards()) for ((k, v) in shard(s)) action(k, v)
    }

    fun shards(): Int = shardCount

    fun shard(i: Int): Map<String, V> = live[i]

    fun clear() {
        for (i in 0 until shardCount) {
            if (live[i].isNotEmpty()) {
                live[i].clear()
                dirty[i] = true
                anyDirty = true
            }
        }
        size = 0
    }

    fun freeze(): FrozenMap<V> {
        if (!anyDirty) return cached
        val next = frozenShards.copyOf()
        for (i in 0 until shardCount) {
            if (dirty[i]) {
                next[i] = HashMap(live[i])
                dirty[i] = false
            }
        }
        anyDirty = false
        frozenShards = next
        cached = FrozenMap(next)
        return cached
    }
}

/** Immutable view produced by [ShardedMap.freeze]. */
internal class FrozenMap<V : Any>(private val shards: Array<Map<String, V>>) {
    operator fun get(k: String): V? {
        val h = k.hashCode()
        return shards[((h xor (h ushr 16)) and Int.MAX_VALUE) % shards.size][k]
    }
}
