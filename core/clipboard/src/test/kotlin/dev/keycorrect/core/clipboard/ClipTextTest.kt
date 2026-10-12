package dev.keycorrect.core.clipboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ClipTextTest {
    @Test
    fun collapsesWhitespaceToOneLine() {
        assertThat(ClipText.preview("  hello \n\t world\r\n  !  ", masked = false))
            .isEqualTo("hello world !")
    }

    @Test
    fun shortTextIsUnchanged() {
        assertThat(ClipText.preview("abc", masked = false, maxChars = 3)).isEqualTo("abc")
    }

    @Test
    fun longTextIsCutWithEllipsisWithinMaxChars() {
        val p = ClipText.preview("x".repeat(500), masked = false)
        assertThat(p).hasLength(120)
        assertThat(p).endsWith("…")
        assertThat(ClipText.preview("abcdefghij", masked = false, maxChars = 5)).isEqualTo("abcd…")
    }

    @Test
    fun cutDoesNotSplitSurrogatePairs() {
        val p = ClipText.preview("ab" + "😀".repeat(5), masked = false, maxChars = 4)
        assertThat(p).isEqualTo("ab…")
    }

    @Test
    fun maskedShowsDotsAndLastTwoChars() {
        assertThat(ClipText.preview("482913", masked = true)).isEqualTo("••••13")
        assertThat(ClipText.preview("  482 913\n", masked = true)).isEqualTo("••••13")
    }

    @Test
    fun maskedShortOrEmptyShowsOnlyDots() {
        assertThat(ClipText.preview("12", masked = true)).isEqualTo("••••")
        assertThat(ClipText.preview("", masked = true)).isEqualTo("••••")
    }

    @Test
    fun maskedPreviewIgnoresMaxChars() {
        assertThat(ClipText.preview("1234567", masked = true, maxChars = 1)).isEqualTo("••••67")
    }

    @Test
    fun emptyTextGivesEmptyPreview() {
        assertThat(ClipText.preview("  \n ", masked = false)).isEmpty()
    }
}
