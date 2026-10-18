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
    fun fold(s: String): String =
        // Fast path, no allocation: nothing to fold or trim (the common case).
        if (isFolded(s)) s else String(CharArray(s.length) { foldChar(s[it]) }).trim()

    /** Normalised lookup key: folded, lower-cased, whitespace replaced so keys stay joinable. */
    fun key(s: String): String {
        // Fast path, no allocation: keys are looked up for every candidate of every keystroke.
        if (isPlainKey(s)) return s
        val f = fold(s).lowercase(Locale.ROOT)
        return if (f.none(::isKeyBreak)) f
        else f.map { if (isKeyBreak(it)) '_' else it }.joinToString("")
    }

    /** Nothing to fold, and nothing for `trim` to remove. */
    private fun isFolded(s: String): Boolean =
        s.all { foldChar(it) == it } &&
            s.firstOrNull()?.isWhitespace() != true &&
            s.lastOrNull()?.isWhitespace() != true

    /**
     * Already its own [key]: ASCII, no capitals, no fold char (`` ` ``), no whitespace or [SEP].
     */
    private fun isPlainKey(s: String): Boolean = s.all {
        it.code < ASCII_END && it !in 'A'..'Z' && it != '`' && !isKeyBreak(it)
    }

    /** Chars a key may not contain (they would break [join]). */
    private fun isKeyBreak(c: Char): Boolean = c.isWhitespace() || c == SEP

    private const val ASCII_END = 0x80

    /** At most the last two non-blank context keys, oldest first. */
    fun contextKeys(context: List<String>): List<String> =
        context.takeLast(2).map(::key).filter { it.isNotEmpty() }

    fun join(vararg parts: String): String = parts.joinToString(SEP.toString())

    // The two- and three-part keys are built per candidate on every keystroke: no vararg array or
    // growing builder. Same result as the vararg form.
    fun join(a: String, b: String): String =
        StringBuilder(a.length + b.length + 1).append(a).append(SEP).append(b).toString()

    fun join(a: String, b: String, c: String): String =
        StringBuilder(a.length + b.length + c.length + 2)
            .append(a)
            .append(SEP)
            .append(b)
            .append(SEP)
            .append(c)
            .toString()
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

    private fun idx(k: String): Int = shardOf(k, shardCount)

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
    operator fun get(k: String): V? = shards[shardOf(k, shards.size)][k]
}

/**
 * The shard of [k]. Taken from the high bits of a multiplicative mix, not from the low bits of
 * `hash xor (hash ushr 16)`: each shard's HashMap picks buckets from those low bits, so sharding on
 * them left every key of a shard in a tiny fraction of its buckets (long, treeified chains).
 */
internal fun shardOf(k: String, count: Int): Int =
    ((k.hashCode() * GOLDEN_GAMMA) ushr SHARD_SHIFT) % count

private const val GOLDEN_GAMMA = -0x61c88647

/** Keeps the mixed high bits, which HashMap's own bucket choice does not use. */
private const val SHARD_SHIFT = 16

/**
 * How much of an n-gram, correction or touch weight with half-life [halfLifeMillis] is left after
 * [elapsedMillis] of active-clock time: the model's own [Decay], for the backup merge.
 */
fun decayFactor(halfLifeMillis: Long, elapsedMillis: Long): Double =
    Decay(halfLifeMillis.toDouble()).factor(elapsedMillis)
