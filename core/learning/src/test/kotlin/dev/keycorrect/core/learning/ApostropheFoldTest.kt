package dev.keycorrect.core.learning

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.learning.classifier.fold
import dev.keycorrect.core.learning.model.Text
import org.junit.Test

/** Every learning-side comparison folds apostrophes the same way as the learned keys. */
class ApostropheFoldTest {
    private val variants = listOf("don't", "don’t", "don‘t", "donʼt", "don´t", "don`t", "don′t")

    @Test
    fun everyVariantFoldsToTheAsciiApostrophe() {
        for (v in variants) assertThat(foldApostrophes(v)).isEqualTo("don't")
    }

    @Test
    fun plainTextIsReturnedAsIs() {
        val s = "plain words"
        assertThat(foldApostrophes(s)).isSameInstanceAs(s)
    }

    @Test
    fun classifierFoldBucketAndModelKeyAgree() {
        for (v in variants) {
            val upper = v.uppercase()
            assertThat(fold(upper)).isEqualTo(Text.key(v))
            assertThat(contextBucket(listOf(upper), "TEXT")).isEqualTo("don't|TEXT")
        }
    }

    @Test
    fun aBacktickDoesNotJoinAWord() {
        assertThat(SentenceContext.isApostrophe('`')).isFalse()
        assertThat(SentenceContext.isApostrophe('’')).isTrue()
    }
}
