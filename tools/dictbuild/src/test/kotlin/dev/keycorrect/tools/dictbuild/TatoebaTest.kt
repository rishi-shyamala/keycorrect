package dev.keycorrect.tools.dictbuild

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TatoebaTest {
    @Test
    fun parsesLines() {
        val s = Tatoeba.parseLine("1276\teng\tLet's try something.")!!
        assertThat(s.id).isEqualTo(1276L)
        assertThat(s.lang).isEqualTo("eng")
        assertThat(s.text).isEqualTo("Let's try something.")
    }

    @Test
    fun rejectsMalformedLines() {
        assertThat(Tatoeba.parseLine("")).isNull()
        assertThat(Tatoeba.parseLine("abc\teng\tx")).isNull()
        assertThat(Tatoeba.parseLine("12\teng")).isNull()
        assertThat(Tatoeba.parseLine("12\teng\t  ")).isNull()
    }

    @Test
    fun heldOutSelectionIsDeterministicAndRoughlyTheRequestedShare() {
        val held = (1L..200_000L).count { Tatoeba.isHeldOut(it, 5) }
        assertThat(held).isIn(com.google.common.collect.Range.closed(9_000, 11_000))
        assertThat((1L..1000L).map { Tatoeba.isHeldOut(it, 5) })
            .isEqualTo((1L..1000L).map { Tatoeba.isHeldOut(it, 5) })
        assertThat((1L..1000L).none { Tatoeba.isHeldOut(it, 0) }).isTrue()
        assertThat((1L..1000L).all { Tatoeba.isHeldOut(it, 100) }).isTrue()
    }

    @Test
    fun textKeyIgnoresCaseAndWhitespaceRuns() {
        assertThat(Tatoeba.textKey("Hello  World")).isEqualTo(Tatoeba.textKey("hello world"))
        assertThat(Tatoeba.textKey("Hello World")).isNotEqualTo(Tatoeba.textKey("Hello Worlds"))
    }
}
