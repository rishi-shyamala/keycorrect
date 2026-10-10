package dev.keycorrect.feature.keyboard

import com.google.common.truth.Truth.assertWithMessage
import dev.keycorrect.core.decoder.DecoderSentenceContext
import dev.keycorrect.core.input.SentenceContext
import org.junit.Test

/**
 * The keyboard records learning context with `SentenceContext.lastWords`; the decoder looks it up
 * with its own `Text.lastSentenceWords`. They must agree on every input or the buckets never match.
 */
class SentenceContextParityTest {
    private val inputs =
        listOf(
            "",
            " ",
            "hello",
            "hello ",
            "hello world ",
            "one two three four ",
            "Hello. World ",
            "Hello! how are ",
            "What? Fine, thanks ",
            "wait… really ",
            "Dr. Smith went ",
            "line one\nline two ",
            "first\n",
            "end.",
            "end. ",
            "3.14 is pi ",
            "don't stop ",
            "don’t stop ",
            "‘quoted’ words ",
            "well-known facts ",
            "well‐known ‑ facts ",
            "a - b ",
            "trailing hyphen- ",
            "'leading apostrophe ",
            "rock 'n' roll ",
            "café au lait ",
            "café au ",
            "naïve user ",
            "emoji 😀 then ",
            "tab\tseparated words ",
            "numbers 42 and 7b ",
            "e.g. this ",
            "ok.Next word ",
            "multi  space   gaps ",
            "x".repeat(300) + " tail ",
            "word ".repeat(80),
            "abc ".repeat(60) + "Stop. then go ",
            "z".repeat(191) + "a b ",
            "y".repeat(193) + " c d ",
        )

    @Test
    fun bothImplementationsAgreeOnEveryInput() {
        for (text in inputs) {
            for (n in 0..4) {
                assertWithMessage("n=$n text=${text.take(40)}")
                    .that(DecoderSentenceContext.lastWords(text, n))
                    .isEqualTo(SentenceContext.lastWords(text, n))
            }
            assertWithMessage("default n, text=${text.take(40)}")
                .that(DecoderSentenceContext.lastWords(text, SentenceContext.CONTEXT_WORDS))
                .isEqualTo(SentenceContext.lastWords(text))
        }
    }
}
