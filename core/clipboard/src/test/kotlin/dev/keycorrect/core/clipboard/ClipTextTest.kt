package dev.keycorrect.core.clipboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ClipTextTest {
    @Test
    fun collapsesWhitespaceToOneLine() {
        assertThat(ClipText.preview("  hello \n\t world\r\n  !  ")).isEqualTo("hello world !")
    }

    @Test
    fun shortTextIsUnchanged() {
        assertThat(ClipText.preview("abc", maxChars = 3)).isEqualTo("abc")
    }

    @Test
    fun longTextIsCutWithEllipsisWithinMaxChars() {
        val p = ClipText.preview("x".repeat(500))
        assertThat(p).hasLength(120)
        assertThat(p).endsWith("…")
        assertThat(ClipText.preview("abcdefghij", maxChars = 5)).isEqualTo("abcd…")
    }

    @Test
    fun cutDoesNotSplitSurrogatePairs() {
        val p = ClipText.preview("ab" + "😀".repeat(5), maxChars = 4)
        assertThat(p).isEqualTo("ab…")
    }

    @Test
    fun emptyTextGivesEmptyPreview() {
        assertThat(ClipText.preview("  \n ")).isEmpty()
    }
}
