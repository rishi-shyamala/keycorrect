package dev.keycorrect.tools.dictbuild

import com.google.common.truth.Truth.assertThat
import java.util.Random
import org.junit.Test

class LongCountMapTest {
    @Test
    fun countsPerChannelAndGrows() {
        val map = LongCountMap(initialCapacityLog2 = 2, channels = 2)
        val reference = HashMap<Long, IntArray>()
        val rnd = Random(42)
        repeat(50_000) {
            val key = (rnd.nextInt(20_000) + 1).toLong() * 7919L
            val ch = rnd.nextInt(2)
            map.increment(key, ch)
            reference.getOrPut(key) { IntArray(2) }[ch]++
        }
        assertThat(map.size).isEqualTo(reference.size)
        for ((k, v) in reference) {
            assertThat(map.count(k, 0)).isEqualTo(v[0])
            assertThat(map.count(k, 1)).isEqualTo(v[1])
            assertThat(map.total(k)).isEqualTo(v[0] + v[1])
        }
        assertThat(map.count(999_999_937L, 0)).isEqualTo(0)
    }

    @Test
    fun sortedKeysFiltersByTotal() {
        val map = LongCountMap(channels = 2)
        map.increment(5, 0)
        map.increment(5, 1)
        map.increment(3, 0)
        map.increment(9, 1, by = 4)
        assertThat(map.sortedKeys(1).toList()).containsExactly(3L, 5L, 9L).inOrder()
        assertThat(map.sortedKeys(2).toList()).containsExactly(5L, 9L).inOrder()
        assertThat(map.sortedKeys(5).toList()).isEmpty()
    }
}
