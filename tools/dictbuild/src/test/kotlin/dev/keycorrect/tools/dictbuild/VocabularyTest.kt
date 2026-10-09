package dev.keycorrect.tools.dictbuild

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VocabularyTest {
    private fun e(word: String, f: Int, flags: Set<String> = emptySet()) =
        VocabEntry(word, f, f, flags)

    private val vocab =
        Vocabulary(
            listOf(
                e("the", 222),
                e("I", 196),
                e("i", 0, setOf(VocabEntry.NOT_A_WORD)),
                e("will", 165),
                e("Will", 113),
                e("Tom", 124),
                e("tom", 81),
                e("Apple", 109),
                e("apple", 107),
                e("don't", 185),
            )
        )

    private fun w(token: String, initial: Boolean = false) =
        vocab.word(vocab.lookup(token, initial))

    @Test
    fun notAWordEntriesGetNoId() {
        assertThat(vocab.size).isEqualTo(9)
        assertThat(vocab.lookup("i", false)).isEqualTo(vocab.lookup("I", false)) // via case-fold
        assertThat(w("i")).isEqualTo("I")
    }

    @Test
    fun exactCaseIsPreferred() {
        assertThat(w("Will")).isEqualTo("Will")
        assertThat(w("will")).isEqualTo("will")
        assertThat(w("Tom")).isEqualTo("Tom")
        assertThat(w("tom")).isEqualTo("tom")
    }

    @Test
    fun caseInsensitiveFallbackPicksMostFrequentVariant() {
        assertThat(w("THE")).isEqualTo("the")
        assertThat(w("WILL")).isEqualTo("will")
        assertThat(w("Don't")).isEqualTo("don't")
        assertThat(w("TOM")).isEqualTo("Tom")
    }

    @Test
    fun sentenceInitialCapitalisationFoldsIntoLowercase() {
        assertThat(w("The", initial = true)).isEqualTo("the")
        assertThat(w("Will", initial = true)).isEqualTo("will") // common word beats name
        assertThat(w("Apple", initial = true)).isEqualTo("apple") // too close to call -> common
        assertThat(w("Don't", initial = true)).isEqualTo("don't")
    }

    @Test
    fun clearProperNounsKeepTheirCapital() {
        assertThat(w("Tom", initial = true)).isEqualTo("Tom")
    }

    @Test
    fun unknownTokensAreOov() {
        assertThat(vocab.lookup("zzyzx", false)).isEqualTo(0)
        assertThat(vocab.lookup("Zzyzx", true)).isEqualTo(0)
    }
}
