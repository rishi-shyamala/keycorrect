package dev.keycorrect.core.input

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GraphemesTest {
    @Test
    fun emptyTextHasNoCluster() {
        assertThat(Graphemes.lastClusterLength("")).isEqualTo(0)
    }

    @Test
    fun asciiClusterIsOneChar() {
        assertThat(Graphemes.lastClusterLength("hello")).isEqualTo(1)
    }

    @Test
    fun surrogatePairEmojiIsDeletedWhole() {
        assertThat(Graphemes.lastClusterLength("hi 😀")).isEqualTo(2)
    }

    @Test
    fun zwjFamilyEmojiIsDeletedWhole() {
        val family = "👨‍👩‍👧"
        assertThat(Graphemes.lastClusterLength("a$family")).isEqualTo(family.length)
    }

    @Test
    fun combiningAccentStaysWithBaseLetter() {
        assertThat(Graphemes.lastClusterLength("café")).isEqualTo(2)
    }
}
