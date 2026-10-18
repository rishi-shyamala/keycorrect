package dev.keycorrect.core.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HotwordPickerTest {
    @Test
    fun keepsLettersOnlyWordsOfSuitableLength() {
        val words =
            listOf(
                "kubectl" to 5.0,
                "a" to 9.0,
                "ab" to 1.0,
                "x".repeat(31) to 8.0,
                "x".repeat(30) to 0.5,
                "don't" to 7.0,
                "node.js" to 7.0,
                "r2d2" to 7.0,
                "123" to 7.0,
                "Rishi" to 6.0,
                "zero" to 0.0,
                "nan" to Double.NaN,
            )
        assertThat(HotwordPicker.pick(words))
            .containsExactly("Rishi", "kubectl", "ab", "x".repeat(30))
            .inOrder()
    }

    @Test
    fun dropsCommonWordsAndCaseDuplicatesAndLimits() {
        val words =
            listOf(
                "the" to 100.0,
                "Kubectl" to 3.0,
                "kubectl" to 5.0,
                "ansible" to 4.0,
                "terraform" to 2.0,
            )
        val picked = HotwordPicker.pick(words, limit = 2, isCommonWord = { it == "the" })
        assertThat(picked).containsExactly("kubectl", "ansible").inOrder()
    }

    @Test
    fun defaultLimitIsOneHundred() {
        val words =
            (1..150).map {
                "w${"a".repeat(it % 5 + 2)}${'a' + it % 26}${'a' + it / 26}" to it.toDouble()
            }
        assertThat(HotwordPicker.pick(words)).hasSize(100)
    }
}
