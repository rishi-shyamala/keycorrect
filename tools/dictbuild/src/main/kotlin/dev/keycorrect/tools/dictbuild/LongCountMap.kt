package dev.keycorrect.tools.dictbuild

import java.util.Arrays

/**
 * Open-addressing (linear probing) hash map from packed n-gram keys to one or more int counters,
 * stored in flat primitive arrays (no boxing). Key `0` is the empty-slot sentinel and therefore not
 * a legal key; packed n-gram keys never are 0 because word ids start at 1 and the sentence start
 * marker (id 0) is only ever the *first* element of an n-gram with at least one real word.
 *
 * @param channels number of independent counters per key (we use one per corpus).
 */
class LongCountMap(initialCapacityLog2: Int = 12, private val channels: Int = 2) {
    private var log2 = initialCapacityLog2
    private var keys = LongArray(1 shl log2)
    private var counts = IntArray((1 shl log2) * channels)
    private var threshold = (keys.size * LOAD_FACTOR).toInt()

    var size = 0
        private set

    private fun home(key: Long, log2: Int): Int = ((key * GOLDEN) ushr (64 - log2)).toInt()

    fun increment(key: Long, channel: Int, by: Int = 1) {
        require(key != 0L) { "key 0 is reserved" }
        val mask = keys.size - 1
        var i = home(key, log2)
        while (true) {
            val k = keys[i]
            if (k == key) {
                counts[i * channels + channel] += by
                return
            }
            if (k == 0L) {
                keys[i] = key
                counts[i * channels + channel] += by
                if (++size > threshold) grow()
                return
            }
            i = (i + 1) and mask
        }
    }

    private fun slotOf(key: Long): Int {
        val mask = keys.size - 1
        var i = home(key, log2)
        while (true) {
            val k = keys[i]
            if (k == key) return i
            if (k == 0L) return -1
            i = (i + 1) and mask
        }
    }

    fun count(key: Long, channel: Int): Int {
        val s = slotOf(key)
        return if (s < 0) 0 else counts[s * channels + channel]
    }

    fun total(key: Long): Int {
        val s = slotOf(key)
        if (s < 0) return 0
        var t = 0
        for (c in 0 until channels) t += counts[s * channels + c]
        return t
    }

    /** All keys whose summed counters are at least [minTotal], in ascending key order. */
    fun sortedKeys(minTotal: Int): LongArray {
        var n = 0
        for (s in keys.indices) if (keys[s] != 0L && slotTotal(s) >= minTotal) n++
        val out = LongArray(n)
        var j = 0
        for (s in keys.indices) if (keys[s] != 0L && slotTotal(s) >= minTotal) out[j++] = keys[s]
        Arrays.sort(out)
        return out
    }

    private fun slotTotal(slot: Int): Int {
        var t = 0
        for (c in 0 until channels) t += counts[slot * channels + c]
        return t
    }

    private fun grow() {
        val oldKeys = keys
        val oldCounts = counts
        log2++
        keys = LongArray(1 shl log2)
        counts = IntArray((1 shl log2) * channels)
        threshold = (keys.size * LOAD_FACTOR).toInt()
        val mask = keys.size - 1
        for (s in oldKeys.indices) {
            val key = oldKeys[s]
            if (key == 0L) continue
            var i = home(key, log2)
            while (keys[i] != 0L) i = (i + 1) and mask
            keys[i] = key
            System.arraycopy(oldCounts, s * channels, counts, i * channels, channels)
        }
    }

    private companion object {
        const val GOLDEN = -0x61c8864680b583ebL // 2^64 / phi, Fibonacci hashing
        const val LOAD_FACTOR = 0.65
    }
}
